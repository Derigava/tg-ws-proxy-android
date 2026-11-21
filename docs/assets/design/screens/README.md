# Презентационные экраны

Исходники композиций для витрины приложения. Собираются из реальных снимков
`docs/assets/screenshots/`, ничего не дорисовывается: только рамка устройства,
подписи и фон.

## Состав

| Файл | Источник | Блок под подписью |
|---|---|---|
| `01-two-modules.html` | `raw/01-home-off.png` | схема TG-WS / ByeByeDPI → приложения |
| `02-tgws-live.html` | `raw/02-tgws-active.png` + кроп `extras/extra-01` | строка `tg://proxy` |
| `03-dpi-vpn.html` | `raw/03-dpi-active.png` | таблица режима |
| `04-both-live.html` | `raw/04-both-active.png` | статусы обоих модулей |
| `05-strategy-test.html` | `raw/05-dpi-strategies.png` | 61/61 и 2/3 |
| `06-cloudflare.html` | `raw/07-cloudflare.png` | таблица CF |
| `07-logs.html` | `raw/08-logs.png` | три строки журнала |
| `08-dpi-settings.html` | `raw/06-dpi-settings.png` | таблица настроек |

## Сборка

```sh
python docs/assets/design/screens/render.py            # все
python docs/assets/design/screens/render.py 05 06      # выборочно
```

Chrome headless рендерит 1600×1100 при `device-scale-factor=1`, скрипт проверяет
ширину, высоту и вес каждого PNG. Результат — `docs/assets/screens/*.png`.

## Общие правила

- Сетка: поля 80 px, колонка текста 880 px, телефон 386×826, шапка 42 px,
  нижний блок прижат к базовой линии 960 px.
- Шрифты: Unbounded 600 — заголовок 56 px, Onest 400 — пояснение 26 px,
  JetBrains Mono — лейблы и значения. Файлы в `../assets/fonts`.
- Цвета: `#0B151B` фон, `#EDF5F7` текст, `#A4B7C0` вторичный, `#65D9E8` акцент.
- Снимок вставляется как 360×800 — это ровно 1080×2400, без растяжения.