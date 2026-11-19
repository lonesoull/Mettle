-- Mettle — initial server schema (Supabase / Postgres + PostGIS)
-- Invite-only: public sign-ups are disabled in Supabase Auth settings; the owner
-- invites each member by email from the Supabase dashboard.
--
-- Conventions
--   * Every user-owned row carries user_id and is protected by row-level security.
--   * Primary keys are UUIDs generated ON THE PHONE, so workouts recorded offline
--     keep the same id after sync.
--   * updated_at drives last-write-wins sync; deleted_at is a soft delete so
--     deletions sync too.
--   * Heavy per-second data (GPS, sensors) is NOT stored in tables: it lives as a
--     compressed file in the "streams" storage bucket, one file per activity.

create extension if not exists postgis;

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------
create type activity_type   as enum ('run', 'walk', 'hike', 'stairs');
create type activity_sub    as enum ('outdoor', 'treadmill', 'stairwell', 'stair_machine');
create type visibility      as enum ('private', 'group');
create type lap_kind        as enum ('auto_km', 'manual', 'stair_set');
create type gear_kind       as enum ('shoes', 'boots', 'other');
create type goal_metric     as enum ('distance_m', 'moving_s', 'elev_gain_m', 'floors_up', 'activities');
create type goal_period     as enum ('week', 'month', 'year');
create type activity_source as enum ('mettle', 'strava_import', 'gpx_import');

