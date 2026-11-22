# Презентационные экраны

Карточки собраны из фрагментов снимков приложения. Подписи, фон и рамки
редактируются в HTML и `../styles/screens.css`. Фрагменты PNG лежат в
`../assets/crops/`, исходные снимки — в `../../screenshots/`.

| Карточка | Снимки |
|---|---|
| 01 · Два модуля | `raw/01-home-off.png`, `raw/06-dpi-settings.png` |
| 02 · TG-WS | `raw/02-tgws-active.png`, `extras/extra-01-home-proxy-link.png` |
| 03 · VPN и SOCKS5 | `raw/06-dpi-settings.png`, `extras/extra-02-dpi-diagnostics.png` |
| 04 · Два модуля в работе | `raw/04-both-active.png` |
| 05 · Автоподбор | `raw/05-dpi-strategies.png` |
| 06 · Cloudflare | `raw/07-cloudflare.png` |
| 07 · Логи | `raw/08-logs.png` |
| 08 · Настройки ByeByeDPI | `raw/06-dpi-settings.png` |

```sh
python docs/assets/design/screens/render.py
python docs/assets/design/screens/render.py 05 06
```

Chrome headless сохраняет карточки 1600 × 1100 в `docs/assets/screens/`.
При обновлении снимков скрой секрет и параметры ссылки `tg://proxy` перед
публикацией исходных кадров, фрагментов и контактного листа.
