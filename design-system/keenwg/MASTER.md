# KeenWG native design system

**Project:** KeenWG 2.0
**Platform:** Android / Jetpack Compose Material 3  
**Direction:** native Material 3 dark utility; calm, rounded, layered and precise

## Principles

- Feel premium through spacing, typography, restrained motion and clean hierarchy; do not copy iOS controls literally.
- Use Material Symbols/Compose vector icons for navigation and actions. Country flags remain content, not structural icons.
- Keep one primary action per screen and keep destructive actions separated and confirmed.
- All interactive targets are at least 48 dp with at least 8 dp between neighbouring targets.
- Preserve system font scaling, screen-reader order, predictive back and navigation-bar insets.
- Use restrained glass only for status/onboarding surfaces and the navigation island: `surfaceContainer` at 90–94% alpha, a neutral outline and normal tonal elevation. The identical opaque fallback must remain usable; no glow, gradients or continuous blur.

## Semantic colors

| Token | Dark value | Use |
|---|---:|---|
| `background` | `#090D14` | App canvas; never pure black |
| `surface` | `#121925` | Standard cards and fields |
| `surfaceElevated` | `#182231` | Sheets, selected/raised content |
| `navigation` | `#151E2B` | Floating bottom island |
| `primary` | `#69C7E4` | Main action and selected navigation |
| `success` | `#4FD1B5` | Connected/healthy state |
| `warning` | `#F2B84B` | Degraded or caution state |
| `error` | `#FF6B6B` | Failed/destructive state |
| `onBackground` | `#F4F7FB` | Primary text |
| `onSurfaceVariant` | `#AAB6C5` | Secondary text; minimum 3:1 |
| `outline` | `#2B3A4B` | Borders and separators |
| `scrim` | `#99000000` | Modal isolation |

Components consume semantic theme tokens; no per-screen literal colors. Status always has an icon or label in addition to color.

## Shape, spacing and type

- Spacing follows a 4/8 dp scale: `4, 8, 12, 16, 24, 32`.
- Screen gutters: 16 dp compact, 24 dp medium, readable-width column on expanded layouts.
- Material shape scale: 8/12/16/20/28 dp. Standard cards use 20 dp; controls follow their native Material 3 shape; primary buttons are not given decorative custom geometry.
- Floating navigation: 28 dp outer radius, 14 dp side margin and navigation-bar-safe bottom margin. Use `NavigationBar`/`NavigationBarItem` with a clipped 22 dp item indication.
- Use Android system Roboto through the unmodified Material 3 type scale. JetBrains Mono is limited to collapsed technical details and identifiers.
- Prefer wrapping over truncation for names and errors. Use tabular figures for IPs, timing and transfer statistics.

## Navigation island

- At most five capability-driven items: Overview and System are permanent; Connections, Routes and Access appear only when supported by the selected router.
- Every item has a consistent vector icon and a short text label.
- The selected item has a cyan-toned filled pill plus weight/contrast change; selection is not communicated by color alone.
- Press indication is bounded and explicitly clipped to each pill. No rectangular overlay, hover magnification, dock scaling, emoji, or top-bar shortcuts duplicating these destinations.
- Bottom list padding includes island height plus navigation-bar inset. On wider layouts the same destinations may become a navigation rail.

## Cards, rows and feedback

- Standard cards use `surface`, a subtle `outline` border and no decorative glow.
- Active route/server uses a mint-tinted elevated surface, leading status rail, check icon and explicit `Active` label.
- Press feedback uses Material state layers in under 100 ms. Content/state transitions use interruptible 180-240 ms fades; reduced-motion removes nonessential movement.
- Loading beyond 300 ms uses a reserved skeleton/progress area to prevent layout shift. Buttons disable and show progress during mutations.
- Modal sheets use the semantic scrim, clear Cancel/Apply actions, field-level errors and confirmation for destructive or network-affecting changes.

## Screen rules

### Overview

Show router health, selected profile, module availability and the active XKeen route without duplicating detailed lists. The profile selector appears only when more than one profile exists.

### Access

Keep peer-management functionality and remove top-level Settings/XKeen icon shortcuts. The bottom island owns top-level navigation; Add peer remains the single screen action.

### Connections

The active subscription server appears once as a highlighted list row. Show a separate `Current route outside subscription` card only when Companion reports that exceptional state. Normalize a leading country flag before presentation so the flag is never duplicated. Preserve subscription order by default. Favorites are an explicit filter, and recent selection is metadata rather than an automatic sort or switch.

### Routes

Group Devices, Direct devices, Direct destinations and XKeen destination exclusions. Online/static/direct states use text plus icon. Protected Companion-managed endpoints are visibly read-only. All writes open a review sheet showing the exact before/after values.

### System

The top-level page is a read-only health and management overview. Call the router service «защищённый доступ» in product copy; `Companion` is an implementation term for technical documentation. Open the complete router/WireGuard/Collector editor through «Расширенные настройки». Keep secrets masked, use semantic form validation and place «Сохранить и проверить» in normal scroll content rather than a floating bottom bar.

### First setup

Ask once for the login and password used to install XKeen/Entware. Keep host/port under progressive disclosure, explain where to find credentials and never show fingerprint, SSH, certificate, token or rollback vocabulary in primary copy. Show four stable product steps, then a dedicated missing-prerequisite, changed-router, success or friendly failure state. Raw identifiers belong only under «Технические подробности».

## Delivery checks

- Test compact 375 px-equivalent phone, landscape, large phone and tablet/expanded width.
- Test largest font scale and TalkBack labels/order; no clipped navigation labels.
- Test gesture and three-button navigation; no content hidden behind the island.
- Check dark contrast independently: primary text >= 4.5:1, secondary and large glyphs >= 3:1.
- Confirm all touch targets >= 48 dp, pressed/disabled/loading states, and reduced-motion behaviour.
