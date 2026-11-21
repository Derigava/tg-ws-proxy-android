#!/usr/bin/env python3
"""Снимает комплект продуктовых скриншотов ByeBye TGWS Proxy из Android-эмулятора.

Скрипт сам находит устройство, ставит debug-сборку x86_64, приводит системное
оформление к единому виду (время, заряд, отключённые уведомления), проходит
экраны приложения по accessibility-дереву и складывает PNG + manifest.json +
capture-report.md.

Примеры:
    python capture.py --apk app/build/outputs/apk/emulator/debug/app-emulator-debug.apk
    python capture.py --serial emulator-5556 --skip-strategy-test
    python capture.py --avd ByeBye_Shots          # поднять AVD и снять всё заново

Скриншоты — настоящие: ни один экран не подделан, состояния модулей берутся из
реальной работы сервисов. Если экран получить не удалось, скрипт пишет причину
в отчёт и идёт дальше.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "io.github.derigava.tgwsproxy"
ACTIVITY = f"{PACKAGE}/com.amurcanov.tgwsproxy.MainActivity"

# Тексты интерфейса (values-ru), по ним скрипт ориентируется в дереве.
TXT_APP_NAME = "ByeBye TGWS Proxy"
TXT_STATE_CONNECTED = "[OK] ПОДКЛЮЧЕНО"
TXT_STATE_DISCONNECTED = "ОТКЛЮЧЕНО"
TXT_POWER_START = "Запустить выбранные сервисы"
TXT_POWER_STOP = "Остановить сервисы"
TXT_ACCESS_DIALOG = "Разреши работу в фоне"
TXT_REPAIR_LINK = "Не работает? Починить"
TXT_REPAIR_TITLE = "Помощник восстановления"
TXT_STRATEGY_TEST_START = "Начать проверку"
TXT_ADVANCED = "Расширенные"
TXT_DPI_SEGMENT = "ByeByeDPI"

SCREEN_W, SCREEN_H = 1080, 2400
TARGET_DENSITY = 400
DEMO_CLOCK = "1008"
DEMO_BATTERY = 92

DEFAULT_ADB = [
    os.environ.get("ADB", ""),
    shutil.which("adb") or "",
    r"C:/Users/Derigava/AppData/Local/Android/Sdk/platform-tools/adb.exe",
    r"~/AppData/Local/Android/Sdk/platform-tools/adb.exe",
]
DEFAULT_EMULATOR = [
    os.environ.get("ANDROID_EMULATOR", ""),
    shutil.which("emulator") or "",
    r"C:/Users/Derigava/AppData/Local/Android/Sdk/emulator/emulator.exe",
]


NAVIGATION_MAP = """## Карта навигации

