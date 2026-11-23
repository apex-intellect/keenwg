# KeenWG design system

The native Android UI follows the approved HTML prototype's ivory/graphite language. Production screens keep their existing state, capabilities, permissions and transaction callbacks. Prototype sample values are not production data.

## Foundations

| Role | Light | Dark |
| :--- | :--- | :--- |
| Background | `#F5F2EA` | `#242522` |
| Surface | `#FBF9F3` | `#2C2D29` |
| Primary text | `#282927` | `#F2EFE6` |
| Secondary text | `#686962` | `#B9B9AD` |
| Divider | `#DBD8D0` | `#48493F` |
| Selected / soft | `#EAE7DF` | `#393A34` |

Colors live in `ui/theme/Color.kt`, mapped to Material semantic roles in `Theme.kt`. Status colors are reserved for success, warnings and errors; state is also conveyed in text.

- `KeenSpacing`: screen 20, card 18, section 24, item 16, group 12, inline 8, tight 4 dp.
- `KeenRadius`: badge 8, control 24, card 27, sheet 32, navigation 29 dp.
- `KeenSize`: 48 dp minimum interactive target; 22 dp navigation icons.
- `KeenScale`: primitive values for specialized diagrams and existing dense layouts. Prefer semantic roles for new UI.
- `Type.kt`: one native sans-serif hierarchy, with JetBrains Mono for technical values.
- `ContinuousShape`: tangent cubic corners for cards, controls and navigation. Native form fields and sheets use the shared Material shapes.

## Components and composition

`KeenCard`, `KeenButton`, `KeenOutlinedButton`, `KeenTextButton` and `KeenTopAppBar` are thin adapters over Material 3. They preserve focus, ripple, disabled states and accessibility. Buttons use continuous 24 dp corners, centered wrapping labels and at least 48 dp touch targets. Feature code imports them under familiar names, keeping business logic unchanged.

`KeenGlassSurface` groups rows. `KeenFloatingNavigationHost` overlays `KeenBottomIsland` on a full-height content viewport, not a Scaffold bottom-bar slot. `LocalKeenBottomClearance` supplies extra scroll-tail and floating-action clearance; it must not become viewport padding.

On Android 12+, the island samples a shared Compose GraphicsLayer display list and blurs only its own region plus the blur kernel. The source invalidates the effect when it redraws, including scrolling. No screen bitmap is exported or stored. `KeenGlass` owns blur, tint and shadow tokens. Icons, labels and native selected-tab semantics are drawn after the effect and remain sharp. Android 8–11 uses a high-contrast tinted fallback because RenderEffect is unavailable. MainActivity configures transparent system bars and matching light/dark icons without Android's opaque navigation contrast scrim.

Main destinations retain the floating bar; nested pages retain their back action. Long text wraps and navigation items use minimum, not fixed, height. Overview links only open capabilities available on the router.

## Data and safety

Do not place demo traffic, invented devices or synthetic online status in production. Unknown and unsupported are distinct from off. Keep confirmation, review, read-back, rollback, owner-only operations and self-peer guards intact.

## Verification

`DesignSystemContractTest` rejects literal dp/sp values or hexadecimal colors outside theme files. Navigation and layout contracts cover the shared components. Android instrumentation checks real screens in light/dark/large-text modes, backdrop blur and redraw, scroll-tail clearance, navigation selection and the XKeen stop-confirmation flow. Full-window screenshots separately check gesture and three-button system navigation.

README structure was informed by [LocalSend](https://github.com/localsend/localsend) and [Read You](https://github.com/ReadYouApp/ReadYou): concise entry points, language links, focused badges and progressive disclosure. No artwork or application screenshots were copied.
