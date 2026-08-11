# KeenWG 2.0.0

KeenWG 2.0 удаляет старый самостоятельный HTTP-сервис XKeen и оставляет один защищённый Companion API. Пользовательские функции, активный маршрут и данные сохраняются.

## Главное

- XKeen status, subscription refresh, выбор узла, домены и IP-исключения работают через pinned HTTPS Companion;
- у каждого телефона собственный отзываемый device token вместо общего controller token;
- чистая установка больше не требует файлов старого сервиса;
- update публичного конфига 1.0 (без поля версии или с `schema_version: 1`) → schema 2 сохраняет certificate identity, pairing, подписки, активный узел, маршруты и recovery state;
- актуальный Companion поддерживает `PAIR_ONLY`: первая привязка и восстановление owner-телефона через подтверждённый SSH работают без загрузки или перезапуска сервиса, в том числе при уже существующих доверенных устройствах;
- после успешного health check installer удаляет только фиксированный allowlist устаревших файлов;
- Android-профили обновляются до schema 2 и больше не дублируются в плоские preference keys;
- RCI и Collector остаются независимыми необязательными транспортами;
- экран диагностики предлагает прямой переход к pairing вместо тупиковой ошибки;
- Go module paths перенесены в `github.com/apex-intellect/keenwg`;
- добавлен Gradle Wrapper 8.12 и исправлен публичный CI.

## Что не происходит автоматически

Обновление не меняет страну, активный XKeen node, routing policy, VLESS source, WireGuard peer или статические DHCP leases. Subscription refresh, node test, activation, scenario apply, cleanup и restore требуют явного действия пользователя.

## Обновление с 1.0

Установите APK 2.0.0 поверх подписанной 1.0.0 и запустите мастер Companion. Он выберет `UPDATE`, проверит candidate по HTTPS, обновит config schema, сохранит identity/device store и только после успешного health удалит устаревший сервис. При ранней ошибке предыдущий Companion остаётся активным.

После обновления проверьте статус Companion, текущий узел, список подключений, доменные/IP-правила, WireGuard-доступы и Collector history. Для потерянного телефона повторите мастер: при уже установленной 2.0 будет выбран `PAIR_ONLY`.

Физический update 1.0.0 → 2.0.0 проверен на Netcraze Hopper SE: хэши Xray-конфигов, XKeen-исключений, доменной политики и активного routing state до и после совпали; незащищённый listener и allowlist-файлы старого сервиса удалены только после успешного health check 2.0.0.

## Совместимость

Подтверждённая платформа и experimental-границы перечислены в [COMPATIBILITY.md](COMPATIBILITY.md). WAN/wildcard Companion listener, MIPS/MIPSel и автоматическая смена страны не поддерживаются.
