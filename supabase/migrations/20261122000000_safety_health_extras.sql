-- Mettle — extras: sitting/standing, lifts, learned places, walking-health trends,
-- admin live map, arrive/leave alerts, fall detection, SOS, no-movement alert,
-- fingertip heart-rate checks, morning readiness, tracking-health report.
-- Run after 20261121000000_space_time_and_devices.sql.

-- ---------------------------------------------------------------------------
-- Finer activity states
-- ---------------------------------------------------------------------------
alter type day_state add value if not exists 'sitting';
alter type day_state add value if not exists 'standing';
alter type day_state add value if not exists 'lift_up';
alter type day_state add value if not exists 'lift_down';

alter table daily_summaries
  add column sitting_min          smallint not null default 0,
  add column standing_min         smallint not null default 0,
  add column standing_hours       smallint not null default 0,   -- hours with ≥1 min standing/moving
  add column stairs_trips         smallint not null default 0,   -- vertical trips taken by stairs
  add column lift_trips           smallint not null default 0,   -- … and by lift (barometer phones)
  add column walking_speed_ms     numeric(4,2),                  -- walking-health trends
  add column walking_cadence_spm  smallint,
  add column step_regularity      numeric(3,2) check (step_regularity between 0 and 1),
  add column readiness            smallint check (readiness between 0 and 100),
  add column tracked_min          smallint not null default 0;   -- minutes with live tracking (for the gap report)

-- ---------------------------------------------------------------------------
-- Auto-learned places (Home / Work / Gym …) reuse the places table
-- ---------------------------------------------------------------------------
create type place_kind as enum ('home', 'work', 'gym', 'building', 'other');
alter table places
  add column kind          place_kind not null default 'other',
  add column radius_m      smallint not null default 75 check (radius_m between 20 and 1000),
  add column auto_learned  boolean not null default false,
  add column visits        integer not null default 0;

-- ---------------------------------------------------------------------------
-- Live status per member: powers the admin live map and tracking health
-- ---------------------------------------------------------------------------
create table member_status (
  user_id        uuid primary key references profiles(id) on delete cascade,
  location       geography(point, 4326),
  altitude_m     numeric(7,1),
  floor          smallint,
  accuracy_m     real,
  state          day_state,
  battery_pct    smallint check (battery_pct between 0 and 100),
  charging       boolean,
  tracking_ok    boolean not null default true,
  problem        text,                                 -- e.g. 'battery_saver', 'location_off'
  updated_at     timestamptz not null default now()
);
create trigger member_status_updated before update on member_status for each row execute function set_updated_at();

create type gap_reason as enum ('phone_off', 'battery_saver', 'location_off', 'permission_removed',
                                'app_stopped', 'tracking_paused', 'unknown');
create table tracking_gaps (
  id         uuid primary key,
  user_id    uuid not null references profiles(id) on delete cascade,
  start_at   timestamptz not null,
  end_at     timestamptz,
  reason     gap_reason not null default 'unknown'
);
create index tracking_gaps_user on tracking_gaps(user_id, start_at desc);

-- ---------------------------------------------------------------------------
-- Safety settings, emergency contacts, alert rules and alerts
-- ---------------------------------------------------------------------------
alter table profiles
  add column fall_detection         boolean not null default true,
  add column no_movement_alert      boolean not null default false,   -- each person opts in
  add column no_movement_hours      smallint not null default 4 check (no_movement_hours between 1 and 24),
  add column no_movement_from       time not null default '07:00',    -- only alert inside this window
  add column no_movement_to         time not null default '22:00',
  add column push_token             text;                             -- for alert notifications

create table emergency_contacts (
  id          uuid primary key,
  user_id     uuid not null references profiles(id) on delete cascade,
  name        text not null,
  phone       text not null,
  notify_sms  boolean not null default true,
  created_at  timestamptz not null default now()
);

-- Arrive/leave rules: "tell me when Asha arrives at Home".
create table geofence_rules (
  id           uuid primary key,
  owner_id     uuid not null references profiles(id) on delete cascade,  -- who gets notified
  subject_id   uuid not null references profiles(id) on delete cascade,  -- whose movement
  place_id     uuid not null references places(id) on delete cascade,
  on_arrive    boolean not null default true,
  on_leave     boolean not null default false,
  active       boolean not null default true,
  created_at   timestamptz not null default now()
);

create type alert_kind   as enum ('fall', 'sos', 'no_movement', 'arrive', 'leave', 'tracking_lost');
create type alert_status as enum ('pending', 'cancelled', 'sent', 'acknowledged');
create table alerts (
  id            uuid primary key,
  user_id       uuid not null references profiles(id) on delete cascade,  -- whom it is about
  kind          alert_kind not null,
  status        alert_status not null default 'pending',
  location      geography(point, 4326),
  floor         smallint,
  detail        jsonb,                                  -- e.g. impact g-force, place name
  created_at    timestamptz not null default now(),
  acknowledged_by uuid references profiles(id),
  acknowledged_at timestamptz
);
create index alerts_recent on alerts(created_at desc);

-- ---------------------------------------------------------------------------
-- Fingertip heart-rate spot checks (camera + flash)
-- ---------------------------------------------------------------------------
create table hr_spot_checks (
  id          uuid primary key,
  user_id     uuid not null references profiles(id) on delete cascade,
  taken_at    timestamptz not null,
  bpm         smallint not null check (bpm between 30 and 220),
  quality     real check (quality between 0 and 1),
  context     text check (context in ('morning', 'rest', 'post_workout', 'other'))
);
create index hr_spot_user on hr_spot_checks(user_id, taken_at desc);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
alter table member_status      enable row level security;
alter table tracking_gaps      enable row level security;
alter table emergency_contacts enable row level security;
alter table geofence_rules     enable row level security;
alter table alerts             enable row level security;
alter table hr_spot_checks     enable row level security;

do $$
declare t text;
begin
  foreach t in array array['member_status','tracking_gaps','emergency_contacts','alerts','hr_spot_checks']
  loop
    execute format('create policy %1$s_owner on %1$I for all to authenticated
                    using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
    execute format('create policy %1$s_admin_read on %1$I for select to authenticated using (is_admin())', t);
  end loop;
end $$;

-- The admin can acknowledge anyone's alert.
create policy alerts_admin_ack on alerts for update to authenticated using (is_admin());

-- Geofence rules: the admin may watch anyone; members may watch only themselves.
create policy geofence_rules_rw on geofence_rules for all to authenticated
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid() and (subject_id = auth.uid() or is_admin()));
create policy geofence_rules_subject_read on geofence_rules for select to authenticated
  using (subject_id = auth.uid());   -- the subject's phone must read the rules to evaluate them

-- Live map updates stream to the admin's app.
do $$ begin
  alter publication supabase_realtime add table member_status, alerts;
exception when undefined_object then null;   -- publication exists only on Supabase itself
end $$;
