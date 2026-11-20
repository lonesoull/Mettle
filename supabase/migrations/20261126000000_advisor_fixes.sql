-- Mettle — fixes for Supabase Security & Performance Advisor warnings.
-- Run after 20261125000000_grants.sql.

-- 1. Group feed: replace the security-definer VIEW with a function.
--    Same result (signed-in members only, privacy-trimmed route, no streams),
--    but in the form Supabase recommends.
drop view if exists feed_activities;

create or replace function get_feed(p_limit int default 30, p_before timestamptz default null)
returns table (
  id uuid, user_id uuid, display_name text, avatar_path text,
  type activity_type, sub_type activity_sub, title text, description text,
  started_at timestamptz, timezone text, elapsed_s integer, moving_s integer,
  distance_m integer, elev_gain_m numeric, floors_up smallint, floors_down smallint,
  avg_pace_s_km numeric, route_public geography, created_at timestamptz,
  kudos_count bigint, comment_count bigint
)
language sql stable security definer set search_path = public as $$
  select a.id, a.user_id, p.display_name, p.avatar_path,
         a.type, a.sub_type, a.title, a.description, a.started_at, a.timezone,
         a.elapsed_s, a.moving_s, a.distance_m, a.elev_gain_m, a.floors_up, a.floors_down,
         a.avg_pace_s_km, a.route_public, a.created_at,
         (select count(*) from kudos k where k.activity_id = a.id),
         (select count(*) from comments c where c.activity_id = a.id and c.deleted_at is null)
  from activities a
  join profiles p on p.id = a.user_id
  where (select auth.uid()) is not null
    and a.deleted_at is null
    and (a.visibility = 'group' or a.user_id = (select auth.uid()) or is_admin())
    and (p_before is null or a.started_at < p_before)
  order by a.started_at desc
  limit least(greatest(p_limit, 1), 100)
$$;

revoke all on function get_feed(int, timestamptz) from public, anon;
grant execute on function get_feed(int, timestamptz) to authenticated;

-- 2. PostGIS reference table: no personal data, but keep it away from signed-out visitors.
--    (It lives in public because PostGIS was installed there; signed-in members keep
--    read access because geography functions consult it.)
do $$ begin
  revoke all on table public.spatial_ref_sys from anon;
  revoke insert, update, delete, truncate on table public.spatial_ref_sys from authenticated;
exception when undefined_table or insufficient_privilege then null;
end $$;

-- 3. Performance: evaluate auth.uid() / is_admin() once per query instead of per row.
do $$
declare
  pol record;
  q text;
  c text;
begin
  for pol in
    select schemaname, tablename, policyname, qual, with_check
    from pg_policies
    where schemaname = 'public'
  loop
    q := pol.qual;
    c := pol.with_check;
    if q is not null then
      q := replace(replace(q, '( SELECT auth.uid() AS uid)', 'auth.uid()'), 'auth.uid()', '(select auth.uid())');
      q := replace(q, 'is_admin()', '(select is_admin())');
    end if;
    if c is not null then
      c := replace(replace(c, '( SELECT auth.uid() AS uid)', 'auth.uid()'), 'auth.uid()', '(select auth.uid())');
      c := replace(c, 'is_admin()', '(select is_admin())');
    end if;
    if q is not null and c is not null then
      execute format('alter policy %I on %I.%I using (%s) with check (%s)', pol.policyname, pol.schemaname, pol.tablename, q, c);
    elsif q is not null then
      execute format('alter policy %I on %I.%I using (%s)', pol.policyname, pol.schemaname, pol.tablename, q);
    elsif c is not null then
      execute format('alter policy %I on %I.%I with check (%s)', pol.policyname, pol.schemaname, pol.tablename, c);
    end if;
  end loop;
end $$;
