# Mettle — Design system

Prototype: the "Mettle App Prototype" canvas (7 screens, real OpenStreetMap renders).

## Colour — one meaning each
| Token | Hex | Used for |
|---|---|---|
| Orange | `#FC4C02` | Brand, primary buttons, you, climbing/up, your route, pace line |
| Pace scale | `#FDC29C → #FD8A4B → #E04400 → #A33100` | Route/chart colouring, slow → fast |
| Blue | `#2563EB` | Descending/down, live GPS dot — nothing else |
| Gold | `#C99700` (tint `#FFF1C7`) | Personal records, achievements |
| Green | `#16A34A` | Fresh, ahead of ghost, improvements |
| Amber | `#D97706` | Warnings only: tired, worn shoes, slow alerts |
| Ink | `#16161A` | Text, fitness line, ghost runner, secondary data |
| Greys | `#3F3F46` `#5F5F68` `#8A8A93` `#ECECEF` `#F2F2F4` | Secondary text, dividers, section bands |

Light theme: white sections separated by 8 dp grey bands; no boxed cards.

## Type
- **Inter** (400–800) for all UI text.
- **Barlow Semi Condensed** (600/700) for big numbers; always tabular digits.
- Both bundled in the APK (offline), both SIL Open Font License.

## Motion
Charts draw in (≈1.4 s ease-out), bars grow, hero numbers count up (≈0.9 s), GPS dot pulses, recording indicator blinks, bottom sheets slide up. Route replay animates a dot along the route.

## Maps
MapLibre with OpenFreeMap vector tiles, restyled: background `#F3F1ED`, white roads with light casings, parks `#D7E8CC`, water `#AFD3EE`, muted labels, POI icons hidden. Route = orange line on a white casing. Heatmap = dark style with orange glow.
