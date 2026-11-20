-- Mettle — Gemini (AI insights) token metering with automatic fallback.
-- Run after 20261123000000_phone_capabilities.sql.
--
-- Maps never use Google: they are OpenStreetMap only, so they need no metering.
-- * The Gemini edge function reports tokens via record_api_usage('gemini', n).
-- * Limit defaults to 700,000 tokens/month and is editable by the admin.
-- * At warn_at (400,000) the admin gets an alert; at 80% of the limit
--   (560,000 by default) insights switch to built-in text templates for the
--   rest of the month. A new month switches Gemini back on automatically.

alter type alert_kind add value if not exists 'api_usage';

create table app_config (
  provider      text primary key check (provider in ('gemini')),
  enabled       boolean not null default true,     -- false = use the free fallback
  hard_limit    bigint  not null default 700000 check (hard_limit > 0),
  warn_at       bigint  not null default 400000,
  stop_ratio    numeric(3,2) not null default 0.80 check (stop_ratio > 0 and stop_ratio <= 1),
  switch_at     bigint  generated always as (floor(hard_limit * stop_ratio)::bigint) stored,
  switched_month date,                             -- month Gemini was switched off
  warned_month  date,                              -- month the 400k alert was sent
  switched_at   timestamptz,
  updated_at    timestamptz not null default now()
);
insert into app_config (provider) values ('gemini');

create table api_usage (
  month     date   not null,                       -- first day of the month (UTC)
  provider  text   not null references app_config(provider),
  user_key  text   not null,                       -- 'server' for the Gemini function (or a member id)
  units     bigint not null default 0,             -- tokens
  primary key (month, provider, user_key)
);

create or replace function api_usage_this_month(p_provider text) returns bigint
language sql stable security definer set search_path = public as $$
  select coalesce(sum(units), 0) from api_usage
  where provider = p_provider and month = date_trunc('month', now() at time zone 'utc')::date
$$;

-- Adds usage, then warns / switches when thresholds are crossed.
-- Returns whether the Google provider may still be used.
create or replace function record_api_usage(p_provider text, p_units bigint)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  m     date := date_trunc('month', now() at time zone 'utc')::date;
  cfg   app_config;
  total bigint;
  admin uuid;
begin
  if p_units < 0 or p_units > 100000 then
    raise exception 'invalid usage amount';
  end if;

  insert into api_usage (month, provider, user_key, units)
  values (m, p_provider, coalesce(auth.uid()::text, 'server'), p_units)
  on conflict (month, provider, user_key) do update set units = api_usage.units + excluded.units;

  select * into cfg from app_config where provider = p_provider for update;
  total := api_usage_this_month(p_provider);
  select id into admin from profiles where role = 'admin' limit 1;

  if total >= cfg.warn_at and (cfg.warned_month is null or cfg.warned_month < m) then
    update app_config set warned_month = m, updated_at = now() where provider = p_provider;
    if admin is not null then
      insert into alerts (id, user_id, kind, detail)
      values (gen_random_uuid(), admin, 'api_usage',
              jsonb_build_object('provider', p_provider, 'used', total, 'level', 'warning',
                                 'message', p_provider || ' passed ' || cfg.warn_at || ' this month'));
    end if;
  end if;

  -- New month: switch back on automatically.
  if not cfg.enabled and cfg.switched_month is not null and cfg.switched_month < m then
    update app_config set enabled = true, switched_month = null, updated_at = now() where provider = p_provider;
    cfg.enabled := true;
  end if;

  if cfg.enabled and total >= cfg.switch_at then
    update app_config set enabled = false, switched_at = now(), switched_month = m, updated_at = now() where provider = p_provider;
    if admin is not null then
      insert into alerts (id, user_id, kind, detail)
      values (gen_random_uuid(), admin, 'api_usage',
              jsonb_build_object('provider', p_provider, 'used', total, 'level', 'switched',
                                 'message', p_provider || ' reached 80% of its limit (' || total || ' tokens); using templates until next month'));
    end if;
    return false;
  end if;

  return cfg.enabled;
end $$;

alter table app_config enable row level security;
alter table api_usage  enable row level security;

-- Every phone reads the config to know whether AI insights are on; only the admin changes it.
create policy app_config_read  on app_config for select to authenticated using (true);
create policy app_config_admin on app_config for update to authenticated using (is_admin()) with check (is_admin());
-- Usage rows are written only through record_api_usage(); the admin can read them.
create policy api_usage_admin_read on api_usage for select to authenticated using (is_admin());

revoke all on function record_api_usage(text, bigint) from public;
grant execute on function record_api_usage(text, bigint) to authenticated, service_role;
grant execute on function api_usage_this_month(text) to authenticated;

do $$ begin
  alter publication supabase_realtime add table app_config;
exception when undefined_object then null;
end $$;
