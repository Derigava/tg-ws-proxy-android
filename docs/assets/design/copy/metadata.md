# Метаданные GitHub

Проверено по живому репозиторию и API 22.11.2026: `description`, `homepage`, `topics`, наличие релиза `v1.2.0`.

## Описание репозитория

Текущее: «Local MTProto proxy for Telegram on Android (Flowseal core). No root or VPN. Локальный прокси Telegram для Android.» — описывает половину приложения и содержит неверное «No VPN» (см. `audit.md`, п. 1).

Предлагаемое (252 символа, лимит 350):

```text
One Android app, two modules: a local MTProto proxy for Telegram (Flowseal core, Cloudflare and Worker routes, tg:// link) and ByeByeDPI as a device-wide VPN or a local SOCKS5 proxy. No root. Android 7+, ARM64. Unofficial, not affiliated with Telegram.
```

Почему так:

- первые 70 символов дают ответ «что это» в выдаче и в мобильной карточке;
- названы оба модуля и оба режима DPI — то, чего нет сейчас;
- «No root» оставлено, «No VPN» убрано: VPN есть, просто он не обязателен для TG-WS;
- в конце юридическая оговорка, которая уже есть в приложении.

Альтернатива, если нужен акцент на функции, а не на архитектуре (270 символов):

```text
Local MTProto proxy for Telegram plus a DPI bypass in one Android app. TG-WS: Flowseal core, Cloudflare and Worker routes, tg:// link. ByeByeDPI: device-wide VPN or local SOCKS5 127.0.0.1:1080. No root, Android 7+, ARM64, GPLv3. Unofficial, not affiliated with Telegram.
```

Не добавлять: «fast», «secure», «bypass any blocking», «anonymous», «unlimited». Ни одно из этого не подтверждается кодом и противоречит разделу о прозрачности.

## Topics

Сейчас в репозитории 13 тем: `android, android-app, chaquopy, jetpack-compose, kotlin, mtproto, mtproto-proxy, proxy, telegram, telegram-proxy, tg-ws-proxy, tg-ws-proxy-android, websocket`. Половина описывает только TG-WS; по запросам `dpi-bypass`, `byebyedpi`, `socks5` приложение не находится.

Предлагаемый набор — 14 тем (лимит GitHub — 20, тема ≤50 символов, только строчные буквы и дефисы):

| Тема | Оценка | Обоснование |
|---|---|---|
| `android` | оставить, высокая | Платформа; основной поисковый запрос. |
| `android-app` | оставить, высокая | Уточняет формат: готовое приложение, а не библиотека. |
| `kotlin` | оставить, высокая | Приложение на Kotlin (`app/build.gradle.kts`, `ui/*.kt`). |
| `jetpack-compose` | оставить, средняя | Весь интерфейс на Compose (`buildFeatures { compose = true }`). |
| `telegram` | оставить, высокая | Основной сценарий использования. |
| `mtproto` | оставить, высокая | Протокол локального прокси; точнее, чем `mtproto-proxy`. |
| `proxy` | оставить, высокая | Общий, но релевантный запрос. |
| `dpi-bypass` | **добавить**, высокая | Второй модуль — ровно эта задача; сейчас темы нет. |
| `byebyedpi` | **добавить**, высокая | Имя апстрима, по которому ищут пользователи оригинала. |
| `socks5` | **добавить**, средняя | Второй режим ByeByeDPI (`127.0.0.1:1080`). |
| `cloudflare` | **добавить**, средняя | CF CDN и свои Worker — заметная часть TG-WS. |
| `chaquopy` | оставить, средняя | Python-ядро внутри APK; отличает проект от Java-обёрток. |
| `tasker` | **добавить**, низкая-средняя | Есть документированные интенты и ярлыки; узкая, но живая аудитория. |
| `websocket` | оставить, средняя | Транспорт прямого маршрута к DC и CF-маршрута. |

Убрать: `mtproto-proxy` (дубль `mtproto`), `telegram-proxy` (семантический дубль `telegram` + `proxy`), `tg-ws-proxy` и `tg-ws-proxy-android` (имя проекта и так в названии репозитория; темы не добавляют поискового сигнала, но занимают слоты).

Оценка остальных кандидатов из задания:

| Кандидат | Решение | Почему |
|---|---|---|
| `vpn` | **не добавлять** | Приложение не VPN-сервис: VPN — локальный `VpnService` для обхода DPI, без удалённого сервера, шифрования и смены IP. Тема привлечёт аудиторию, которой продукт не обещает то, что она ищет. |
| `telegram-proxy` | убрать | Дубль; хуже ранжируется, чем `telegram` + `proxy`. |
| `android-app` | оставить | См. таблицу выше. |
| `tasker` | добавить с оговоркой | Уместна, пока в README есть раздел про интенты. Если раздел убрать, тему тоже убрать. |

