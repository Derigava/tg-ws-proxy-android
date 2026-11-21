<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/banner-light.png">
    <img src="docs/assets/banner-dark.png" width="100%" alt="ByeBye TGWS Proxy — a local MTProto proxy and a DPI bypass for Android">
  </picture>
</p>

<h1 align="center">ByeBye TGWS Proxy</h1>

<p align="center"><b>A local MTProto proxy and a DPI bypass in one app</b></p>

<p align="center">
  <a href="https://github.com/Derigava/tg-ws-proxy-android/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Derigava/tg-ws-proxy-android?style=flat-square&label=release&color=65D9E8&labelColor=0B151B"></a>
  <a href="https://github.com/Derigava/tg-ws-proxy-android/actions/workflows/android-release.yml"><img alt="CI status" src="https://img.shields.io/github/actions/workflow/status/Derigava/tg-ws-proxy-android/android-release.yml?branch=main&style=flat-square&label=CI&labelColor=0B151B"></a>
  <a href="LICENSE"><img alt="License GPLv3" src="https://img.shields.io/badge/license-GPLv3-65D9E8?style=flat-square&labelColor=0B151B"></a>
  <img alt="Android 7.0+ ARM64" src="https://img.shields.io/badge/Android-7.0%2B%20ARM64-65D9E8?style=flat-square&labelColor=0B151B">
</p>

<h3 align="center"><a href="https://github.com/Derigava/tg-ws-proxy-android/releases/latest">Download the APK</a></h3>

<p align="center"><sub>Android 7.0+ · ARM64 · version 1.2.0 · no root</sub></p>

<p align="center"><a href="README.md">Русский</a> · <b>English</b></p>

## Quick start

