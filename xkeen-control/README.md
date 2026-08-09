# KeenWG XKeen Controller

Контроллер добавляет в KeenWG ручное обновление приватной VLESS-подписки, безопасный выбор узла XKeen и редактор структурированных доменных маршрутов. Он слушает только приватный IPv4-адрес роутера (`10.8.0.1:18778`), использует отдельный Bearer-токен и не передаёт Android-приложению UUID, Reality-ключи, SNI, исходные VLESS URI, URL подписки или сырой Xray-конфиг.

Автоматического обновления нет: приложение читает сохранённый статус, а загрузка подписки и смена страны запускаются только пользователем. Обновление подписки само по себе не меняет активный Xray outbound и не перезапускает XKeen.

## Сборка

Нужны Go 1.26.5, Git for Windows с GNU tar/gzip и Linux-среда для race detector. Из корня репозитория:

```powershell
powershell -ExecutionPolicy Bypass -File xkeen-control\scripts\build.ps1 -Version 0.4.0
```

Сборщик проверяет чистое дерево `xkeen-control`, shell lifecycle, Go-тесты, race detector, vet, повторяемость бинарника и архива. Результат появляется в `dist/xkeen-control/`.

## Установка

Распакуйте архив в новый каталог под `/opt/tmp`, сверьте SHA-256 и запустите `sh install.sh`. Установщик без эха запросит приватный HTTPS URL, например `https://vpn.example.test/sub/private`. URL не передаётся аргументом процесса.

При установке:

- создаётся отдельный 64-символьный control token, который показывается один раз;
- текущий endpoint переносится из `S05xkeen` в помеченный блок `/opt/etc/xkeen/ip_exclude.lst`;
- резервные копии сохраняются в `/opt/etc/keenwg/backups/`;
- доменные правила из `05_routing.json` импортируются в `/opt/etc/keenwg/domain-policy.json`;
- общие `.info` и `.tv` заменяются на `category-gov-ru` и точечные домены Okko, а `.ru`, `.su`, кириллические российские зоны, `.moscow` и `geoip:ru` сохраняются;
- текущий активный outbound считывается без загрузки подписки и без перезапуска XKeen;
- запускается `/opt/etc/init.d/S96keenwg-xkeen-control`.

Control token нужно вручную сохранить в настройках KeenWG. Это не токен истории WireGuard и не URL подписки.

## Проверка и управление

Проверка без скачивания, записи файлов и restart:

```sh
/opt/lib/keenwg-xkeen-control/current/keenwg-xkeen-control \
  -config /opt/etc/keenwg/xkeen-control.json -check
```

Санитизированный локальный статус: `/opt/sbin/xkeen-country status`.

HTTP health доступен без токена на `GET /v1/xkeen/health`; status, refresh, select, diagnostics и все network-операции требуют control token. Доменные правила читаются через `GET /v1/network/domains`, а создаются, меняются и удаляются через `/v1/network/domains/rules`. API принимает только домен, разрешённую зону или GeoSite-пресет и эффект `direct`/`vpn`; произвольные regex, JSON и shell-команды запрещены.

GeoSite сопоставляет имя сайта, а `geoip:ru` — IP назначения. Поэтому GeoIP остаётся запасным уровнем, но не заменяет доменные правила. `ext_exclude` намеренно не используется: преобразование домена в общий CDN-IP может затронуть посторонние сайты.

Результаты смены узла: `success`, `failed_no_change`, `failed_rolled_back` или `uncertain`. Результаты изменения доменных правил: `committed`, `rolled_back`, `rejected` или `uncertain`. При `uncertain` новые изменения блокируются до успешного чтения и сверки policy с Xray.

Для проверки rollback без изменения трафика остановите только сервис контроллера, выполните `-self-test-rollback`, затем запустите сервис снова. Тест записывает candidate-файлы, намеренно останавливается до restart, восстанавливает исходные байты и подтверждает старый endpoint.

## Удаление и восстановление

`sh uninstall.sh` оставляет последний подтверждённый Xray outbound активным, возвращает endpoint в `S05xkeen`, восстанавливает прежние доменные правила и `xkeen-country`; созданная policy остаётся в install-backup. `sh uninstall.sh --purge` дополнительно удаляет приватное состояние контроллера после подтверждённой остановки процесса.

Если операция завершилась `uncertain`, не редактируйте state JSON вручную. Остановите контроллер, восстановите `S05xkeen`, `ip_exclude.lst` и `04_outbounds.json` из одного каталога установки в `/opt/etc/keenwg/backups/`, выполните Xray `run -test`, перезапустите XKeen и проверьте endpoint в `user_exclude`.
