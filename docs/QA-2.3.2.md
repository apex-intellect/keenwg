# 2.3.2 UI verification

## Automated checks

- 396 JVM tests passed. Android lint completed with no errors (57 warnings).
- Debug application and instrumentation APKs built successfully; locale, UI-resource and FileProvider-policy checks passed.
- All 12 instrumentation tests passed on Android 15 / API 35 in Russian portrait, Russian landscape and English portrait.
- The screen sweep captured 15 real unpaired screens in light, dark and 200% font-scale modes: 135 captures across those runs.

## Regressions and visual review

- A real NavHost reproduces the 2.3.1 saved-stack alias bug with the old navigation options, then verifies that the new Home action recovers.
- Repeated Overview shortcut → section → Home transitions pass for every tab. Other sections retain saveable state; system Back still reaches Overview.
- AGSL compilation and effect-cache invalidation pass on API 35. Pixel tests verify actual backdrop blur and redraw, not merely a translucent fill.
- Scroll-tail clearance, large-text tab selection and existing XKeen stop-confirmation/read-only/unknown-state checks remain green.
- The new native vector mark is shared by launcher and About. Foreground and monochrome vector paths are checked for identical geometry.
- About was visually reviewed in both themes: the Apex Intellect wordmark retains its geometry with a theme-aware monochrome tint. The debug build correctly displays its unverified-signature notice.
- README navigation screenshots were refreshed from real unpaired screens, without fabricated telemetry.
- Full-window MainActivity captures were checked with gesture and three-button navigation: the floating menu and system bar remain separate, without an opaque black block below the menu.

## Limits and safety

The emulator's Wi-Fi and mobile data were disabled. No live router access, service restarts or network-configuration changes were used. Router-service source is unchanged from 2.3.1, and these UI changes do not require a Companion update.

Edge refraction requires Android 13+. Android 12 uses live blur; Android 8–11 uses a tinted fallback. Older-API fallback branches were code-reviewed and build-checked, not physically tested in this run. Emulator rendering is not a physical-device performance benchmark.