1. Download `tgwsproxy-*-arm64.apk` from the [latest release](https://github.com/Derigava/tg-ws-proxy-android/releases/latest). You need Android 7.0+ and ARM64.
2. Install the APK. If Android warns about an unknown source, allow the install for this file only.
3. Open the app and allow notifications: they show the state and give you a stop button in the shade.
4. Tick the services and tap the power button: **Telegram** goes through the local MTProto proxy, **YouTube** and **Discord** through the ByeByeDPI VPN. For Telegram, tap "Apply in Telegram" afterwards; for the VPN, Android asks for consent once.

The rest of this page explains what the modules do, how the modes differ, and what to check when a start fails.

## What this is

One app with two independent modules. Both run on the device itself, without root: no flashing, no unlocked bootloader, no system patches.

- **TG-WS** runs a local MTProto proxy for Telegram on `127.0.0.1:1443`. The Telegram client connects to it like to any proxy, and the [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) `v1.11.4` core reaches Telegram data centres directly or through Cloudflare.
- **ByeByeDPI** is built on [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) `v.1.8.2`. It works against DPI at the TCP/UDP level: either as a system VPN for the whole phone or as a local SOCKS5 proxy that you configure in apps by hand.

```text
Telegram          → 127.0.0.1:1443 (MTProto) → Flowseal core → Telegram DC
Other apps        → ByeByeDPI VPN (or SOCKS5 127.0.0.1:1080) → internet
```

The Android wrapper grew out of [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android); the commit history is preserved.

## Why two modules

These are not two modes of one mechanism. They are different tools, and neither replaces the other.

**TG-WS** solves one narrow problem: give Telegram a route to its data centres when the direct connection fails. It does not capture phone traffic, does not take the VPN slot, and does not affect other apps. When a carrier interferes with connections to Telegram DCs, this module tries to carry them over WebSocket and Cloudflare.

**ByeByeDPI** solves a general problem: make traffic harder for a DPI system to classify. It helps not only Telegram but any app that breaks on filtering. The system VPN mode covers the whole device or selected apps; the SOCKS5 mode exposes a local proxy that you point a client at.

They coexist because they do not share a resource. TG-WS listens on a local port; ByeByeDPI takes the VPN slot or another local port. The app excludes itself from its own VPN tunnel, so proxy traffic cannot loop. Both modules can stay on at the same time; whether a given DPI strategy helps the Telegram route depends on the carrier and can only be checked on the device.

The full feature-by-feature comparison with ByeByeDPI is in [docs/byebyedpi/parity.md](docs/byebyedpi/parity.md).

## Features

- **A local MTProto proxy with no VPN.** The Flowseal `v1.11.4` core is unmodified apart from selecting the bundled Chaquopy OpenSSL build for AES on Android. Address, port and the 16-byte secret are on the main screen; a `tg://proxy` link and the "Apply in Telegram" button hand them to the client, and with several clients installed the app asks which one to open.
- **Direct route and Cloudflare.** DC addresses are automatic by default: a direct WebSocket to DC2 and DC4 first, the CF route as fallback. Under "Own Cloudflare" you can set CF proxy domains and your own Worker domains; Workers are tried first and do not share a quota with other users.
- **ByeByeDPI in two modes.** A system VPN for the whole device, or a local SOCKS5 proxy on `127.0.0.1:1080`. If another VPN is active, the app never drops it silently: it offers a confirmed replacement, a switch to SOCKS5, or cancel.
- **Strategy search and a desync editor.** The search tests strategies against HTTPS endpoints (site, API, YouTube images) and reports how many are reachable with each one. Good strategies are saved, pinned and applied with one tap; the manual editor exposes split, fake, OOB, TLS-record and host-modifier parameters.
- **App filter and domain lists.** A blacklist or whitelist of apps for the VPN mode, and custom domain lists for bypassing. ByeByeDPI settings export to a file and import back, including strategy history and selected apps.
- **Logs and a report.** Logs are split by source: TG-WS and ByeByeDPI write separately. "Info → Build report" collects the device, ABI, settings and errors from the last 24 hours; the secret is not included. "Share log" produces a file with the secret and Worker domains masked.

## Interface

The main screen holds three services and one power button. Telegram carries the `PROXY` tag and is served by TG-WS. YouTube and Discord carry the `VPN` tag and are served by ByeByeDPI in the selected mode. Below sit the status, "Apply in Telegram", "Change strategy", "Not working? Fix it", and a table with mode, pool, port, secret, version and core. "Settings → Advanced" is split into TG-WS and ByeByeDPI tabs; Logs and Info are at the bottom.

<p align="center">
  <img src="docs/assets/screens/01-two-modules.png" width="300" alt="Main screen: Telegram served by TG-WS, YouTube and Discord by ByeByeDPI">
</p>
<p align="center"><sub><b>Main screen.</b> Telegram goes through TG-WS, YouTube and Discord through ByeByeDPI; one button starts the selected services.</sub></p>

<p align="center">
  <img src="docs/assets/screens/02-tgws-live.png" width="300" alt="TG-WS connected: status, port 1443, secret and tg://proxy link">
</p>
<p align="center"><sub><b>TG-WS connected.</b> Status <code>[OK] CONNECTED</code>, port 1443, the secret and a ready <code>tg://proxy</code> link.</sub></p>

<p align="center">
  <img src="docs/assets/screens/03-dpi-vpn.png" width="300" alt="ByeByeDPI in VPN mode: YouTube selected, the VPN slot is taken">
</p>
<p align="center"><sub><b>ByeByeDPI, VPN mode.</b> YouTube is selected; the app warns that the Android VPN slot is in use.</sub></p>

<p align="center">
  <img src="docs/assets/screens/04-both-live.png" width="300" alt="Both modules running: Telegram and YouTube selected together">
</p>
<p align="center"><sub><b>Both at once.</b> Telegram and YouTube selected together: proxy and VPN run at the same time.</sub></p>

<p align="center">
  <img src="docs/assets/screens/05-strategy-test.png" width="300" alt="Recovery helper: a DPI strategy run against HTTPS endpoints">
</p>
<p align="center"><sub><b>Recovery helper.</b> A run over the built-in strategies: how many HTTPS endpoints each one reaches.</sub></p>

<p align="center">
  <img src="docs/assets/screens/06-cloudflare.png" width="300" alt="Cloudflare: CDN, no-TLS mode, own CF proxy and Worker domains">
</p>
<p align="center"><sub><b>Own Cloudflare.</b> CDN on, with your own CF proxy domains and a Worker domain.</sub></p>

<p align="center">
  <img src="docs/assets/screens/07-logs.png" width="300" alt="Logs: TG-WS and ByeByeDPI tabs, INFO, ERROR and NULL levels">
</p>
<p align="center"><sub><b>Logs.</b> TG-WS and ByeByeDPI tabs, INFO/ERROR/NULL levels; the secret is replaced with <code>[secret redacted]</code>.</sub></p>

<p align="center">
  <img src="docs/assets/screens/08-dpi-settings.png" width="300" alt="ByeByeDPI advanced settings: mode, app filter, arguments, strategy history">
</p>
<p align="center"><sub><b>ByeByeDPI settings.</b> VPN or SOCKS5 mode, app filter, ciadpi arguments and strategy history.</sub></p>

## First run

### TG-WS

1. Tick Telegram and tap the power button. Wait for `[OK] CONNECTED`: the status appears only after the local proxy is actually up.
2. Tap "Apply in Telegram". The app passes the address, port and secret to the client; if several clients are installed, it asks which one should receive them.
3. For manual setup, the main screen shows the address, port, secret and the `tg://proxy` link. Restart the proxy after changing settings.

The default port is `1443`. The `127.0.0.1` address keeps the proxy on this phone; `0.0.0.0` exposes it to the whole local network, so use it deliberately.

### ByeByeDPI

**VPN mode.** Tick YouTube or Discord and tap the power button. Android shows the system VPN consent dialog — without it the tunnel will not come up. After consent a notification appears and the status changes to `[OK] CONNECTED`. If another VPN is already running, the app offers to replace it with explicit confirmation, switch to SOCKS5, or cancel.

**SOCKS5 mode.** "Settings → Advanced → ByeByeDPI → Mode → SOCKS5". The local proxy takes `127.0.0.1:1080` and starts through the same service selection and power button. Point the apps that should use it at that port. This is not a VPN connection: traffic from other apps is unchanged.

**Strategy.** If connections keep failing, open "Not working? Fix it" → "01 · DPI strategy check", stop the module and run the test: the app tries the built-in strategies and shows how many HTTPS endpoints each reaches. A working strategy can be applied and pinned so it appears under "Change strategy". The check does not guarantee access to every site on every carrier.

## Requirements

| Item | Value |
|---|---|
| Android | 7.0 (API 24) or newer |
| Architecture | ARM64 (`arm64-v8a`) for the release APK; the `emulator` flavour with `x86_64` is for emulator tests and never ships |
| Root | Not required by either module |
| VPN | Only for ByeByeDPI's VPN mode; TG-WS and SOCKS5 work without it |
| Network | Internet access; for Cloudflare, reachability of the selected CF domains or your own Workers |

## Access

| Item | Why | Without it |
|---|---|---|
| Notifications | Show that a module is running and provide restart and stop buttons | Everything works, but you cannot control it from the shade |
| VPN consent (system dialog) | Lets ByeByeDPI bring up the VPN tunnel | VPN mode will not start; SOCKS5 and TG-WS still work |
| Battery optimisation | Opens the system battery list when a module stops with the screen off | Some devices may suspend background work |
| Vendor autostart | Xiaomi, Huawei, Honor, Oppo, realme, OnePlus, Vivo and Asus keep their own lists. The row appears only on those devices | The firmware may kill the app; the state cannot be read, check it manually |

The current state is under "Settings → Access". On Samsung, disabling battery optimisation and checking the sleeping-apps list is usually enough.

Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` for the VPN service. There is no storage permission and no `QUERY_ALL_PACKAGES`: the app list for the filter is read through a launcher intent, and settings files are picked with the system file dialog.

## Own Cloudflare

Under "Settings → Own Cloudflare" there are two fields, both accepting several comma-separated domains:

- **CF proxy domains** replace the shared domain list. They work while CloudFlare CDN is on.
- **CF Worker** — domains of your [Workers](https://github.com/Flowseal/tg-ws-proxy). The core tries them first, before CF proxies and the direct connection, even with CloudFlare CDN off. Your Worker does not share a quota with other users.

The issue report includes only the number of Workers, never the domains. An invalid domain is highlighted right in the field.

## Control without the UI

Long-pressing the launcher icon gives "Start proxy" and "Stop proxy" shortcuts. There is also a quick-settings tile. Automation apps (MacroDroid, Tasker) start activities of the `io.github.derigava.tgwsproxy` package:

| Action | Effect |
|---|---|
| `io.github.derigava.tgwsproxy.action.START` | Starts TG-WS with the saved settings |
| `io.github.derigava.tgwsproxy.action.STOP` | Stops TG-WS |
| `io.github.derigava.tgwsproxy.action.TOGGLE` | Toggles TG-WS |
| `io.github.derigava.tgwsproxy.action.dpi.START` | Starts ByeByeDPI in the selected mode |
| `io.github.derigava.tgwsproxy.action.dpi.STOP` | Stops ByeByeDPI |
| `io.github.derigava.tgwsproxy.action.dpi.TOGGLE` | Toggles ByeByeDPI |

The activities behind external actions are `com.amurcanov.tgwsproxy.ProxyShortcutActivity` and `com.amurcanov.tgwsproxy.DpiShortcutActivity`. Requests from automation apps require confirmation on screen: an external app cannot silently enable the VPN or change the strategy. Desktop shortcuts and the tile apply the action on tap.

## Diagnostics

- **Logs** — a tab with per-source separation: the TG-WS core journal and the ByeByeDPI engine log. You can copy, clear and filter by level.
- **Build report** — "Info → Build report". It contains the version, Android version, device, ABI, mode, port, pool size, Worker count and errors from the last 24 hours. No secret.
- **Share log** — the whole journal as a file, for long outages. The secret and Worker domains are masked at write time.
- **ByeByeDPI diagnostics** — address, mode, launch arguments, IPv6, app filter, domain lists and backups.

## Technical documentation

| Topic | Where |
|---|---|
| Parity with ByeByeDPI 1.8.2: screen → action → check | [docs/byebyedpi/parity.md](docs/byebyedpi/parity.md) |
| Release gates: what must be verified before publishing | [docs/byebyedpi/release-checklist.md](docs/byebyedpi/release-checklist.md) |
| Play Protect, appeals, signature verification | [docs/PLAY_PROTECT.md](docs/PLAY_PROTECT.md) |
| Investigation of mobile connection failures | [docs/ISSUE_3_INVESTIGATION.md](docs/ISSUE_3_INVESTIGATION.md) |
| Security and vulnerability reporting | [SECURITY.md](SECURITY.md) |
| Licences, third-party components, brand | [NOTICE.md](NOTICE.md) |
| Version history | [CHANGELOG.md](CHANGELOG.md) |
| Pinned core versions and file hashes | [upstream/flowseal.lock.json](upstream/flowseal.lock.json), [upstream/byebyedpi.lock.json](upstream/byebyedpi.lock.json) |

The sections below cover only what you need to install and run the app: [Own Cloudflare](#own-cloudflare), [automation](#control-without-the-ui), [access](#access), [diagnostics](#diagnostics), [building](#building) and [APK verification](#apk-verification).

## Transparency and security

**Three modes, three different properties.**

| Mode | What it does | What it does not do |
|---|---|---|
| MTProto proxy (TG-WS) | Accepts Telegram client connections on `127.0.0.1:1443` and carries them to Telegram DCs directly or through Cloudflare/Worker | Does not capture other apps' traffic, does not take the VPN slot, does not change your IP for Telegram |
| SOCKS5 (ByeByeDPI) | Runs a local proxy on `127.0.0.1:1080`; apps use it if configured to | Is not a VPN: unconfigured apps go direct |
| VPN (ByeByeDPI) | Runs a local `VpnService` that carries device or selected-app traffic and passes it through the DPI engine | Does not send traffic to a remote server, does not add encryption, does not hide your IP |

**What the app does not promise.** ByeByeDPI is not an anonymity tool and not a privacy VPN: it makes traffic harder for DPI systems to detect, but it does not encrypt connections and does not change your IP. No strategy guarantees access to every site on every carrier. Results depend on the route, DNS and the operator; if a connection hangs, restart the module and compare behaviour with Cloudflare on and off.

**Data.** There are no accounts, analytics or telemetry. The issue report contains the version, device, ABI, settings and errors; the MTProto secret is not included and Worker domains are replaced with `worker#N`. The journal is written in the background too, so a report covers outages that happened before you opened the app. Any intermediate hop — Cloudflare or your Worker — can see your IP and connection parameters; that is a property of any relayed route.

**Updates.** The app checks release metadata every 12 hours through WorkManager. There is no in-app download or install: the button opens the release page in a browser and Android confirms the installation. Since 1.1.1 the build requests neither `REQUEST_INSTALL_PACKAGES` nor `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and CI fails if either capability returns.

**Play Protect.** The APK is distributed outside Google Play, so Play Protect scans it as an app from the internet. In 1.1.0 the built-in self-updater caused a "harmful app" block; that capability is gone. If Play Protect blocks the official build, tap "More details" → "Install anyway" when the button is available; if it is not, send the Android version, APK name, SHA-256 and the file's source. Do not disable Play Protect entirely and do not install APKs from third-party sources. Details and the appeal template are in [docs/PLAY_PROTECT.md](docs/PLAY_PROTECT.md).

**Unofficial app.** This project is not affiliated with Telegram. Telegram is a trademark of its owner.

## APK verification

The official build is named `ByeBye TGWS Proxy`. Signing certificate SHA-256:

```text
6b7e992f93bd0bfd8c9395ddf92d0e3684d236b0f29cdf637dfca80e20b60195
```

Download the APK and `SHA256SUMS` from the same release and check both the file and the signature:

```bash
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs tgwsproxy-*-arm64.apk
```

`apksigner` must print the same certificate fingerprint. If the hash or the certificate does not match, the file did not come from this repository — do not install it.

## Building

You need JDK 21, Python 3.13, Android SDK platform 35 and build-tools 36.0.0. Point `ANDROID_HOME` or `local.properties` at the SDK.

```bash
python -m pip install -r scripts/requirements-test.txt
python scripts/run_python_tests.py
./gradlew :app:assembleArm64Release
```

On Windows use `gradlew.bat` instead of `./gradlew`, and run the Python tests with `py -3.13 -m unittest discover -s tests/python` and `tests/scripts`: some Flowseal network tests with short timeouts do not fit Windows timing, so the full suite runs on Linux.

Without a key you get an unsigned APK. To sign, set `TGWS_KEYSTORE_FILE`, `TGWS_KEYSTORE_PASSWORD`, `TGWS_KEY_ALIAS` and `TGWS_KEY_PASSWORD`. To confirm the core is untouched:

```bash
python scripts/sync_upstream.py --check
```

## How releases are made

`upstream/*.lock.json` records tags, commits and SHA-256 for every file of both cores. GitHub Actions reports the available Flowseal tag once a day; updating the core is `python scripts/sync_upstream.py --ref vX.Y.Z` followed by a diff review.

A release is started by hand: the **Android checks and releases** workflow on `main` with `publish_release` enabled. It runs the Python tests, builds the ARM64 APK and checks native library alignment for 16 KB memory pages, runs the release build on API 24, 25, 26 and API 35 emulators (16 KB pages on 35) plus instrumented tests. Only then is the APK signed with the existing certificate and published, with provenance attestation attached. If anything fails, no release is published.

Versioning: `VERSION` holds the `MAJOR.MINOR` line and CI picks the next free patch. `versionCode` is derived from the name (`major*10000 + minor*100 + patch`, 1.2.0 → 10200), so every build is strictly above the previous one.

## Support and contributing

- **Bugs and questions** — [Issues](https://github.com/Derigava/tg-ws-proxy-android/issues). Attach the report from "Info → Build report": it has the device, version, settings and errors, but no secret. For long outages add the file from "Info → Share log".
- **Vulnerabilities** — do not open a public issue. Use Security → Report a vulnerability as described in [SECURITY.md](SECURITY.md).
- **Releases and APKs** — [Releases](https://github.com/Derigava/tg-ws-proxy-android/releases). Verify `SHA256SUMS` and the certificate.
- **Protocol and core bugs** belong to [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy); Android-specific start-up issues belong here.
- **On-device testing** is the most useful contribution: the matrix in [docs/byebyedpi/parity.md](docs/byebyedpi/parity.md) is organised by scenario, and a real device run closes a gap CI cannot cover.

## Licences and authors

- **Android app** — [GPLv3](LICENSE). Based on the work of [amurcanov](https://github.com/amurcanov/tg-ws-proxy-android), with the commit history preserved. The original repository is archived, so the Android implementation is maintained here: fixes, compatibility with the current Flowseal core, and the second module.
- **TG-WS core** — [MIT](upstream/flowseal/LICENSE), [Flowseal](https://github.com/Flowseal/tg-ws-proxy) `v1.11.4`. The snapshot has one change: it selects the bundled Chaquopy OpenSSL library for AES on Android.
- **ByeByeDPI native part** — [GPL-3.0](upstream/byebyedpi/LICENSE), [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) `v.1.8.2`. Bundled dependencies: [hufrea/byedpi](https://github.com/hufrea/byedpi) (MIT), [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT), hev-socks5-core / hev-task-system / yaml (MIT), [lwip](https://savannah.nongnu.org/projects/lwip/) (BSD-3-Clause) and the preinstalled wintun (WireGuard). The exact set and licences are pinned in `upstream/byebyedpi.lock.json`.
- **Fonts** Unbounded, Onest and JetBrains Mono — [SIL OFL 1.1](licenses/fonts).
- **The `derigava_█` mark** is not distributed with the code: GPLv3 grants no rights to trademarks. See [NOTICE.md](NOTICE.md).

---

<p align="center"><a href="README.md">Русская версия</a></p>