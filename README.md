# LitEnergy VPN для Android

Клиент сервиса LitEnergy VPN — быстрый и стабильный VPN для защиты соединения.
Добавьте подписку (ссылка, QR-код, ссылка `litenergy://import?url=…`, вход по почте или через Telegram),
выберите сервер или «Автовыбор» и подключайтесь одной кнопкой.

## Возможности

- Несколько подписок с переключением; срок и трафик из данных подписки; автообновление (по умолчанию раз в 12 часов).
- Форматы подписки: готовая конфигурация ядра (JSON), список ссылок vless / vmess / trojan / ss / hysteria2 / tuic (в том числе base64), Clash YAML.
- Серверы с пингом, поиском и автовыбором самого быстрого.
- Маршрутизация «Весь трафик» или «Российские сайты напрямую», выбор приложений для VPN, свой DNS, IPv6.
- Плитка в шторке, уведомление с таймером и кнопкой «Отключить», постоянный VPN, проверка обновлений через GitHub Releases.
- Ссылка подписки хранится в зашифрованном виде (ключ в Android Keystore) и не пишется в логи.

## Сборка

Только через GitHub Actions (`.github/workflows/build.yml`): ядро для arm64 / armv7 / x86_64 → APK →
проверка на эмуляторе со скриншотами → релиз по тегу `v*`. Версия — в `version.properties`
(`VERSION_CODE`, `VERSION_NAME`).

Секреты репозитория: `KEYSTORE_B64` (ключ подписи в base64) и `LOCAL_PROPERTIES`
(base64 от `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`).

## Лицензия

GPL-3.0-or-later. LitEnergy VPN — модифицированная версия
[sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) с ядром
[sing-box](https://github.com/SagerNet/sing-box) (© SagerNet, GPL-3.0).
Изменения: собственный интерфейс, название, иконка и идентификатор приложения `com.litenergy.vpn`,
работа с подписками сервиса LitEnergy VPN. Проект не связан с sing-box и не использует его название.
