# KeenWG Companion

Этот Go-модуль реализует локальный Companion для KeenWG. Несмотря на историческое имя каталога, публичный runtime и бинарник называются только `keenwg-companion`.

Companion объединяет XKeen/VLESS, каталог подключений, доменные и IP-маршруты, домашние устройства, статические DHCP-адреса, WireGuard-доступы, сценарии, диагностику, backup и управление доверенными телефонами. Production runtime поднимает один HTTPS listener на приватном IPv4 и не содержит самостоятельного cleartext XKeen API.

## Security contract

- exact certificate pinning на Android;
- отдельный хешируемый token каждого устройства;
- scopes `viewer`, `operator`, `owner`;
- state version и idempotency key для изменений;
- bounded strict JSON/VLESS parsers;
- fixed allowlisted `ndmq` commands with bounded time and output;
- reviewed, versioned and idempotent DHCP/WireGuard mutations with read-back and rollback;
- WireGuard private keys are generated on Android and never cross the Companion API;
- transactional Xray test, restart, read-back и rollback;
- subscription URL, UUID, Reality keys, SNI и raw Xray config не возвращаются клиенту;
- wildcard, loopback, hostname и публичный listener отклоняются.

## Проверка

```bash
go test ./... -count=1
go test -race ./... -count=1
go vet ./...
sh packaging/install-companion_test.sh
```

## Сборка ARM64 bundle

Из корня репозитория:

```powershell
.\scripts\build-companion-bundle.ps1 -Version 2.1.2 -GoExecutable go
.\scripts\stage-companion-asset.ps1 -Archive .\dist\keenwg-companion-arm64-2.1.2.tar.gz
```

Архив детерминированно содержит binary, init script, installer, uninstaller, allowlisted obsolete cleanup, config example, `VERSION` и `SHA256SUMS`. `install-companion.sh` поддерживает чистую установку, атомарное обновление schema 1 → 2 и rollback предыдущего release.

Ручная установка не является основным пользовательским потоком. Используйте проверяемый мастер в Android-приложении; он получает SSH fingerprint до пароля и показывает точный план до записи.

См. [настройку Companion](../docs/COMPANION-SETUP.md) и [модель безопасности](../docs/SECURITY-MODEL.md).
