# KeenWG 2.3.1

## Floating navigation, corrected

- Navigation now overlays scrolling content instead of occupying a separate bottom strip.
- Real background blur on Android 12+, with sharp icons and labels. Android 8–11 retains a contrast-safe tinted fallback.
- Transparent system navigation area with light/dark system icons; no forced black contrast rectangle below the menu.
- Scroll-tail spacing and the access action keep the final rows and buttons reachable above the floating menu.

## More compact, consistent controls

- Shared rounded controls, including text buttons and the add-access action.
- Smaller card headings and body text, centered multiline button labels, and shorter system-menu descriptions.
- Minimum 48 dp touch targets and adaptive large-text navigation are preserved.
- English, Russian and German documentation updated to describe the actual rendering and compatibility limits.

## Scope and updating

Install the signed APK over the existing official app; profiles are retained.
This patch changes Android presentation only. It does not change routing, subscriptions, WireGuard keys or router-service behavior. An existing Companion 2.3.0 does not need an update for these UI fixes. Installing the APK does not restart or update the router.

The release includes a versioned Companion package for fresh installations; its service source is unchanged from 2.3.0.
