# KeenWG native design system

**Project:** KeenWG 2.0
**Platform:** Android / Jetpack Compose Material 3  
**Direction:** calm Apple-inspired dark utility; rounded, layered, precise  

## Principles

- Feel premium through spacing, typography, restrained motion and clean hierarchy; do not copy iOS controls literally.
- Use Material Symbols/Compose vector icons for navigation and actions. Country flags remain content, not structural icons.
- Keep one primary action per screen and keep destructive actions separated and confirmed.
- All interactive targets are at least 48 dp with at least 8 dp between neighbouring targets.
- Preserve system font scaling, screen-reader order, predictive back and navigation-bar insets.
- Use near-opaque surfaces instead of continuous blur so lists remain smooth on the router-management phone.

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
- Cards: 24 dp radius. Controls and fields: 18 dp. Primary buttons: capsule.
- Floating navigation: 28-32 dp radius, 12-16 dp side/bottom safe-area margin, 68-72 dp content height.
- Use the Android system sans-serif through Material type roles. Titles 24/28 semibold, section titles 18/24 semibold, body 16/24, supporting text 14/20, labels 12/16 medium.
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

Group router, Collector and Companion connections in rounded sections. Keep secrets masked and use semantic form validation.

## Delivery checks

- Test compact 375 px-equivalent phone, landscape, large phone and tablet/expanded width.
- Test largest font scale and TalkBack labels/order; no clipped navigation labels.
- Test gesture and three-button navigation; no content hidden behind the island.
- Check dark contrast independently: primary text >= 4.5:1, secondary and large glyphs >= 3:1.
- Confirm all touch targets >= 48 dp, pressed/disabled/loading states, and reduced-motion behaviour.
