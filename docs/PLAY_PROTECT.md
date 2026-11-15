# Play Protect и установка APK

Приложение распространяется напрямую APK, поэтому Google Play Защита проверяет его как приложение из интернета. Иногда она показывает «Вредоносное приложение заблокировано» — это классификация Play Protect, а не проверка подписи: действительная подпись сама по себе не отменяет блокировку.

## Почему это произошло в 1.1.0

В 1.1.0 приложение умело обновлять себя:

- запрашивало `REQUEST_INSTALL_PACKAGES` и скачивало APK из GitHub Releases;
- само открывало системный установщик;
- дополнительно запрашивало `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

Для Play Protect это признаки приложения, которое устанавливает другие пакеты и просит исключение из энергосбережения. Google [запрещает](https://support.google.com/googleplay/android-developer/answer/16558241) использовать `REQUEST_INSTALL_PACKAGES` для самостоятельных обновлений, а Play Protect относит такие возможности к [потенциально нежелательному ПО](https://developers.google.com/android/play-protect/warning-dev-guidance).

Начиная с 1.1.1:

- разрешения на установку APK и запрос исключения из экономии заряда удалены;
- APK больше не скачивается и не устанавливается: кнопка обновления открывает страницу релиза в браузере;
- CI проверяет собранный APK и падает, если запрещённые разрешения или строки установщика вернутся.

Приложение не собирает данные и не имеет чувствительных разрешений; полный список — в разделе [«Доступы»](../README.md#доступы).

## Как проверить официальную сборку

Официальная сборка называется `TG WS Proxy`. Сертификат подписи SHA-256:

```text
6b7e992f93bd0bfd8c9395ddf92d0e3684d236b0f29cdf637dfca80e20b60195
```

Скачай APK и `SHA256SUMS` из одного релиза и проверь:

```bash
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs tgwsproxy-*-arm64.apk
```

`apksigner` должен показать тот же отпечаток сертификата. Если хеш или сертификат не совпадают — файл не из этого репозитория, его устанавливать не нужно.

## Если Play Защита блокирует установку

1. Нажми «Подробнее» → «Всё равно установить», если кнопка доступна. Google может скрывать её в новых версиях Android.
2. Если кнопки нет, не отключай Play Защиту целиком. Пришли в issue:
   - версию Android и модель устройства;
   - имя APK и SHA-256 файла;
   - откуда скачан файл;
   - скриншот диалога.
3. Ложное срабатывание снимает апелляция в Google. Шаблон для владельца репозитория — ниже.

Предупреждение «неизвестное приложение» для sideload-сборки — нормальное; оно не означает, что в APK есть вредоносный код.

## Апелляция в Google Play Protect

Форма: <https://support.google.com/googleplay/android-developer/contact/protectappeals>. Если форма требует Play Console, нужен аккаунт разработчика; self-serve регистрация бесплатна.

Данные для апелляции:

- package: `io.github.derigava.tgwsproxy`;
- versionCode/versionName новой сборки;
- SHA-256 APK из `SHA256SUMS`;
- SHA-256 сертификата `6b7e992f93bd0bfd8c9395ddf92d0e3684d236b0f29cdf637dfca80e20b60195`;
- ссылки на репозиторий, релиз, `README.md` и этот файл.

Шаблон текста (английский):

```text
App: TG WS Proxy (io.github.derigava.tgwsproxy), versionName <...>, versionCode <...>
APK SHA-256: <...>
Signing certificate SHA-256: 6b7e992f93bd0bfd8c9395ddf92d0e3684d236b0f29cdf637dfca80e20b60195

Please review the Play Protect classification for this app. It is an open-source
(GPLv3) local MTProto proxy for Telegram; it does not collect personal data and
has no sensitive permissions.

Version 1.1.0 was classified as harmful on install. It included an in-app
self-update flow (REQUEST_INSTALL_PACKAGES) and requested a battery-optimization
exemption. We removed both in <version>:

- manifest permissions: INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS,
  RECEIVE_BOOT_COMPLETED;
- no APK is downloaded, written or installed; the update button only opens the
  GitHub release page in a browser;
- the APK contains no package-installer API, no
  application/vnd.android.package-archive intent and no
  REQUEST_INSTALL_PACKAGES string; the build fails if they reappear.

Repository: https://github.com/Derigava/tg-ws-proxy-android
Release: <link>
Verification: README.md#play-protect-и-установка-вне-google-play, docs/PLAY_PROTECT.md

Please rescan the new APK and clear the Play Protect classification if it is a
false positive.
```

После ответа Google обнови issue: ссылку на апелляцию и результат проверки.

## Android Developer Verification

С 30 сентября 2026 Google постепенно требует регистрацию разработчика и приложения для установки на сертифицированные устройства, включая sideload; глобально — позже. Это отдельный механизм от классификации Play Protect. Владельцу стоит заранее зарегистрировать пакет и ключ подписи в [Android Developer Console](https://support.google.com/android-developer-console/answer/16561738), чтобы после расширения программы установка не блокировалась по линии verification.
