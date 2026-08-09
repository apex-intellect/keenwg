# KeenWG 0.7.0 — platform foundation

Версия 0.7.0 добавляет capability-driven интерфейс, профили роутеров, защищённый HTTPS companion, точный TLS pin, одноразовый pairing, отзыв устройств и двухэтапный SSH-мастер установки. Существующие XKeen, Xray routing, исключения, WireGuard и collector продолжают работать через совместимые адаптеры.

## Локальная приёмка

- `go test ./... -race -count=1`: пройдено в Linux/WSL.
- `go vet ./...`: пройдено.
- install/uninstall companion и legacy controller в изолированных fake-root: пройдено.
- Android unit tests, `lintDebug`, `assembleDebug`, `assembleRelease`: пройдено.
- SHA companion asset внутри debug и release APK совпадает с committed manifest.
- Secret scan tracked sources: приватные ключи и приватные subscription URL не обнаружены.

## SHA-256

```text
d60e5b5e57d1542d141cb23735a6874f0be16f69e0755273e2d629a10512b8ae  app-debug.apk
3bc62d0066e9ef8b0cd692110a91f6f806e6b640fe9ad6ff1b82701cf0d034c8  app-release-unsigned.apk
78f4b6796bb44962376f266799d6cb50d8f91a2816ba03464263361034098e86  keenwg-companion-arm64-0.7.0.tar.gz
```

Release APK пока unsigned и не предназначен для публичной раздачи. Для локальной установки используется debug APK; публичный релиз требует стабильного signing key и повторного SHA после подписи.

## Ручная приёмка перед установкой на основной роутер

- Установить debug APK поверх 0.6 и подтвердить сохранение настроек.
- Через мастер сверить SSH fingerprint, план и pairing на физическом ARM64 роутере.
- Проверить неизменность активного узла, доменных правил, исключений и WireGuard peer после перезапуска.
- В тестовом окне намеренно оборвать установку до commit и подтвердить восстановление 0.6.
- Проверить санитизированный отчёт на телефоне.

Эти пункты требуют физического телефона и явного подтверждения мутации роутера; локальная сборка не отмечает их выполненными.
