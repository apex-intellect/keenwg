# KeenWG Stable Glass design system

**Platform:** Android / Jetpack Compose Material 3  
**Direction:** light, compact, calm, and native; Apple-like restraint without copying iOS controls

## Principles

- Use a soft paper canvas, white translucent surfaces, black text, and one blue/cyan accent pair.
- Keep screen gutters at 16 dp on phones. Cards never grow horizontally or scale on press.
- Reserve space for asynchronous results. Ping, route checks, and health updates replace content inside a fixed result slot and never move the scroll position.
- Use Material icons and platform typography. Country flags are content, not navigation icons.
- Every target is at least 48 dp. Press indication is clipped by a clickable Material `Surface` or `Card` with the same shape as the visible control; do not use an unshaped foundation `clickable` modifier.
- All app-authored copy comes from Android resources. English is the unqualified default; Russian lives in `values-ru`.

## Semantic colors

| Token | Value | Use |
|---|---:|---|
| `background` | `#F3F4F5` | Paper canvas |
| `surface` | `#FFFFFF` at 82–88% | Stable glass cards |
| `ink` | `#090B0E` | Main text and high-contrast controls |
| `muted` | `#6C727B` | Secondary copy |
| `primary` | `#176BFF` | Main action and link |
| `cyan` | `#50D5FF` | Brand orb and selected navigation underline |
| `success` | `#148B66` | Confirmed state |
| `warning` | `#9A6500` | Caution |
| `error` | `#D84F57` | Failure and destructive state |
| `outline` | `#D4D7DC` | Borders and separators |
| `navigation` | `#050608` at 97% | Floating bottom island |

Color is never the only status signal: pair it with an icon and explicit text.

## Shape, spacing, and motion

- Spacing scale: 4, 8, 12, 16, 24, 32 dp.
- Shapes: 8, 12, 16, 20, and 28 dp; status cards may use 28 dp.
- Bottom island: 14 dp side margin, 28 dp radius, navigation-bar inset, near-black background. A selected destination uses higher white contrast and a short blue/cyan underline.
- Standard transition: 180–240 ms fade or color change. Do not animate card size for live status.
- Blobs are large, low-opacity background fields only. They never reduce contrast or become interactive decoration.

## Stable async cards

- Connection and route cards define one result slot before any request begins.
- Empty, loading, success, and failure states render inside that slot with one line and ellipsis at extreme font scales.
- The control row remains in the same position. Refresh does not recreate or reorder the list unless the returned catalog actually changes.

## Brand and provenance

- The Apex Route mark combines a black route/apex line with a blue/cyan node.
- “KeenWG by Apex Intellect” appears in About, not repeatedly on operational screens.
- About exposes the official repository, Apex Intellect website, maintainer contact, Apache 2.0, and the brand/redistribution policy.
- Official-build status is based on the APK signer certificate. Mirrors do not affect it; modified signatures show an unverified-build warning.

## Delivery checks

- Test 360–430 dp phone widths, landscape, large phone, and tablet.
- Test the largest font scale and TalkBack order; allow important text to wrap outside fixed result slots.
- Test gesture and three-button navigation.
- Verify English, Russian, and “same as system”, including Activity recreation.
- Verify all rounded targets have bounded state layers and no rectangular overlay.
- Verify ping, route checks, and refresh results do not change card height or scroll position.