-- ---------------------------------------------------------------------------
-- Shared trigger: keep updated_at current
-- ---------------------------------------------------------------------------
create or replace function set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- Profiles (1:1 with auth.users)
-- ---------------------------------------------------------------------------
create table profiles (
  id              uuid primary key references auth.users(id) on delete cascade,
  display_name    text not null check (char_length(display_name) between 1 and 40),
  avatar_path     text,                                   -- storage path in "avatars"
  units           text not null default 'metric' check (units in ('metric', 'imperial')),
  weight_kg       numeric(5,1) check (weight_kg between 20 and 300),
  max_hr          smallint check (max_hr between 100 and 230),   -- for future BLE strap
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create trigger profiles_updated before update on profiles for each row execute function set_updated_at();

-- Auto-create a profile when an invited user first signs in.
create or replace function handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into profiles (id, display_name)
  values (new.id, coalesce(split_part(new.email, '@', 1), 'Athlete'));
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function handle_new_user();

-- ---------------------------------------------------------------------------
-- Gear (shoes / boots) with mileage
-- ---------------------------------------------------------------------------
create table gear (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  kind            gear_kind not null default 'shoes',
  name            text not null,
  start_distance_m integer not null default 0,           -- km already on them when added
  retire_at_m     integer not null default 700000,        -- warn at this distance
  default_for     activity_type[] not null default '{}',  -- auto-select for these types
  retired_at      timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create index gear_user on gear(user_id);
create trigger gear_updated before update on gear for each row execute function set_updated_at();

-- ---------------------------------------------------------------------------
-- Places: calibrated buildings for stair workouts
-- ---------------------------------------------------------------------------
create table places (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  name            text not null,
  location        geography(point, 4326),
  floor_height_m  numeric(4,2) not null default 3.0 check (floor_height_m between 2 and 6),
  steps_per_floor smallint check (steps_per_floor between 6 and 60),  -- learned by calibration walk
  floors_total    smallint,
  calibrated_at   timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create index places_user on places(user_id);
create trigger places_updated before update on places for each row execute function set_updated_at();

-- ---------------------------------------------------------------------------
-- Privacy zones: start/end inside these circles is hidden from others
-- ---------------------------------------------------------------------------
create table privacy_zones (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  center          geography(point, 4326) not null,
  radius_m        integer not null default 300 check (radius_m between 100 and 2000),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create trigger privacy_zones_updated before update on privacy_zones for each row execute function set_updated_at();

-- ---------------------------------------------------------------------------
-- Matched routes ("your regular loop")
-- ---------------------------------------------------------------------------
create table routes (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  name            text not null,
  fingerprint     text not null,                          -- geohash sequence for fast matching
  geometry        geography(linestring, 4326) not null,
  distance_m      integer not null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create index routes_user on routes(user_id);
create trigger routes_updated before update on routes for each row execute function set_updated_at();

-- ---------------------------------------------------------------------------
-- Activities: one row per workout (summary only)
-- ---------------------------------------------------------------------------
create table activities (
  id                  uuid primary key,
  user_id             uuid not null references profiles(id) on delete cascade,
  type                activity_type not null,
  sub_type            activity_sub not null default 'outdoor',
  title               text not null,
  description         text,
  started_at          timestamptz not null,
  timezone            text not null,                       -- e.g. Asia/Kolkata
  elapsed_s           integer not null check (elapsed_s >= 0),
  moving_s            integer not null check (moving_s >= 0),
  distance_m          integer not null default 0,
  elev_gain_m         numeric(7,1) not null default 0,
  elev_loss_m         numeric(7,1) not null default 0,
  floors_up           smallint not null default 0,
  floors_down         smallint not null default 0,
  avg_pace_s_km       numeric(6,1),
  gap_s_km            numeric(6,1),                        -- grade-adjusted pace
  heat_adj_pace_s_km  numeric(6,1),
  avg_cadence_spm     smallint,
  calories            smallint,
  rpe                 smallint check (rpe between 1 and 10),
  training_load       smallint,                            -- rpe × moving minutes
  downhill_stress     smallint,
  weather             jsonb,                               -- {temp_c, humidity, wind_ms, source}
  gear_id             uuid references gear(id) on delete set null,
  place_id            uuid references places(id) on delete set null,
  route_id            uuid references routes(id) on delete set null,
  visibility          visibility not null default 'group',
  route               geography(linestring, 4326),         -- simplified full route (owner only)
  route_public        geography(linestring, 4326),         -- privacy zones removed (shown to group)
  streams_path        text,                                -- "streams" bucket: {user_id}/{id}.json.gz
  streams_version     smallint not null default 1,
  source              activity_source not null default 'mettle',
  device              text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  deleted_at          timestamptz
);
create index activities_user_time on activities(user_id, started_at desc);
create index activities_feed on activities(started_at desc) where deleted_at is null and visibility = 'group';
create index activities_updated on activities(user_id, updated_at);
create trigger activities_updated before update on activities for each row execute function set_updated_at();

-- Laps, auto km splits and stair sets
create table laps (
  id                uuid primary key,
  activity_id       uuid not null references activities(id) on delete cascade,
  user_id           uuid not null references profiles(id) on delete cascade,
  idx               smallint not null,
  kind              lap_kind not null,
  start_offset_s    integer not null,
  end_offset_s      integer not null,
  distance_m        integer not null default 0,
  moving_s          integer not null default 0,
  elev_gain_m       numeric(6,1) not null default 0,
  floors_up         smallint not null default 0,
  floors_down       smallint not null default 0,
  climb_s           integer,                               -- stairs: time going up
  descent_s         integer,                               -- stairs: time going down
  rest_s            integer,
  unique (activity_id, kind, idx)
);
create index laps_activity on laps(activity_id);

-- Best efforts found inside each activity (sliding window)
create table best_efforts (
  id                uuid primary key,
  user_id           uuid not null references profiles(id) on delete cascade,
  activity_id       uuid not null references activities(id) on delete cascade,
  effort            text not null,                         -- 'run_400m','run_1k','run_5k','stairs_up_10fl','stairs_down_15fl',...
  time_s            integer not null,
  start_offset_s    integer not null,
  end_offset_s      integer not null,
  rank_at_time      smallint,                              -- 1 = PR when recorded
  achieved_at       timestamptz not null
);
create index best_efforts_lookup on best_efforts(user_id, effort, time_s);

-- Tags
create table tags (
  id          uuid primary key,
  user_id     uuid not null references profiles(id) on delete cascade,
  name        text not null check (name ~ '^[a-z0-9-]{1,30}$'),
  unique (user_id, name)
);
create table activity_tags (
  activity_id uuid not null references activities(id) on delete cascade,
  tag_id      uuid not null references tags(id) on delete cascade,
  primary key (activity_id, tag_id)
);

-- Goals
create table goals (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  metric          goal_metric not null,
  period          goal_period not null,
  target          integer not null check (target > 0),
  activity_types  activity_type[] not null default '{run,walk,hike,stairs}',
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create trigger goals_updated before update on goals for each row execute function set_updated_at();

-- Personal segments (also used for small-group leaderboards)
create table segments (
  id              uuid primary key,
  user_id         uuid not null references profiles(id) on delete cascade,
  name            text not null,
  type            activity_type not null,
  geometry        geography(linestring, 4326),             -- outdoor segments
  place_id        uuid references places(id) on delete cascade,  -- stair segments
  floor_from      smallint,
  floor_to        smallint,
  distance_m      integer,
  shared          boolean not null default true,           -- visible to the group
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
create trigger segments_updated before update on segments for each row execute function set_updated_at();

create table segment_efforts (
  id              uuid primary key,
  segment_id      uuid not null references segments(id) on delete cascade,
  activity_id     uuid not null references activities(id) on delete cascade,
  user_id         uuid not null references profiles(id) on delete cascade,
  elapsed_s       integer not null,
  start_offset_s  integer not null,
  achieved_at     timestamptz not null
);
create index segment_efforts_board on segment_efforts(segment_id, elapsed_s);

-- Social: kudos and comments within the group
create table kudos (
  activity_id uuid not null references activities(id) on delete cascade,
  user_id     uuid not null references profiles(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (activity_id, user_id)
);
create table comments (
  id          uuid primary key default gen_random_uuid(),
  activity_id uuid not null references activities(id) on delete cascade,
  user_id     uuid not null references profiles(id) on delete cascade,
  body        text not null check (char_length(body) between 1 and 1000),
  created_at  timestamptz not null default now(),
  deleted_at  timestamptz
);
create index comments_activity on comments(activity_id, created_at);

-- Sensor Lab: labelled raw recordings used to tune stair detection
create table sensor_lab_recordings (
  id          uuid primary key,
  user_id     uuid not null references profiles(id) on delete cascade,
  device      text not null,
  labels      jsonb not null,                              -- [{t_start, t_end, label: 'up'|'down'|'flat'|'rest'}]
  file_path   text not null,                               -- "sensorlab" bucket
  notes       text,
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Group feed view: what other members may see (no private route, no streams)
-- ---------------------------------------------------------------------------
create view feed_activities with (security_invoker = false) as
select a.id, a.user_id, p.display_name, p.avatar_path,
       a.type, a.sub_type, a.title, a.description, a.started_at, a.timezone,
       a.elapsed_s, a.moving_s, a.distance_m, a.elev_gain_m, a.floors_up, a.floors_down,
       a.avg_pace_s_km, a.route_public, a.created_at,
       (select count(*) from kudos k where k.activity_id = a.id)          as kudos_count,
       (select count(*) from comments c where c.activity_id = a.id and c.deleted_at is null) as comment_count
from activities a
join profiles p on p.id = a.user_id
where a.deleted_at is null
  and (a.visibility = 'group' or a.user_id = auth.uid())
  and auth.uid() is not null;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
alter table profiles              enable row level security;
alter table gear                  enable row level security;
alter table places                enable row level security;
alter table privacy_zones         enable row level security;
alter table routes                enable row level security;
alter table activities            enable row level security;
alter table laps                  enable row level security;
alter table best_efforts          enable row level security;
alter table tags                  enable row level security;
alter table activity_tags         enable row level security;
alter table goals                 enable row level security;
alter table segments              enable row level security;
alter table segment_efforts       enable row level security;
alter table kudos                 enable row level security;
alter table comments              enable row level security;
alter table sensor_lab_recordings enable row level security;

-- Group members can see each other's names and avatars; only you edit yours.
create policy profiles_read  on profiles for select to authenticated using (true);
create policy profiles_write on profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- Owner-only tables: full access to your own rows, nothing else.
do $$
declare t text;
begin
  foreach t in array array['gear','places','privacy_zones','routes','activities','laps',
                           'best_efforts','tags','goals','sensor_lab_recordings']
  loop
    execute format('create policy %1$s_owner on %1$I for all to authenticated
                    using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

create policy activity_tags_owner on activity_tags for all to authenticated
  using (exists (select 1 from activities a where a.id = activity_id and a.user_id = auth.uid()))
  with check (exists (select 1 from activities a where a.id = activity_id and a.user_id = auth.uid()));

-- Shared segments and their efforts form the group leaderboards.
create policy segments_read  on segments for select to authenticated using (shared or user_id = auth.uid());
create policy segments_write on segments for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy segment_efforts_read on segment_efforts for select to authenticated
  using (exists (select 1 from segments s where s.id = segment_id and (s.shared or s.user_id = auth.uid())));
create policy segment_efforts_write on segment_efforts for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Kudos and comments: anyone in the group, on activities they can see.
create policy kudos_read on kudos for select to authenticated using (true);
create policy kudos_give on kudos for insert to authenticated with check (user_id = auth.uid());
create policy kudos_take on kudos for delete to authenticated using (user_id = auth.uid());
create policy comments_read  on comments for select to authenticated using (deleted_at is null);
create policy comments_write on comments for insert to authenticated with check (user_id = auth.uid());
create policy comments_edit  on comments for update to authenticated using (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Storage buckets (private). Path convention: {user_id}/{file}
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public) values
  ('streams', 'streams', false),
  ('avatars', 'avatars', false),
  ('sensorlab', 'sensorlab', false)
on conflict (id) do nothing;

create policy storage_own_files on storage.objects for all to authenticated
  using (bucket_id in ('streams','sensorlab','avatars') and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id in ('streams','sensorlab','avatars') and (storage.foldername(name))[1] = auth.uid()::text);
create policy storage_avatars_read on storage.objects for select to authenticated
  using (bucket_id = 'avatars');
