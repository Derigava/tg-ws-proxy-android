# Графика для GitHub — ByeBye TGWS Proxy

Исходники трёх растровых материалов: два hero-баннера (README) и social preview
(карточка репозитория). Всё свёрстано в HTML/CSS, рендерится headless Chrome,
шрифты и логотипы лежат рядом и подключены относительными путями.

## Файлы

| Исходник | Результат | Размер |
|---|---|---|
| `hero-dark.html` | `../banner-dark.png` | 1600 × 560 |
| `hero-light.html` | `../banner-light.png` | 1600 × 560 |
| `social.html` | `../social-preview.png` | 1280 × 640 |

Общие стили — `style.css`. Ничего не минифицировано: правки делаются прямо в HTML/CSS.

## Перерендер

Windows, git-bash, Chrome. Размер окна равен артборду, `--force-device-scale-factor=1`
обязателен, иначе PNG выйдет в 2×. Запускать из любой папки, но пути в атрибутах
`--screenshot` и `file:///` — абсолютные.

```bash
CH="C:/Program Files/Google/Chrome/Application/chrome.exe"
ROOT="G:/Projects/tg-ws-proxy-android"

"$CH" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=4000 \
  --screenshot="$ROOT/docs/assets/banner-dark.png" --window-size=1600,560 \
  "file:///$ROOT/docs/assets/design/hero-dark.html"

"$CH" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=4000 \
  --screenshot="$ROOT/docs/assets/banner-light.png" --window-size=1600,560 \
  "file:///$ROOT/docs/assets/design/hero-light.html"

"$CH" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --virtual-time-budget=4000 \
  --screenshot="$ROOT/docs/assets/social-preview.png" --window-size=1280,640 \
  "file:///$ROOT/docs/assets/design/social.html"
```

`--virtual-time-budget` ждёт загрузку локальных шрифтов и картинок; без него возможен
снимок с фолбэком. Сжатие после рендера:

```bash
python -c "from PIL import Image; [Image.open(p).save(p, optimize=True) for p in ['docs/assets/banner-dark.png','docs/assets/banner-light.png','docs/assets/social-preview.png']]"
```

Если установлены `pngquant`/`optipng`, они дают меньший вес, но PNG и так < 80 КБ.

## Шрифты и логотипы

- **Unbounded** 600 — название. **Onest** 400 — подзаголовок и описания модулей.
  **JetBrains Mono** 400/500 — лейблы, статусы, факты, версия.
- Файлы: `assets/fonts/`, лицензии OFL рядом, сводка — `assets/fonts/fonts.txt`.
- Логотипы: `assets/logos/` — `mark-dark-color.svg`, `mark-light-color.svg`,
  `wordmark-dark-color.svg`, `wordmark-light-color.svg`. Знак не перерисовывается и
  не растягивается: пропорции задаются только шириной при сохранении viewBox.
- Иконки `assets/icons/` (24 px, currentColor) скопированы для правок; в текущих
  макетах не использованы — композиция держится на линиях маршрутизации.
- Палитра: accent `#65D9E8`, deep `#086578`, bg `#0B151B`, paper `#EDF5F7`,
  muted `#A4B7C0`, текст на тёмном `#F4F3EB`.

## Композиция

- **Тёмная**: горизонтальная иерархия. Название в одну строку, под ним линия-разделитель
  с меткой `MODULES 02 / INDEPENDENT`, ниже два равных модуля TG-WS и ByeByeDPI со
  статусами `[OK]`; скобка под ними и подпись «одно приложение · два независимых модуля».
  Справа вверху — wordmark Derigava в подчинённой роли.
- **Светлая**: другая композиция, не инверсия. Название в две строки слева, модули —
  вертикальные полосы справа с залитыми deep-чипами `01`/`02` и общей шиной-рельсом.
  Акцентный приём — плотная заливка `#086578`, а не линии.
- **Social**: самостоятельная карточка 1280 × 640, название 96 px в две строки,
  позиционирование одной строкой 32 px, два модуля-узла справа. Мелкого текста нет:
  минимум 16 px в макете.

## Ограничения

- Артборды фиксированного размера: текст не переносится, при смене формулировок нужно
  проверять ширину и перерендеривать.
- Это растр для экрана; для печати и для SVG-версий макеты не предназначены.
- Шрифты не встроены в PNG (это невозможно) и не сабсетнуты — HTML зависит от папки
  `assets/fonts/`.
- `social-preview.png` рассчитан на рекомендацию GitHub 1280 × 640; при загрузке в
  настройках репозитория GitHub может кадрировать края под 2:1 в некоторых местах.
- Модули описаны по фактическому поведению 1.2.0: TG-WS — локальный MTProto-прокси на
  `127.0.0.1:1443` через Cloudflare CDN; ByeByeDPI — DPI-bypass без root, системный
  VPN-режим или локальный SOCKS5. Формулировки «VPN для анонимности», «шифрование»,
  «скрытие IP» и «официальный клиент Telegram» не используются.