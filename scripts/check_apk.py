"""Reject APKs containing native ELF segments incompatible with 16 KB pages."""
from __future__ import annotations

import argparse
import io
from pathlib import Path
import struct
import zipfile


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("Expected a little-endian 64-bit ELF library")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or phoff + size * count > len(data):
        raise ValueError("Invalid ELF program header table")
    aligns = []
    for index in range(count):
        header = phoff + index * size
        if struct.unpack_from("<I", data, header)[0] == 1:
            offset, virtual = struct.unpack_from("<QQ", data, header + 8)
            alignment = struct.unpack_from("<Q", data, header + 48)[0]
            if alignment < 16384 or alignment & (alignment - 1) or offset % 16384 != virtual % 16384:
                raise ValueError(f"PT_LOAD is not 16 KB aligned (alignment={alignment})")
            aligns.append(alignment)
    if not aligns:
        raise ValueError("ELF contains no loadable segments")
    return aligns


def check(apk: Path, abi: str) -> int:
    count = 0
    expected_machine = {"arm64-v8a": 183, "x86_64": 62}[abi]

    def inspect(data: bytes, location: str) -> None:
        nonlocal count
        try:
            load_alignments(data)
            if struct.unpack_from("<H", data, 18)[0] != expected_machine:
                raise ValueError(f"ELF architecture does not match {abi}")
        except ValueError as error:
            raise ValueError(f"{location}: {error}") from error
        count += 1

    with zipfile.ZipFile(apk) as archive:
        libraries = [item for item in archive.infolist() if item.filename.startswith("lib/") and item.filename.endswith(".so")]
        abis = {item.filename.split("/")[1] for item in libraries}
        if abis != {abi}:
            raise ValueError(f"Unexpected packaged ABIs: {sorted(abis)}; expected only {abi}")
        if any("tgwsproxy" in item.filename for item in libraries):
            raise ValueError("Legacy Rust proxy library is still packaged")
        for item in libraries:
            try:
                inspect(archive.read(item), item.filename)
                if item.compress_type == zipfile.ZIP_STORED:
                    with apk.open("rb") as stream:
                        stream.seek(item.header_offset + 26)
                        name_size, extra_size = struct.unpack("<HH", stream.read(4))
                    data_offset = item.header_offset + 30 + name_size + extra_size
                    if data_offset % 16384:
                        raise ValueError("Uncompressed native library is not APK-page aligned")
            except ValueError as error:
                raise ValueError(f"{item.filename}: {error}") from error
        # Chaquopy stores cryptography, cffi and other extension modules inside
        # ZIP-compatible .imy archives; Android later loads their extracted .so.
        for item in archive.infolist():
            if item.filename.startswith("assets/chaquopy/") and item.filename.endswith(".imy"):
                with zipfile.ZipFile(io.BytesIO(archive.read(item))) as python_archive:
                    for extension in python_archive.infolist():
                        if extension.filename.endswith(".so"):
                            inspect(python_archive.read(extension), f"{item.filename}!/{extension.filename}")
    if not count:
        raise ValueError("APK contains no native Python runtime libraries")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    options = parser.parse_args()
    print(f"16 KB compatibility: {check(options.apk, options.abi)} native libraries passed")
