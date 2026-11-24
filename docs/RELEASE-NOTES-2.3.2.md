# KeenWG 2.3.2

## Reliable Home navigation

- Overview shortcuts and bottom tabs now use one navigation policy.
- Home no longer restores a saved section stack as if it were the Overview screen.
- Other tabs still preserve their state; system Back returns to Overview.
- Regression coverage includes the old saved-stack alias and repeated shortcut → Home transitions.

## Clearer glass and a new identity

- More transparent floating navigation, a subtle directional rim highlight and background-only edge refraction on Android 13+.
- Android 12 retains live blur; Android 8–11 retains the readable tinted fallback. Unsupported shader implementations also fall back to blur.
- Icons, labels and touch areas are not distorted. The optical effect is static, without a decorative animation loop.
- New vector Route K mark, in graphite on ivory, shared by the adaptive launcher icon and About screen; themed Android icons use the matching monochrome shape.
- Apex Intellect's existing logo geometry is preserved and follows the app's light/dark foreground color.

## Updating

Install the signed APK over the existing official app. Profiles are retained. No Companion update is required for these changes. This release does not change routing, WireGuard configuration or router-service code.