Порядок применения: About (шестерёнка) → Topics → вписать по одной → Save changes. GitHub сам приводит к нижнему регистру.

## Бейджи

Четыре обязательных — те же, что в шапке README. Все URL проверены: HTTP 200.

| Назначение | URL | Что показывает |
|---|---|---|
| Последний релиз | `https://img.shields.io/github/v/release/Derigava/tg-ws-proxy-android?style=flat-square&label=%D1%80%D0%B5%D0%BB%D0%B8%D0%B7&color=65D9E8&labelColor=0B151B` | Тег последнего релиза; данные GitHub, не ручные. |
| CI | `https://img.shields.io/github/actions/workflow/status/Derigava/tg-ws-proxy-android/android-release.yml?branch=main&style=flat-square&label=CI&labelColor=0B151B` | Статус workflow `android-release.yml` на `main`. |
| Лицензия | `https://img.shields.io/badge/license-GPLv3-65D9E8?style=flat-square&labelColor=0B151B` | Статический бейдж: `LICENSE` — GPLv3, API GitHub отдаёт `GPL-3.0`. |
| Платформа | `https://img.shields.io/badge/Android-7.0%2B%20ARM64-65D9E8?style=flat-square&labelColor=0B151B` | Статический: `minSdk 24`, релизный flavour `arm64`. |

Необязательные, если понадобится усилить шапку:

| Назначение | URL | Оговорка |
|---|---|---|
| Скачивания | `https://img.shields.io/github/downloads/Derigava/tg-ws-proxy-android/total?style=flat-square&labelColor=0B151B` | Реальная метрика GitHub, не выдумка. Но ноль на старте выглядит хуже отсутствия бейджа — включать только при заметном числе. |
| Последний коммит | `https://img.shields.io/github/last-commit/Derigava/tg-ws-proxy-android?style=flat-square&labelColor=0B151B` | Показывает живость проекта; безвредно. |

Чего не добавлять: «stars», «forks», «contributors», «code size», «tests», «coverage», «Docker pulls». Первые три дублируют интерфейс GitHub, остальные либо не измеряются в этом репозитории, либо дадут ложный сигнал (покрытия нет, Docker-образа нет).

Цвета: акцент `65D9E8`, подложка `0B151B` — как в баннере и интерфейсе. `style=flat-square` уже используется, менять не нужно.

## Homepage

Сейчас: `https://github.com/Derigava/tg-ws-proxy-android/releases/latest`.

Проверка: ссылка жива — 302 на `https://github.com/Derigava/tg-ws-proxy-android/releases/tag/v1.2.0`, финальный ответ 200. Релиз `v1.2.0` опубликован, APK на месте.

Решение: **оставить**. Для приложения, которое распространяется APK, домашняя страница логично ведёт на скачивание, а не на описание, которое и так под ссылкой. Два риска:

- если релизов не останется, `/releases/latest` отдаст 404 — перед сменой линейки версий убедись, что предыдущий релиз не удалён;
- поле не объясняет, что внутри; описание в About закрывает это.

Альтернатива, если хочется вести на документацию: `https://github.com/Derigava/tg-ws-proxy-android#readme`. Тогда скачивание становится на один клик дальше — для этого продукта это хуже.

## Social Preview

Файл: `docs/assets/social-preview.png`, 1280×640, 54 КБ — соответствует рекомендации GitHub (1280×640, до 1 МБ). Хранится в репозитории, но GitHub не берёт его оттуда автоматически.

Как включить:

1. Открыть репозиторий → **Settings**.
2. Блок **Social preview** → **Edit** → **Upload an image…**
3. Выбрать `docs/assets/social-preview.png` (локальная копия из репозитория).
4. **Save**. Превью обновится не мгновенно: GitHub кеширует картинку, в соцсетях старое превью может жить до нескольких суток.

Проверка после загрузки: открыть `https://github.com/Derigava/tg-ws-proxy-android` в режиме инкогнито и посмотреть превью в Settings; для внешних сервисов — любой OG-валидатор по URL репозитория. Пока файл не загружен, в соцсетях показывается аватар и описание, а не баннер.

Что должно быть на превью (проверить перед загрузкой): название **ByeBye TGWS Proxy**, формулировка про два модуля, тёмный фон `0B151B`, акцент `65D9E8`. Текст мельче 24 px в превью не читается — на 1280×640 это примерно 3–4 строки максимум.