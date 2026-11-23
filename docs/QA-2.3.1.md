# 2.3.1 UI verification

## Local checks

- 394 JVM tests passed, with no failures or skipped tests.
- Debug application and instrumentation APKs built successfully.
- Android lint completed with no errors (59 existing warnings).
- Locale, UI-resource and FileProvider-policy checks passed.
- Eight instrumentation tests passed on Android 15 / API 35, repeated after the final changes in English portrait, Russian portrait and Russian landscape.
- Each screen sweep captured 15 real unpaired screens in light, dark and 200% font-scale modes: 135 final captures across those three runs.

## Regressions covered

- Background stripes are actually blurred, not just covered by a translucent tint.
- A change in the content display list updates the glass; no stale backdrop.
- Content extends below the navigation island and the final scrollable row clears it.
- The setup action on the Connections page remains reachable above the island, including landscape and large text.
- All navigation destinations remain selectable at 200% font scale.
- Existing XKeen stop confirmation, cancellation, read-only access and unknown-state safeguards still pass.
- Full-window MainActivity captures checked both gesture and three-button system navigation with light/dark colors. The system navigation area has no opaque black scrim.
- README screen captures were regenerated from the real native UI, without a router connection or fabricated telemetry.

## Limits and safety

The emulator had Wi-Fi and mobile data disabled. No live router commands, service restarts or routing changes were used for this verification. Backend service source is unchanged from 2.3.0.

Live blur uses Android 12+ RenderEffect. Android 8–11's tinted fallback is code-reviewed and build-checked, not physically validated in this run. Emulator screenshots are not a physical-device performance benchmark.
