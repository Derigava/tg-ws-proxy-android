# Изменения

Раздел с номером собираемой версии попадает в начало текста релиза на GitHub. Линейка версий задаётся в файле `VERSION`, патч CI подбирает сам.

## 1.2.0

### Второй модуль: ByeByeDPI

- Одно приложение вместо двух: слева TG-WS с локальным MTProto-прокси, справа ByeByeDPI на снимке `romanvht/ByeByeDPI` v.1.8.2. По умолчанию DPI работает как системный VPN на весь телефон, второй режим — локальный SOCKS5 на `127.0.0.1:1080`. Оба режима без root, TG-WS работает вместе с каждым из них.
- Подбор и история стратегий, параметры desync, списки доменов, фильтр приложений, импорт и экспорт настроек ByeByeDPI, диагностика, логи с разделением по источнику, ярлыки, плитка быстрых настроек и управление пультом.
- VPN захватывается только по явному согласию: если работает другой VPN, приложение предлагает замену, SOCKS5 или отмену и не отзывает чужой туннель молча.

### Имя, иконка, «О проекте»

- Приложение называется **ByeBye TGWS Proxy**: под иконкой, в системных настройках, в заголовках и разделе «О проекте». `applicationId`, package name и идентификаторы не менялись, поэтому обновление ставится поверх установленной версии.
- Иконка — знак маршрута: adaptive с монохромной и круглой версиями, плюс растровые варианты иконки и круглого значка для лаунчеров Android 7 и ниже.
- В «О проекте» три ссылки на исходники: это приложение, ядро Flowseal и основа DPI `romanvht/ByeByeDPI`.
- Нижний технический блок с версией и лицензиями убран. Последний элемент раздела — предупреждение «Неофициальное приложение, не связано с Telegram»; тексты лицензий и уведомления сторонних компонентов остались в APK.
- `versionName` 1.2.0. `versionCode` теперь выводится из имени сборки (`major*10000 + minor*100 + patch` = 10200), поэтому каждая следующая сборка строго выше предыдущей и обновление её видит.

### English

One app instead of two: TG-WS runs the local MTProto proxy and ByeByeDPI (`romanvht/ByeByeDPI` v.1.8.2) runs as a device-wide VPN or a local SOCKS5 proxy without root, including strategy search and history, desync parameters, domain lists, app filter, settings import/export, diagnostics, per-source logs, shortcuts, the quick-settings tile and remote navigation. The app asks before replacing another active VPN.

The app is now called **ByeBye TGWS Proxy** in the launcher, system settings, headers and the About section; the package and identifiers are unchanged, so it installs over the previous version. The launcher icon is the route mark with adaptive, round, monochrome and legacy bitmap variants. About lists the three sources (this app, the Flowseal core and `romanvht/ByeByeDPI`) and ends with the disclaimer "Unofficial app, not affiliated with Telegram."; the version/license footer is gone while the license texts stay in the APK. `versionName` is 1.2.0 and `versionCode` is derived from the name (10200), so every build stays above the previous one.

## 1.1.2

### Обход перестал работать в режиме CloudFlare CDN

- С включённым CloudFlare CDN приложение не задавало адресов DC, и единственным маршрутом оставались общие CF-домены. Когда они перестали отвечать, Telegram не подключался вообще, хотя на ПК всё работало.
- Теперь «Адреса DC: авто» — то же, что на ПК: прямой WebSocket к DC2 и DC4 идёт первым, CF остаётся запасным маршрутом. Ручные адреса DC при выключенном CF работают как раньше.
- Если ни один CF-домен не ответил, CF-маршрут пропускается на 30 секунд (дальше до 5 минут), а не перебирается заново для каждого соединения по 10 секунд на домен. Успешное подключение через CF или смена сети снимает паузу сразу.

### English

With CloudFlare CDN on, the app sent no DC addresses, so the shared CF domains were the only route and their outage cut Telegram off. Auto now matches the desktop proxy: direct WebSocket to DC2 and DC4 first, CF as fallback. When no CF domain answers, the CF route is skipped with backoff (30 s up to 5 min) instead of being walked again for every connection; a CF success or a network change clears it.

## 1.1.1

### Play Protect

- Убраны `REQUEST_INSTALL_PACKAGES` и `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: приложение больше не скачивает и не устанавливает APK и не просит исключение из экономии заряда.
- Обновление теперь только вручную: страница релиза открывается в браузере, установку подтверждает Android. Встроенный self-update удалён.
- CI проверяет манифест и DEX: запрещённые разрешения и строки установщика валят сборку.
- Если Play Protect всё ещё блокирует установку, пришли версию Android, имя APK, SHA-256 и откуда скачан файл — подробности в `docs/PLAY_PROTECT.md`.

### English

No self-updater, install permission or battery-exemption request. Updates are manual from the GitHub release page, and CI rejects any future installer capability. If Play Protect still blocks the APK, report the Android version, APK name, SHA-256 and download source.

## 1.1.0

### Свой Cloudflare

- В «Настройки → Свой Cloudflare» два поля: домены CF-прокси и домены CF Worker, по несколько через запятую.
- Воркеры ядро пробует первыми, до общих CF-доменов и прямого подключения, даже при выключенном CloudFlare CDN. Свой воркер не делит квоту с другими пользователями.
- Неверный домен подсвечивается прямо в поле. В отчёт попадает только число воркеров, в журнале они названы `worker#N`.

### Восстановление после смены сети

- После сбоев ядро откладывало прямое подключение и пополнение пула до часа, и помогал только перезапуск прокси. Теперь при смене сети или возврате интернета задержки сбрасываются сами, текущие соединения не рвутся.

### Батарея

- Процессор больше не держится бодрствующим всё время работы прокси: только пока идёт трафик и минуту после.

### Диагностика

- Журнал с метками времени ведётся и в фоне. Секрет и домены воркеров в нём замаскированы.
- «Собрать отчёт» показывает ошибки и события за 24 часа, а не только то, что успело попасть на экран.
- «Инфо → Поделиться журналом» отдаёт весь журнал файлом для долгих отвалов.

### Управление без интерфейса

- Ярлыки «Запустить прокси» и «Остановить прокси» по долгому нажатию на иконку.
- Действия `io.github.derigava.tgwsproxy.action.START`, `STOP` и `TOGGLE` для MacroDroid и Tasker. Подробности в README.

### English

Own Cloudflare proxy domains and Cloudflare Worker domains in Settings, with Workers tried first. The proxy recovers by itself after a network switch or outage. The wake lock is held only while traffic flows. A background journal feeds a 24-hour report and can be shared as a file. Launcher shortcuts and START/STOP/TOGGLE actions for automation apps.
