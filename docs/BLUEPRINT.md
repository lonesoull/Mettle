# Mettle — Product Blueprint

Personal training tracker for running, walking, hiking and stairs. Android only, invite-only (under 5 users), zero running cost.

## Decisions
| Area | Choice |
|---|---|
| App | Native Kotlin + Jetpack Compose (Material 3), Android only |
| Target phone | Moto Edge 50 Pro — **no barometer** |
| Local storage | Room (SQLite), offline-first, background sync via WorkManager |
| Backend | Supabase free tier: Auth (invite-only), Postgres + PostGIS, Storage, Realtime |
| Analytics | Computed on-device in Kotlin |
| Look | Light theme, Strava-style orange `#FC4C02`, Inter + Barlow Semi Condensed — see docs/DESIGN.md |
| Data model | docs/DATABASE.md, supabase/migrations |
| Maps | MapLibre Native; OpenFreeMap tiles online; Protomaps PMTiles offline; AWS terrain tiles for elevation/3D |
| Weather | Open-Meteo (fetched after sync, historical API) |
| Builds | GitHub Actions (`.github/workflows/build.yml`), signed with the Mettle release key |
| Distribution | GitHub Releases + in-app updater (no Play Store) |
| Live beacon page | GitHub Pages + Supabase Realtime |
| Upkeep | Scheduled GitHub Actions: daily Supabase keep-alive, weekly DB backup |

## Stairs without a barometer
- Floors from stair-step detection (accelerometer + gyroscope) × steps-per-floor learned from a one-flight calibration walk per building.
- Up vs down classified from cadence and impact pattern; confirm/edit screen after workout.
- Sets auto-detected from rest pauses, plus manual Lap button.
- Stair-machine sub-mode (step-based).
- Hidden "Sensor Lab" mode records labelled raw sensor data for tuning.
- Optional future: BLE pressure sensor.

## All-day tracking (decided 2026-11-20)
- Activity state all day: still / walking / running / stairs up / stairs down / vehicle, with phone placement, via batched motion sensors gated by the hardware step counter.
- Space-time track: best-estimate 3D position (lat, lon, height + accuracy) over time, fusing GPS, network location, dead reckoning and barometer; adaptive sampling (~5–10% battery/day). Indoors: building + floor, not room.
- Barometer phones: floors and lifts detected accurately; others estimate from stair detection.
- Heart rate (BLE strap/watch) and sleep (Health Connect) only appear when a device is connected.
- Group admin reads all members' data; members see only their own. No in-app consent screen (closed group).

## Core rule: best effort with what each phone has (decided 2026-11-22)
No extra hardware (no beacons, no add-on sensors) and no paid map services. On first launch and after system updates the app detects each phone's capabilities and always uses the best available method, silently skipping what is missing:
- Barometer → accurate floors, lifts and elevation; otherwise stair-pattern estimates.
- Wi-Fi RTT → 1–2 m indoor ranging wherever nearby routers support it; otherwise skipped.
- Wi-Fi fingerprints of frequently visited places (existing networks, scan-throttled by Android).
- Dual-frequency GNSS → 1–3 m outdoors; otherwise standard GNSS.
- Android fused location (incl. Google's 3D-building GNSS correction where available) always.
- Hardware step counter / sensor batching → lowest battery; otherwise software fallbacks.
- Gyroscope + magnetometer → better heading, placement and dead reckoning.
- Camera flash → fingertip HR; Bluetooth HR strap/watch and Health Connect → HR and sleep.
A "What this phone can do" screen shows each phone's tier; the admin sees every member's (profiles.phone_capabilities).
AI insights: Gemini (key kept on the server) is used until 80% of the monthly token limit the admin enters (alert at 50%; no cap until a limit is entered); after that, and whenever Google refuses a request, insights come from built-in templates on the phone.
Free map data only: OpenStreetMap (path snapping, building outlines and levels) and AWS terrain tiles.

## Extras (decided 2026-11-21)
1. Day replay — scrub through a day on the map: place, height/floor and activity change as it plays.
2. Sitting vs standing — from thigh angle with the phone in a trouser pocket; standing hours, long-sitting nudges.
3. Lift vs stairs (barometer phones) — "stairs 7 of 9 times today".
4. Auto-learned places — Home / Work / Gym from stays; per-building floor heights remembered.
5. Walking-health trends — walking speed, cadence and step regularity over weeks.
6. Daily records — most steps, most floors, longest walk in a day; streaks.
7. Admin dashboard — live group map (last position, floor, activity, battery), today's stats, tracking health, weekly group report.
9. Arrive/leave alerts — low-power geofences; the admin may watch anyone, members only themselves.
10. Fall detection — impact then stillness → 30 s countdown → alert admin + SMS to emergency contacts with location.
11. SOS button; no-movement-for-X-hours alert — off by default, each person opts in and sets hours and active window.
12. Fingertip heart-rate check — camera + flash, 30 s spot check.
13. Morning readiness — resting HR + sleep (if device) + recent training load → 0–100.
14. Tracking-health report — gaps shown with reason (phone off, battery saver, location off, permission removed, app stopped).

Not included: group challenges.

## Not possible / limited (agreed)
- Room-level indoor position — building + floor only.
- Accurate floors and lift detection without a barometer — motion-based estimate instead.
- Heart rate, HRV, SpO2, blood pressure, skin temperature from the phone alone — need a strap/watch (except the fingertip spot check).
- Sleep stages without a wearable — sleep hidden unless a device is connected.
- Data when the phone is off, left behind, or carried by someone else — shown as gaps.
- Exact calories without heart rate — estimate.
- Second-by-second GPS all day — adaptive tracking instead.

## Release plan (revised 2026-11-21)
1. **Backend + login + sync** — Supabase, admin role, member invites.
2. **All-day tracking** — activity states (incl. sitting/standing), steps, distance, active minutes, space-time track and stays, day replay, tracking-health report, admin live map.
3. **Stairs & places** — Sensor Lab, stair up/down + lift detection, auto-learned places, walking-health trends, daily records.
4. **Safety** — fall detection, SOS, opt-in no-movement alert, arrive/leave alerts, emergency contacts, push notifications.
5. **Workouts** — GPS run/walk/hike with auto-start on runs, stair workouts, activity detail analytics (GAP, splits, best efforts, matched routes), voice coach.
6. **Health** — fingertip heart-rate check, morning readiness, Bluetooth heart-rate straps, Health Connect sleep.
7. **Polish** — feed, kudos/comments, segments, heatmap, exports, Strava import.
8. **iOS** — last.

## Screens (prototype)
Home · Choose mode · Live run · Live stairs · Save workout · Activity detail · Progress
