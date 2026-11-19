# Mettle — Database design

Two copies of the same data model:

| Where | Technology | Job |
|---|---|---|
| Phone | Room (SQLite) — `app/src/main/java/app/mettle/data/db` | Everything works offline. Workouts are saved here first. |
| Server | Supabase Postgres + PostGIS — `supabase/migrations` | Logins, backup, sync between devices, the group feed. |

## Rules that keep sync simple
- **IDs are made on the phone** (UUIDs), so an offline workout keeps the same id after it uploads.
- **`updated_at` + last write wins** decides conflicts; **`deleted_at`** is a soft delete so deletions sync too.
- **Per-second data is a file, not rows.** GPS points, altitude, steps and stair events are packed into one gzip JSON file per activity (Supabase Storage bucket `streams`, path `{user_id}/{activity_id}.json.gz`). Tables hold only summaries — this keeps us inside the free tier for years.
- **Privacy is enforced by the server**, not the app: row-level security means a member can only read their own rows; others see your workouts only through the `feed_activities` view, which shows the privacy-trimmed route (`route_public`) and never your raw data.

## Tables

| Table | One row per | Key contents |
|---|---|---|
| `profiles` | member | name, avatar, units, weight (for calories) |
| `activities` | workout | type (run/walk/hike/stairs), sub-type (outdoor/treadmill/stairwell/stair machine), times, distance, climb/descent, floors up/down, pace, GAP, heat-adjusted pace, cadence, RPE, training load, downhill stress, weather, shoes, building, matched route, visibility, route |
| `laps` | km split, manual lap or stair set | start/end, distance, floors up/down, climb time, descent time, rest |
| `best_efforts` | best effort found inside a workout | effort name (`run_5k`, `stairs_up_10fl`…), time, where in the workout, rank when set |
| `gear` | pair of shoes/boots | name, starting km, retire-at km, default for which modes |
| `places` | building used for stairs | floor height, **steps per floor (from the calibration walk)** |
| `privacy_zones` | hidden circle (e.g. home) | centre, radius |
| `routes` | a regular loop | fingerprint for matching, shape, distance |
| `tags` / `activity_tags` | tag / tag on a workout | `#fasted`, `#weighted-vest`, … |
| `goals` | weekly/monthly/yearly goal | metric (distance, time, climb, floors, count), target |
| `segments` / `segment_efforts` | personal segment / attempt on it | road, trail or stair segment; shared ones form small-group leaderboards |
| `kudos`, `comments` | social interaction | within the group only |
| `sensor_lab_recordings` | labelled raw sensor recording | used to tune stair detection on phones without a barometer |

Phone-only table: `recording_samples` — a crash-safe buffer written every second during a workout, compacted into the streams file when you press Save.

## Per-second stream file (version 1)
```json
{ "v": 1, "t": [0,1,2,...],            // seconds from start
  "lat": [...], "lon": [...], "acc": [...],   // GPS (null indoors)
  "alt": [...], "alt_dem": [...],             // raw GPS altitude, terrain-corrected altitude
  "dist": [...], "speed": [...], "cad": [...], "steps": [...],
  "moving": [1,1,0,...],                      // auto-pause mask
  "floors": [{"t": 312, "dir": "up", "conf": 0.93}, ...],
  "hr": null }                                // reserved for a Bluetooth strap later
```

## Setting up the server (done once, when we reach release 3)
1. Create a free Supabase project.
2. Run `supabase/migrations/20261119000000_init.sql` in its SQL editor.
3. Authentication → turn **off** "Allow new users to sign up"; invite each member by email.

The migration has been tested against Postgres 16 + PostGIS, including the privacy rules (a second member sees your group workouts in the feed, cannot see private ones, and cannot read your raw rows).
