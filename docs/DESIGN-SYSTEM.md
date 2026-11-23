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
- `KeenRadius`: badge 8, control 14, card 27, sheet 32, navigation 29 dp.
- `KeenSize`: 48 dp minimum interactive target; 22 dp navigation icons.
- `KeenScale`: primitive values for specialized diagrams and existing dense layouts. Prefer semantic roles for new UI.
- `Type.kt`: one native sans-serif hierarchy, with JetBrains Mono for technical values.
- `ContinuousShape`: tangent cubic corners for cards, controls and navigation. Native form fields and sheets use the shared Material shapes.

## Components and composition

`KeenCard`, `KeenButton`, `KeenOutlinedButton` and `KeenTopAppBar` are thin adapters over Material 3. They preserve focus, ripple, disabled states and accessibility. Feature code imports them under familiar names, keeping business logic unchanged.

`KeenGlassSurface` groups rows. `KeenBottomIsland` provides translucent, rimmed navigation with native selected-tab semantics and system-bar clearance. It uses a translucent surface rather than an expensive live backdrop capture; no blur is applied to labels or icons.

Main destinations retain the floating bar; nested pages retain their back action. Long text wraps and navigation items use minimum, not fixed, height. Overview links only open capabilities available on the router.

## Data and safety

Do not place demo traffic, invented devices or synthetic online status in production. Unknown and unsupported are distinct from off. Keep confirmation, review, read-back, rollback, owner-only operations and self-peer guards intact.

## Verification

`DesignSystemContractTest` rejects literal dp/sp values or hexadecimal colors outside theme files. Navigation and layout contracts cover the shared components. Android instrumentation checks the navigation, theme states and XKeen stop-confirmation flow.

README structure was informed by [LocalSend](https://github.com/localsend/localsend) and [Read You](https://github.com/ReadYouApp/ReadYou): concise entry points, language links, focused badges and progressive disclosure. No artwork or application screenshots were copied.
