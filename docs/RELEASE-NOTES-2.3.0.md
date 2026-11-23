# KeenWG 2.3.0

## A consistent native interface

- Ivory/graphite light and dark themes across the Android screens.
- Shared color, spacing, typography, corner and component tokens.
- Floating translucent navigation, outline icons and compact rule tabs.
- Cleaner overview with capability-aware shortcuts.
- Existing router state, permissions, reviews and confirmations preserved; no prototype telemetry added.

## Connections and reliability

- Standalone VLESS import improvements alongside subscription import.
- Explicit XKeen runtime controls with verified state, capability checks and stop confirmation.
- Shared runtime guards serialize start/stop commands with routing changes and component updates.
- Companion watchdog and lifecycle fixes, with packaging regression coverage.

## Documentation

- English is the primary README; Russian and German versions are included.
- Theme-aware project banner, quick-start links, compatibility and security guidance.
- A documented native design system and regression contracts.

## Updating

Install the signed APK over the existing official app; profiles are retained.
Update Companion separately through the app to use the new runtime controls and watchdog changes.
Publishing or installing this APK does not automatically replace the router service.

Router support remains ARM64/Entware with the documented compatibility limits. Additional physical router models are not newly certified by this release.
