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

## Release plan
**v1**
1. Run/walk/hike recording: foreground service, GPS filtering, auto-pause, map
2. Stairs mode + Sensor Lab
3. Logins + sync (Supabase)
4. Strava import, best efforts, shoes, goals, tags, GPX/CSV/SQLite export, privacy zones, offline voice coach, in-app updater

**v2** — GAP, histograms, fitness/fatigue/form (session RPE), downhill stress score, matched routes, personal segments + ghost pacer, heatmap, heat-adjusted pace, missed-check-in safety alert, small-group segment leaderboards

**v3** — route builder, live beacon, 3D terrain, BLE heart rate, FIT export

## Screens (prototype)
Home · Choose mode · Live run · Live stairs · Save workout · Activity detail · Progress
