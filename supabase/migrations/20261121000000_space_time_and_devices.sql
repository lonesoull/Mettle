-- Mettle — all-day space-time tracking, external heart-rate/sleep devices
-- Run after 20261120000000_all_day_and_admin.sql.
--
-- Closed group decision: no in-app consent screen. Android's own permission
-- prompts and the persistent tracking notification still apply.

alter table profiles drop column if exists admin_visibility_accepted_at;
alter table profiles add column location_tracking boolean not null default true;

create type position_source as enum ('gps', 'network', 'dead_reckoning', 'mixed');

-- One compressed file per member per day holds the full 3D track:
-- {"v":1,"t":[epoch_s...],"lat":[...],"lon":[...],"alt":[...],"h_acc":[...],
--  "v_acc":[...],"floor":[...],"src":["gps"|"network"|"dr"...],"state":[...]}
create table day_tracks (
  user_id      uuid not null references profiles(id) on delete cascade,
  day          date not null,
  timezone     text not null,
  file_path    text not null,                          -- "tracks" bucket: {user_id}/{day}.json.gz
  points       integer not null default 0,
  bbox         geography(polygon, 4326),
  distance_m   integer not null default 0,
  updated_at   timestamptz not null default now(),
  primary key (user_id, day)
);
create trigger day_tracks_updated before update on day_tracks for each row execute function set_updated_at();

-- Stays: where someone was, from when to when (the readable timeline on the map).
create table stays (
  id            uuid primary key,                      -- made on the phone
  user_id       uuid not null references profiles(id) on delete cascade,
  start_at      timestamptz not null,
  end_at        timestamptz not null check (end_at >= start_at),
  location      geography(point, 4326) not null,
  altitude_m    numeric(7,1),
  floor         smallint,                              -- estimated floor (barometer or detected stairs)
  accuracy_m    real,
  place_id      uuid references places(id) on delete set null,
  label         text,                                  -- e.g. "Home", "Office Block B"
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index stays_user_time on stays(user_id, start_at desc);
create trigger stays_updated before update on stays for each row execute function set_updated_at();

-- Paired Bluetooth / Health Connect sources. Heart-rate and sleep features stay
-- hidden in the app until a member has at least one active device here.
create type device_kind as enum ('ble_heart_rate', 'health_connect');
create table devices (
  id            uuid primary key,
  user_id       uuid not null references profiles(id) on delete cascade,
  kind          device_kind not null,
  name          text not null,                         -- e.g. "Polar H10", "Samsung Health"
  address       text,                                  -- BLE MAC (null for Health Connect)
  provides_hr   boolean not null default false,
  provides_sleep boolean not null default false,
  last_seen_at  timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz
);
create trigger devices_updated before update on devices for each row execute function set_updated_at();

create table sleep_sessions (
  id            uuid primary key,
  user_id       uuid not null references profiles(id) on delete cascade,
  device_id     uuid references devices(id) on delete set null,
  start_at      timestamptz not null,
  end_at        timestamptz not null check (end_at > start_at),
  asleep_min    smallint,
  deep_min      smallint,
  light_min     smallint,
  rem_min       smallint,
  awake_min     smallint,
  resting_hr    smallint,
  created_at    timestamptz not null default now()
);
create index sleep_user_time on sleep_sessions(user_id, start_at desc);

-- All-day heart rate from a connected device, one row per member per day.
alter table daily_summaries
  add column resting_hr  smallint,
  add column avg_hr      smallint,
  add column max_hr      smallint,
  add column hr_file     text;                          -- "tracks" bucket: {user_id}/{day}.hr.json.gz

-- Row-level security: owner full access, admin read-only.
alter table day_tracks     enable row level security;
alter table stays          enable row level security;
alter table devices        enable row level security;
alter table sleep_sessions enable row level security;

do $$
declare t text;
begin
  foreach t in array array['day_tracks','stays','devices','sleep_sessions']
  loop
    execute format('create policy %1$s_owner on %1$I for all to authenticated
                    using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
    execute format('create policy %1$s_admin_read on %1$I for select to authenticated using (is_admin())', t);
  end loop;
end $$;

insert into storage.buckets (id, name, public) values ('tracks', 'tracks', false)
on conflict (id) do nothing;

create policy storage_tracks_own on storage.objects for all to authenticated
  using (bucket_id = 'tracks' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'tracks' and (storage.foldername(name))[1] = auth.uid()::text);
create policy storage_tracks_admin_read on storage.objects for select to authenticated
  using (bucket_id = 'tracks' and is_admin());
