-- Mettle — all-day activity tracking + group admin
-- Run after 20261119000000_init.sql.
--
-- * The group admin can READ every member's data (never edit it).
-- * Each member still sees only their own data (plus shared workouts in the feed).
-- * Members must accept that the admin can see their data before syncing
--   (profiles.admin_visibility_accepted_at is set by the app's consent screen).

create type member_role     as enum ('admin', 'member');
create type day_state       as enum ('still', 'walking', 'running', 'stairs_up', 'stairs_down',
                                     'vehicle', 'cycling', 'unknown');
create type phone_placement as enum ('pocket', 'hand', 'bag', 'armband', 'table', 'unknown');

alter table profiles
  add column role                          member_role not null default 'member',
  add column height_cm                     smallint check (height_cm between 100 and 230),
  add column step_goal                     integer not null default 8000 check (step_goal between 1000 and 100000),
  add column all_day_tracking              boolean not null default true,
  add column admin_visibility_accepted_at  timestamptz;

alter table activities
  add column auto_detected boolean not null default false;   -- logged by all-day tracking, not by pressing Start

-- One row per member per day: the totals shown on Home.
create table daily_summaries (
  user_id           uuid not null references profiles(id) on delete cascade,
  day               date not null,                 -- the member's local date
  timezone          text not null,
  steps             integer not null default 0,
  distance_m        integer not null default 0,    -- estimated from steps × stride (height-based)
  active_min        smallint not null default 0,
  walking_min       smallint not null default 0,
  running_min       smallint not null default 0,
  still_min         smallint not null default 0,
  vehicle_min       smallint not null default 0,
  floors_up         smallint not null default 0,   -- detected stair climbing (estimate, no barometer)
  floors_down       smallint not null default 0,
  active_calories   smallint not null default 0,
  updated_at        timestamptz not null default now(),
  primary key (user_id, day)
);
create trigger daily_summaries_updated before update on daily_summaries for each row execute function set_updated_at();

-- The day timeline: consecutive periods of one state.
create table day_segments (
  id            uuid primary key,                  -- made on the phone
  user_id       uuid not null references profiles(id) on delete cascade,
  start_at      timestamptz not null,
  end_at        timestamptz not null check (end_at >= start_at),
  state         day_state not null,
  steps         integer not null default 0,
  floors        smallint not null default 0,
  confidence    real check (confidence between 0 and 1),
  placement     phone_placement not null default 'unknown',
  activity_id   uuid references activities(id) on delete set null,   -- if it became an auto-detected activity
  created_at    timestamptz not null default now()
);
create index day_segments_user_time on day_segments(user_id, start_at desc);

-- ---------------------------------------------------------------------------
-- Admin helper. security definer so it can read profiles without recursion.
-- ---------------------------------------------------------------------------
create or replace function is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from profiles where id = auth.uid() and role = 'admin')
$$;

-- Members cannot promote themselves: only an existing admin (or the dashboard) changes roles.
create or replace function protect_role() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  -- auth.uid() is null in the Supabase SQL editor, which is how the first admin is set.
  if new.role is distinct from old.role and auth.uid() is not null and not is_admin() then
    raise exception 'only the admin can change roles';
  end if;
  return new;
end $$;
create trigger profiles_protect_role before update on profiles for each row execute function protect_role();

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
alter table daily_summaries enable row level security;
alter table day_segments    enable row level security;

create policy daily_summaries_owner on daily_summaries for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy day_segments_owner on day_segments for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Admin: read-only access to every member's rows.
do $$
declare t text;
begin
  foreach t in array array['activities','laps','best_efforts','gear','places','routes','tags',
                           'goals','daily_summaries','day_segments','sensor_lab_recordings']
  loop
    execute format('create policy %1$s_admin_read on %1$I for select to authenticated using (is_admin())', t);
  end loop;
end $$;

create policy activity_tags_admin_read on activity_tags for select to authenticated using (is_admin());
create policy storage_admin_read on storage.objects for select to authenticated
  using (bucket_id in ('streams', 'sensorlab') and is_admin());

-- Make yourself admin (run once in the SQL editor, with your login email):
--   update profiles set role = 'admin'
--   where id = (select id from auth.users where email = 'YOUR-EMAIL');
