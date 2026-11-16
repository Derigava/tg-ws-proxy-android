# Изменения

Раздел с номером собираемой версии попадает в начало текста релиза на GitHub. Линейка версий задаётся в файле `VERSION`, патч CI подбирает сам.

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
