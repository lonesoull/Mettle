-- Mettle — group feed with drawable routes and "did I give kudos".
-- Run after 20261129000000_workout_heart_rate.sql.

drop function if exists get_feed(int, timestamptz);

create or replace function get_feed(p_limit int default 30, p_before timestamptz default null)
returns table (
  id uuid, user_id uuid, display_name text,
  type activity_type, sub_type activity_sub, title text, description text,
  started_at timestamptz, elapsed_s integer, moving_s integer,
  distance_m integer, elev_gain_m numeric, floors_up smallint, floors_down smallint,
  avg_pace_s_km numeric, route_geojson text,
  kudos_count bigint, comment_count bigint, my_kudos boolean
)
language sql stable security definer set search_path = public as $$
  select a.id, a.user_id, p.display_name,
         a.type, a.sub_type, a.title, a.description, a.started_at, a.elapsed_s, a.moving_s,
         a.distance_m, a.elev_gain_m, a.floors_up, a.floors_down, a.avg_pace_s_km,
         case when a.user_id = (select auth.uid()) then st_asgeojson(coalesce(a.route, a.route_public))
              else st_asgeojson(a.route_public) end,
         (select count(*) from kudos k where k.activity_id = a.id),
         (select count(*) from comments c where c.activity_id = a.id and c.deleted_at is null),
         exists (select 1 from kudos k where k.activity_id = a.id and k.user_id = (select auth.uid()))
  from activities a
  join profiles p on p.id = a.user_id
  where (select auth.uid()) is not null
    and a.deleted_at is null
    and (a.visibility = 'group' or a.user_id = (select auth.uid()) or (select is_admin()))
    and (p_before is null or a.started_at < p_before)
  order by a.started_at desc
  limit least(greatest(p_limit, 1), 100)
$$;

revoke all on function get_feed(int, timestamptz) from public, anon;
grant execute on function get_feed(int, timestamptz) to authenticated;
