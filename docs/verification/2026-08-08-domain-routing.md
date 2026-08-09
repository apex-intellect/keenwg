# KeenWG 0.6 domain routing verification

Date: 2026-08-08

## Automated gates

- Android unit tests
- Android lintDebug
- Android debug and release assembly
- Root scaffold inset regression test
- Go controller tests
- Go vet
- Staged controller install/upgrade/failure/uninstall lifecycle

## Router acceptance

- controller health and authenticated domain status;
- one managed domain-policy marker block;
- no broad `.info` or `.tv` matcher;
- `category-gov-ru`, `okko.ru`, `okko.tv` and `okko.sport` present;
- `.ru`, `.su`, Russian Cyrillic zones, `.moscow` and `geoip:ru` retained;
- Xray configuration validation and XKeen service status;
- active XKeen country and outbound unchanged;
- no control token, subscription URL, VLESS URI or UUID in deliverables.

## Result

- Deployed controller: `keenwg-xkeen-control 0.6.0` (`fd641634435b57476247d1833b65c1a508be5498`)
- Router acceptance: `domain-routing-acceptance-ok`
- Active XKeen route after installation and restart: Netherlands 1, state version 5
- Android gate: `BUILD SUCCESSFUL` (97 tasks)
- APK signature verification: APK Signature Scheme v2, one signer

## SHA-256

- Installable APK: `ba5095bb439964c540925ca32a302e530dab9231b6160264677387260d24b50d`
- Controller archive: `9e044583f8dbb87939b0d824145bd99a2fb7c37a379ac83f73d2c61335bd492a`
- Live routing projection: `d702c7b19603d64fdc421bd3c84d4f88b942221ea0b58579e9d79ab16b234b2d`
- Live domain policy: `8a556b383c459ffa30aca85a1fd4db40549aa05259bb0560eae3770d063c9f54`