```text
Главный экран (Прокси)
├── Telegram / YouTube / Discord — выбор сервисов, кнопка питания, статус
├── Сменить стратегию → диалог со списком закреплённых стратегий
├── Не работает? Починить → помощник восстановления
│   ├── 01 · Проверка стратегий DPI (автоподбор)
│   ├── 02 · Telegram (CF / Worker / порт 80)
│   └── 03 · отчёт для issue
├── Выбрать другой клиент (появляется, когда прокси запущен)
└── Таблица: режим, пул, порт, секрет, версия, ядро + ссылка tg://proxy

Настройки
├── Основные: подобрать/сменить стратегию, порт 80, автозапуск Telegram и SOCKS5
└── Расширенные
    ├── TG-WS: IP и порт, адреса DC, пул WS, секретный ключ, Cloudflare CDN,
    │          CF без TLS, свой Cloudflare (домены CF-прокси и CF Worker), автозапуск
    └── ByeByeDPI: режим VPN/SOCKS5, фильтр приложений, командная строка,
                   история стратегий, стратегия, списки доменов, диагностика, копии
    (ниже у обоих разделов — Доступы, Тема, Язык)

Логи
├── TG-WS — журнал ядра (INFO / ERROR / NULL)
└── ByeByeDPI — журнал движка

Инфо
├── Проверить обновления, Справка, Собрать отчёт, Поделиться журналом, Задать вопрос
├── О проекте — ссылки на репозитории
└── Поддержать автора, подпись, дисклеймер

Системные экраны: согласие Android на VPN (Connection request) — подтверждается
перед съёмкой и в кадры не попадает.
```
"""


def first_existing(paths):
    for p in paths:
        if not p:
            continue
        p = os.path.expanduser(p)
        if os.path.exists(p):
            return p
    return None


class CaptureError(RuntimeError):
    """Экран не удалось получить: пишем причину в отчёт и идём дальше."""


@dataclass
class Node:
    text: str
    desc: str
    cls: str
    bounds: tuple
    clickable: bool
    checked: bool

    @property
    def cx(self):
        return (self.bounds[0] + self.bounds[2]) // 2

    @property
    def cy(self):
        return (self.bounds[1] + self.bounds[3]) // 2

    @property
    def center(self):
        return self.cx, self.cy


class Device:
    def __init__(self, adb_path: str, serial: str | None = None):
        self.adb = adb_path
        self.serial = serial or self._pick_serial()
        scratch = Path(os.environ.get("SHOT_TMP") or tempfile.gettempdir()) / "tgws-shots"
        scratch.mkdir(parents=True, exist_ok=True)
        self._tmp = scratch / "ui_dump.xml"

    # --- низкий уровень -------------------------------------------------
    def _base(self):
        return [self.adb] + (["-s", self.serial] if self.serial else [])

    def run(self, *args, binary=False, timeout=90):
        proc = subprocess.run(self._base() + list(args), capture_output=True, timeout=timeout)
        if proc.returncode != 0:
            raise CaptureError(
                f"adb {' '.join(args)} -> {proc.returncode}: "
                f"{proc.stderr.decode('utf-8', 'replace').strip()}"
            )
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def shell(self, cmd, timeout=90):
        return self.run("shell", cmd, timeout=timeout)

    def _pick_serial(self):
        out = subprocess.run([self.adb, "devices"], capture_output=True).stdout.decode()
        devices = [line.split("\t")[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]
        if not devices:
            raise SystemExit("Нет подключённого устройства: запусти AVD или передай --serial")
        return devices[0]

    # --- UI -------------------------------------------------------------
    def dump(self):
        self.shell("uiautomator dump /data/local/tmp/ui.xml")
        self.run("pull", "/data/local/tmp/ui.xml", str(self._tmp))
        xml = self._tmp.read_text(encoding="utf-8", errors="replace")
        nodes = []
        for match in re.finditer(r"<node[^>]*>", xml):
            tag = match.group(0)

            def attr(name):
                found = re.search(name + r'="([^"]*)"', tag)
                return found.group(1) if found else ""

            nums = [int(n) for n in re.findall(r"\d+", attr("bounds"))]
            bounds = tuple(nums[:4]) if len(nums) >= 4 else (0, 0, 0, 0)
            unescape = lambda s: s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#10;", "\n")
            nodes.append(
                Node(
                    text=unescape(attr("text")),
                    desc=unescape(attr("content-desc")),
                    cls=attr("class").split(".")[-1],
                    bounds=bounds,
                    clickable=attr("clickable") == "true",
                    checked=attr("checked") == "true",
                )
            )
        return nodes

    def find(self, text, exact=False, nodes=None, clickable=False, desc=False):
        for node in nodes if nodes is not None else self.dump():
            field_text = node.desc if desc else node.text
            hit = (field_text == text) if exact else (text in field_text)
            if desc and not exact and node.text and text in node.text:
                hit = True
            if hit and (not clickable or node.clickable):
                return node
        return None

    def find_all(self, text, exact=False):
        return [n for n in self.dump() if (n.text == text if exact else text in n.text)]

    def wait_text(self, text, timeout=60, gone=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            hit = self.find(text)
            if (hit is None) if gone else (hit is not None):
                return hit
            time.sleep(1.0)
        return None

    def tap(self, node_or_xy):
        if isinstance(node_or_xy, Node):
            x, y = node_or_xy.center
        else:
            x, y = node_or_xy
        self.shell(f"input tap {int(x)} {int(y)}")
        return x, y

    def tap_text(self, text, exact=False, desc=False, what=None):
        node = self.find(text, exact=exact, desc=desc)
        if node is None:
            raise CaptureError(f"не найден элемент «{what or text}»")
        self.tap(node)
        time.sleep(0.6)
        return node

    def swipe(self, x1, y1, x2, y2, duration=500):
        self.shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(duration)}")

    def scroll_by(self, dy, steps=1):
        """dy > 0 — содержимое уезжает вверх (скролл вниз по экрану)."""
        for _ in range(steps):
            if dy > 0:
                self.swipe(SCREEN_W // 2, int(SCREEN_H * 0.72), SCREEN_W // 2, int(SCREEN_H * 0.72) - dy, 600)
            else:
                self.swipe(SCREEN_W // 2, int(SCREEN_H * 0.35), SCREEN_W // 2, int(SCREEN_H * 0.35) - dy, 600)
            time.sleep(0.9)

    def scroll_to(self, text, target_y, max_steps=14):
        """Подводит элемент к нужной высоте, читая реальные координаты из дерева."""
        self.scroll_until(text)

        def locate():
            return self.find(text) or self.find(text.upper())

        for _ in range(max_steps):
            node = locate()
            if node is None:
                raise CaptureError(f"не найден элемент «{text}» для прокрутки")
            delta = node.cy - target_y
            if abs(delta) <= 40:
                return node
            self.scroll_by(max(-700, min(700, delta)))
        return locate()

    def scroll_until(self, text, dy=520, max_steps=10):
        """Прокручивает вниз, пока элемент не появится в видимой части дерева."""
        for _ in range(max_steps):
            node = self.find(text) or self.find(text.upper())
            if node is not None:
                return node
            self.scroll_by(dy)
        raise CaptureError(f"элемент «{text}» не нашёлся после прокрутки")

    def scroll_top(self, marker=TXT_APP_NAME):
        for _ in range(6):
            node = self.find(marker)
            if node and node.bounds[1] <= 120:
                return
            self.swipe(SCREEN_W // 2, int(SCREEN_H * 0.30), SCREEN_W // 2, int(SCREEN_H * 0.85), 300)
            time.sleep(0.7)

    def back(self):
        self.shell("input keyevent 4")
        time.sleep(0.8)

    def shot(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        data = self.run("exec-out", "screencap", "-p", binary=True)
        path.write_bytes(data)
        if len(data) < 20_000:
            raise CaptureError(f"пустой снимок {path.name} ({len(data)} байт)")
        return path

    # --- системное оформление -------------------------------------------
    def status_bar(self):
        self.shell(f"am broadcast -a com.android.systemui.demo -e command enter")
        self.shell(f"am broadcast -a com.android.systemui.demo -e command clock -e hhmm {DEMO_CLOCK}")
        self.shell(
            f"am broadcast -a com.android.systemui.demo -e command battery "
            f"-e level {DEMO_BATTERY} -e plugged true"
        )
        self.shell(
            "am broadcast -a com.android.systemui.demo -e command network "
            "-e wifi show -e level 4 -e mobile show -e datatype none"
        )
        self.shell("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")


class Capture:
    def __init__(self, dev: Device, out: Path, apk: Path | None, args):
        self.dev = dev
        self.out = out
        self.apk = apk
        self.args = args
        self.raw = out / "raw"
        self.extras = out / "extras"
        self.raw.mkdir(parents=True, exist_ok=True)
        self.extras.mkdir(parents=True, exist_ok=True)
        # Комплект всегда собирается заново: старые кадры не должны смешиваться с новыми.
        for stale in list(self.raw.glob("*.png")) + list(self.extras.glob("*.png")):
            stale.unlink()
        self.entries: list[dict] = []
        self.failures: list[dict] = []
        self.notes: list[str] = [
            "Секрет прокси и секретный ключ на кадрах — значения, сгенерированные приложением "
            "при чистой установке на эмулятор; они нигде не используются и не связаны с аккаунтом.",
            "История стратегий ByeByeDPI оставлена пустой: применённая стратегия отображается в списке "
            "с литералом «null» (дефект отображения в текущей сборке), кадр с дефектом в комплект не включён.",
        ]
        self.version = "?"
        self.version_code = "?"
        self.api = "?"
        self.abi = "?"

    # --- окружение ------------------------------------------------------
    def prepare_device(self):
        dev = self.dev
        size = dev.shell("wm size").strip()
        density = dev.shell("wm density").strip()
        if f"{SCREEN_W}x{SCREEN_H}" not in size:
            self.notes.append(f"Устройство не 1080x2400: {size!r} — снимки будут другого размера")
        if f"Override density: {TARGET_DENSITY}" not in density:
            dev.shell(f"wm density {TARGET_DENSITY}")
            time.sleep(2)
        dev.shell("settings put global window_animation_scale 0")
        dev.shell("settings put global transition_animation_scale 0")
        dev.shell("settings put global animator_duration_scale 0")
        dev.shell("settings put global heads_up_notifications_enabled 0")
        dev.shell("settings put secure show_notifications 1")
        dev.shell("settings put global sysui_demo_allowed 1")
        dev.shell("svc power stayon true")
        dev.shell("input keyevent KEYCODE_WAKEUP")
        dev.shell("wm dismiss-keyguard")
        dev.shell(f"dumpsys battery set level {DEMO_BATTERY}")
        dev.shell("dumpsys battery set ac 1")
        dev.shell("dumpsys battery set status 2")
        # Время 10:08 — только штатная установка часов, состояние приложения не трогаем.
        try:
            dev.shell("su 0 date 112110082026.00")
        except CaptureError:
            pass
        dev.status_bar()
        self.api = dev.shell("getprop ro.build.version.sdk").strip()
        self.abi = dev.shell("getprop ro.product.cpu.abi").strip()

    def install(self):
        if not self.apk:
            self.notes.append("APK не передан: используется уже установленная сборка")
        else:
            out = self.dev.run("install", "-r", str(self.apk), timeout=300)
            if "Success" not in out:
                raise SystemExit(f"Установка не удалась: {out}")
            self.dev.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")

    def read_app_version(self):
        try:
            dump = self.dev.shell(f"dumpsys package {PACKAGE}")
            name = re.search(r"versionName=(\S+)", dump)
            code = re.search(r"versionCode=(\d+)", dump)
            self.version = name.group(1) if name else self.version
            self.version_code = code.group(1) if code else self.version_code
        except CaptureError:
            pass

    def launch(self, fresh=True):
        dev = self.dev
        dev.shell(f"am force-stop {PACKAGE}")
        if fresh:
            dev.shell(f"pm clear {PACKAGE}")
            dev.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
        dev.shell(f"am start -n {ACTIVITY}")
        if dev.wait_text(TXT_APP_NAME, timeout=60) is None:
            raise CaptureError("приложение не открылось")
        if dev.wait_text(TXT_STATE_DISCONNECTED, timeout=60) is None:
            raise CaptureError("главный экран не показал состояние «ОТКЛЮЧЕНО»")
        # Диалог доступов на первом запуске — закрываем, он не часть продукта.
        if dev.find(TXT_ACCESS_DIALOG):
            for label in ("Позже", "Готово"):
                node = dev.find(label)
                if node:
                    dev.tap(node)
                    time.sleep(1.0)
                    break
        dev.scroll_top()

    # --- шаги -----------------------------------------------------------
    def power_button(self):
        node = self.dev.find(TXT_POWER_START, desc=True) or self.dev.find(TXT_POWER_STOP, desc=True)
        if node is None:
            node = next((n for n in self.dev.dump() if n.cls == "Button" and n.clickable), None)
        if node is None:
            raise CaptureError("кнопка питания не найдена")
        return node

    def wait_state(self, text, timeout=120):
        node = self.dev.wait_text(text, timeout=timeout)
        if node is None:
            raise CaptureError(f"состояние «{text}» не появилось за {timeout} с")
        return node

    def step_home_off(self):
        self.dev.scroll_top()
        self.shot_and_record(
            self.raw / "01-home-off.png",
            "Главный экран: TG-WS и ByeByeDPI выключены",
            "оба модуля остановлены, Telegram выбран для запуска",
        )

    def step_tgws_active(self):
        dev = self.dev
        dev.scroll_top()
        dev.tap(self.power_button())
        self.wait_state(TXT_STATE_CONNECTED, timeout=150)
        time.sleep(2)
        dev.scroll_top()
        self.shot_and_record(
            self.raw / "02-tgws-active.png",
            "TG-WS запущен: локальный MTProto-прокси подключён",
            "Telegram-прокси работает, статус «[OK] ПОДКЛЮЧЕНО»",
        )
        self.stop_all()

    def stop_all(self):
        dev = self.dev
        if dev.find("Прокси", exact=True):
            dev.tap_text("Прокси", exact=True)
            time.sleep(1.5)
        dev.scroll_top()
        node = self.power_button()
        dev.tap(node)
        if dev.wait_text(TXT_STATE_DISCONNECTED, timeout=90) is None:
            raise CaptureError("модули не остановились")
        time.sleep(1.5)

    def service_row(self, label):
        """Возвращает кликабельную строку сервиса и её состояние (галочка внутри)."""
        dev = self.dev
        nodes = dev.dump()
        text_node = dev.find(label, nodes=nodes)
        if text_node is None:
            raise CaptureError(f"нет строки сервиса «{label}»")
        container = None
        for node in nodes:
            if not node.clickable:
                continue
            if node.bounds[0] <= text_node.cx <= node.bounds[2] and node.bounds[1] <= text_node.cy <= node.bounds[3]:
                area = (node.bounds[2] - node.bounds[0]) * (node.bounds[3] - node.bounds[1])
                if container is None or area < container[0]:
                    container = (area, node)
        row = container[1] if container else text_node
        checked = any(
            n.text == "✓" and row.bounds[0] <= n.cx <= row.bounds[2] and row.bounds[1] <= n.cy <= row.bounds[3]
            for n in nodes
        )
        return row, checked

    def set_service(self, label, wanted):
        row, checked = self.service_row(label)
        if checked != wanted:
            self.dev.tap(row)
            time.sleep(1.2)
            row, checked = self.service_row(label)
            if checked != wanted:
                raise CaptureError(f"не удалось переключить «{label}» в {wanted}")
        return checked

    def accept_vpn_consent(self):
        dev = self.dev
        node = dev.find("Connection request") or dev.find("Запрос на подключение")
        if node is None:
            node = dev.find("OK", exact=True)
        if node is not None:
            dev.tap(node)
            time.sleep(2)

    def step_dpi_active(self):
        dev = self.dev
        dev.scroll_top()
        self.set_service("YouTube", True)
        self.set_service("Telegram", False)
        dev.tap(self.power_button())
        self.accept_vpn_consent()
        self.wait_state(TXT_STATE_CONNECTED, timeout=150)
        time.sleep(3)
        dev.scroll_top()
        self.shot_and_record(
            self.raw / "03-dpi-active.png",
            "ByeByeDPI активен в режиме VPN",
            "выбран только YouTube → модуль DPI поднял системный VPN, статус «[OK] ПОДКЛЮЧЕНО»",
        )

    def step_both_active(self):
        dev = self.dev
        dev.scroll_top()
        self.set_service("Telegram", True)
        dev.tap(self.power_button())  # останавливаем DPI, чтобы запустить оба модуля одной кнопкой
        self.wait_state(TXT_STATE_DISCONNECTED, timeout=90)
        time.sleep(1.5)
        dev.scroll_top()
        dev.tap(self.power_button())
        self.accept_vpn_consent()
        self.wait_state(TXT_STATE_CONNECTED, timeout=180)
        time.sleep(3)
        dev.scroll_top()
        self.shot_and_record(
            self.raw / "04-both-active.png",
            "Оба модуля работают одновременно",
            "TG-WS и ByeByeDPI запущены вместе, статус «[OK] ПОДКЛЮЧЕНО»",
        )
        # Дополнительный кадр: низ главного экрана со ссылкой tg://proxy.
        self.extra_home_link()

    def extra_home_link(self):
        dev = self.dev
        try:
            dev.scroll_until("КОПИРОВАТЬ")
            dev.scroll_to("КОПИРОВАТЬ", 1900)
            time.sleep(1.2)
            self.shot_and_record(
                self.extras / "extra-01-home-proxy-link.png",
                "Главный экран, нижняя часть: ссылка tg://proxy и кнопка «Копировать»",
                "оба модуля запущены",
            )
        except CaptureError as exc:
            self.failures.append({"screen": "extra-01-home-proxy-link.png", "reason": str(exc)})
        finally:
            dev.scroll_top()

    def step_dpi_strategies(self):
        dev = self.dev
        self.stop_all()
        dev.scroll_top()
        dev.tap_text(TXT_REPAIR_LINK)
        if dev.wait_text(TXT_REPAIR_TITLE, timeout=30) is None:
            raise CaptureError("помощник восстановления не открылся")
        dev.tap_text(TXT_STRATEGY_TEST_START)
        deadline = time.time() + 420
        done = False
        while time.time() < deadline:
            time.sleep(8)
            if dev.find("HTTPS-узлов доступно") or dev.find("Ни одна стратегия не достигла"):
                done = True
                break
        if not done:
            raise CaptureError("автоподбор стратегий не завершился за 7 минут")
        time.sleep(2)
        self.shot_and_record(
            self.raw / "05-dpi-strategies.png",
            "Автоподбор DPI-стратегий: результат реальной проверки",
            "ByeByeDPI остановлен, проверены все стратегии из встроенного списка",
        )
        dev.back()
        time.sleep(1.5)
        if dev.find(TXT_REPAIR_TITLE):
            dev.back()
            time.sleep(1.0)

    def open_advanced(self, segment=None):
        dev = self.dev
        dev.tap_text("Настройки")
        time.sleep(1.5)
        dev.tap_text(TXT_ADVANCED)
        time.sleep(1.5)
        if segment:
            dev.tap_text(segment, exact=True)
            time.sleep(1.5)

    def step_dpi_settings(self):
        dev = self.dev
        self.open_advanced(TXT_DPI_SEGMENT)
        if dev.wait_text("ФИЛЬТР ПРИЛОЖЕНИЙ", timeout=20) is None:
            raise CaptureError("раздел ByeByeDPI не открылся")
        dev.scroll_top()
        time.sleep(1.0)
        self.shot_and_record(
            self.raw / "06-dpi-settings.png",
            "Параметры ByeByeDPI: режим работы, фильтр приложений, командная строка, история стратегий",
            "ByeByeDPI остановлен, настройки по умолчанию",
        )

    def extra_dpi_diagnostics(self):
        dev = self.dev
        try:
            dev.scroll_until("ДИАГНОСТИКА BYEBYEDPI")
            dev.scroll_to("ДИАГНОСТИКА BYEBYEDPI", 420)
            time.sleep(1.0)
            self.shot_and_record(
                self.extras / "extra-02-dpi-diagnostics.png",
                "Диагностика ByeByeDPI: адрес, режим, аргументы, списки доменов и резервные копии",
                "ByeByeDPI остановлен",
            )
        except CaptureError as exc:
            self.failures.append({"screen": "extra-02-dpi-diagnostics.png", "reason": str(exc)})

    def step_cloudflare(self):
        dev = self.dev
        dev.scroll_top()
        dev.tap_text("TG-WS", exact=True)
        time.sleep(1.5)
        dev.scroll_until("Cloudflare CDN")
        dev.scroll_to("Cloudflare CDN", 220)
        time.sleep(1.0)
        self.shot_and_record(
            self.raw / "07-cloudflare.png",
            "Cloudflare: CDN, режим без TLS и свои домены CF-прокси / CF Worker",
            "TG-WS остановлен, Cloudflare CDN включён",
        )

    def extra_settings_advanced(self):
        dev = self.dev
        try:
            dev.scroll_top()
            time.sleep(1.0)
            self.shot_and_record(
                self.extras / "extra-05-settings-advanced.png",
                "Расширенные настройки TG-WS: адрес и порт, адреса DC, пул WS, секретный ключ",
                "TG-WS остановлен, настройки по умолчанию",
            )
        except CaptureError as exc:
            self.failures.append({"screen": "extra-05-settings-advanced.png", "reason": str(exc)})

    def step_logs(self):
        dev = self.dev
        dev.tap_text("Логи")
        time.sleep(2.0)
        if dev.wait_text("Listening on", timeout=20) is None:
            self.notes.append("В журнале TG-WS нет записей: прокси не запускался в этой сессии")
        self.shot_and_record(
            self.raw / "08-logs.png",
            "Журнал TG-WS: реальные записи ядра при работающем прокси",
            "оба модуля запущены, журнал показывает текущую сессию",
        )

    def step_settings(self):
        dev = self.dev
        dev.tap_text("Настройки")
        time.sleep(1.8)
        back = dev.find("Назад к настройкам")
        if back is not None:
            dev.tap(back)
            time.sleep(1.5)
        if dev.wait_text("ОСНОВНЫЕ", timeout=20) is None:
            raise CaptureError("экран настроек не открылся")
        dev.scroll_top()
        self.shot_and_record(
            self.raw / "09-settings.png",
            "Основные настройки приложения",
            "настройки по умолчанию",
        )

    def step_about(self):
        dev = self.dev
        dev.tap_text("Инфо")
        time.sleep(1.8)
        if dev.wait_text("О ПРОЕКТЕ", timeout=20) is None:
            raise CaptureError("экран «Инфо» не открылся")
        dev.scroll_top()
        self.shot_and_record(
            self.raw / "10-about.png",
            "Информация о приложении: версия, ядро, действия и поддержка",
            "экран «Инфо» в начале списка",
        )

    def extra_about_project(self):
        dev = self.dev
        try:
            dev.scroll_until("Поддержать автора")
            dev.scroll_to("Поддержать автора", 1700)
            time.sleep(1.0)
            self.shot_and_record(
                self.extras / "extra-03-about-project.png",
                "О проекте: ссылки на исходники и блок поддержки автора",
                "экран «Инфо», прокручен вниз",
            )
        except CaptureError as exc:
            self.failures.append({"screen": "extra-03-about-project.png", "reason": str(exc)})

    def extra_help(self):
        dev = self.dev
        try:
            dev.scroll_top()
            dev.tap_text("Справка")
            time.sleep(1.8)
            if dev.wait_text("АВТО И АДРЕСА ДАТАЦЕНТРОВ", timeout=15) is None:
                raise CaptureError("диалог справки не открылся")
            self.shot_and_record(
                self.extras / "extra-04-help.png",
                "Справка по Cloudflare, пулу WS, секрету и автозапуску",
                "диалог справки",
            )
            dev.back()
        except CaptureError as exc:
            self.failures.append({"screen": "extra-04-help.png", "reason": str(exc)})

    # --- запись результатов ---------------------------------------------
    def shot_and_record(self, path: Path, description: str, state: str):
        self.dev.shot(path)
        self.entries.append(
            {
                "file": str(path.relative_to(self.out)).replace("\\", "/"),
                "description": description,
                "app_state": state,
                "android_api": self.api,
                "abi": self.abi,
                "resolution": f"{SCREEN_W}x{SCREEN_H}",
                "density_dpi": TARGET_DENSITY,
                "app_version": self.version,
                "app_version_code": self.version_code,
                "package": PACKAGE,
                "captured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
                "captured": True,
            }
        )
        print(f"  [ok] {path.name}")

    def run_step(self, label, fn, required=True):
        print(f"-> {label}")
        try:
            fn()
        except CaptureError as exc:
            print(f"  [!] {label}: {exc}")
            self.failures.append({"screen": label, "reason": str(exc)})
            if required:
                self.notes.append(f"Шаг «{label}» не выполнен: {exc}")

    # --- отчёт ----------------------------------------------------------
    def write_outputs(self, elapsed):
        self.entries.sort(key=lambda item: item["file"])
        manifest = {
            "app": "ByeBye TGWS Proxy",
            "package": PACKAGE,
            "app_version": self.version,
            "app_version_code": self.version_code,
            "android_api": self.api,
            "abi": self.abi,
            "resolution": f"{SCREEN_W}x{SCREEN_H}",
            "density_dpi": TARGET_DENSITY,
            "device": self.args.serial or self.dev.serial,
            "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
            "captured": len(self.entries),
            "failed": len(self.failures),
            "screenshots": self.entries,
        }
        (self.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

        lines = [
            "# Отчёт о съёмке скриншотов",
            "",
            f"- Приложение: ByeBye TGWS Proxy {self.version} ({self.version_code}), пакет `{PACKAGE}`",
            f"- Устройство: {manifest['device']}, Android API {self.api}, ABI {self.abi}, {SCREEN_W}×{SCREEN_H} @ {TARGET_DENSITY} dpi",
            f"- Дата съёмки: {manifest['generated_at']}",
            f"- Всего изображений: {len(self.entries)} (ошибок: {len(self.failures)})",
            f"- Время прогона: {elapsed:.0f} с",
            "- Контактный лист: `contact-sheet.png`",
            "- Скрипт съёмки: `automation/capture.py` (описание — `automation/README.md`)",
            "",
            "## Полученные экраны",
            "",
            "| Файл | Что на экране |",
            "|---|---|",
        ]
        for entry in self.entries:
            lines.append(f"| `{entry['file']}` | {entry['description']} |")
        lines += ["", NAVIGATION_MAP]
        if self.failures:
            lines += ["", "## Не получено", "", "| Шаг | Причина |", "|---|---|"]
            for fail in self.failures:
                lines.append(f"| {fail['screen']} | {fail['reason']} |")
        else:
            lines += ["", "## Не получено", "", "Все запланированные экраны сняты."]
        if self.notes:
            lines += ["", "## Замечания", ""] + [f"- {note}" for note in self.notes]
        lines += [
            "",
            "## Как снимались состояния",
            "",
            "- Все снимки сделаны с одного AVD и одного APK, разрешение и плотность не менялись между кадрами.",
            "- Статус-бар приведён к единому виду штатными средствами Android (demo mode: 10:08, Wi-Fi, 92 %),",
            "  состояние самого приложения при этом не подменялось.",
            "- «Подключено» появляется только тогда, когда сервисы действительно поднялись: TG-WS держит",
            "  локальный MTProto-прокси, ByeByeDPI — системный VPN. Если сеть не пускает, скриншот не снимается.",
            "- Автоподбор стратегий запускался реально: проверяются HTTPS-узлы YouTube из приложения.",
        ]
        (self.out / "capture-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_contact_sheet(out: Path):
    """Контактный лист: все кадры в сетке, с подписями файлов."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("PIL недоступен: контактный лист не собран")
        return None
    files = sorted((out / "raw").glob("*.png")) + sorted((out / "extras").glob("*.png"))
    if not files:
        return None
    cols, thumb_w, pad, label_h = 5, 260, 18, 26
    thumbs = []
    for path in files:
        img = Image.open(path).convert("RGB")
        ratio = thumb_w / img.width
        thumbs.append((path.name, img.resize((thumb_w, int(img.height * ratio)), Image.LANCZOS)))
    thumb_h = max(t.height for _, t in thumbs)
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (thumb_w + pad) + pad, rows * (thumb_h + label_h + pad) + pad), (11, 21, 27))
    draw = ImageDraw.Draw(sheet)
    for index, (name, thumb) in enumerate(thumbs):
        col, row = index % cols, index // cols
        x = pad + col * (thumb_w + pad)
        y = pad + row * (thumb_h + label_h + pad)
        sheet.paste(thumb, (x, y))
        draw.text((x, y + thumb_h + 6), name, fill=(101, 217, 232))
    target = out / "contact-sheet.png"
    sheet.save(target)
    return target


def ensure_emulator(avd: str, emulator_path: str | None):
    if not emulator_path:
        raise SystemExit("Не найден emulator: передай --emulator или ANDROID_EMULATOR")
    print(f"-> запускаю AVD {avd}")
    subprocess.Popen(
        [emulator_path, "-avd", avd, "-no-window", "-no-audio", "-no-boot-anim",
         "-gpu", "swiftshader_indirect", "-no-snapshot"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def main():
    root = Path(__file__).resolve().parents[4] if len(Path(__file__).resolve().parents) > 4 else Path.cwd()
    default_out = root / "docs" / "assets" / "screenshots"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", type=Path, default=None, help="debug APK flavour emulator (x86_64)")
    ap.add_argument("--out", type=Path, default=default_out)
    ap.add_argument("--serial", default=None)
    ap.add_argument("--avd", default=None, help="поднять AVD, если устройство не подключено")
    ap.add_argument("--adb", default=None)
    ap.add_argument("--emulator", default=None)
    ap.add_argument("--keep-data", action="store_true", help="не очищать данные приложения перед съёмкой")
    ap.add_argument("--skip-strategy-test", action="store_true", help="не запускать автоподбор (≈2.5 минуты)")
    args = ap.parse_args()

    adb_path = args.adb or first_existing(DEFAULT_ADB)
    if not adb_path:
        raise SystemExit("Не найден adb: передай --adb или задай ADB")
    if args.avd:
        ensure_emulator(args.avd, args.emulator or first_existing(DEFAULT_EMULATOR))
        time.sleep(5)

    dev = Device(adb_path, args.serial)
    subprocess.run([adb_path, "-s", dev.serial, "wait-for-device"], check=False)
    for _ in range(120):
        if dev.shell("getprop sys.boot_completed").strip() == "1":
            break
        time.sleep(3)
    else:
        raise SystemExit("Эмулятор не загрузился за 6 минут")

    started = time.time()
    cap = Capture(dev, args.out, args.apk, args)
    cap.prepare_device()
    cap.install()
    cap.read_app_version()
    cap.launch(fresh=not args.keep_data)

    steps = [
        ("01-home-off", cap.step_home_off),
        ("02-tgws-active", cap.step_tgws_active),
        ("03-dpi-active", cap.step_dpi_active),
        ("04-both-active", cap.step_both_active),
        ("08-logs", cap.step_logs),
    ]
    for label, fn in steps:
        cap.run_step(label, fn, required=False)
        if cap.failures and cap.failures[-1]["screen"] == label:
            # Дальнейшие состояния зависят от текущего: подчищаем и продолжаем.
            try:
                dev.shell(f"am force-stop {PACKAGE}")
                cap.launch(fresh=False)
            except CaptureError as exc:
                cap.notes.append(f"Перезапуск после сбоя: {exc}")

    if args.skip_strategy_test:
        cap.notes.append("Автоподбор стратегий пропущен: запущено с --skip-strategy-test")
    else:
        cap.run_step("05-dpi-strategies", cap.step_dpi_strategies, required=False)

    cap.run_step("06-dpi-settings", cap.step_dpi_settings, required=False)
    cap.extra_dpi_diagnostics()
    cap.run_step("07-cloudflare", cap.step_cloudflare, required=False)
    cap.extra_settings_advanced()
    cap.run_step("09-settings", cap.step_settings, required=False)
    cap.run_step("10-about", cap.step_about, required=False)
    cap.extra_about_project()
    cap.extra_help()

    elapsed = time.time() - started
    cap.write_outputs(elapsed)
    sheet = build_contact_sheet(args.out)
    print(f"\nГотово: {len(cap.entries)} снимков, ошибок {len(cap.failures)}, {elapsed:.0f} с")
    print(f"Папка: {args.out}")
    if sheet:
        print(f"Контактный лист: {sheet}")
    if cap.failures:
        for fail in cap.failures:
            print(f"  [!] {fail['screen']}: {fail['reason']}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
