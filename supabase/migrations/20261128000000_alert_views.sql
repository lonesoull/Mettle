-- Mettle — read-friendly alert and place views (lat/lon instead of PostGIS values).
-- Run after 20261127000000_admin_views.sql.

create or replace view alerts_live with (security_invoker = true) as
select a.id, a.user_id, a.kind, a.status,
       st_y(a.location::geometry) as lat, st_x(a.location::geometry) as lon,
       a.floor, a.detail, a.created_at, a.acknowledged_by, a.acknowledged_at,
       p.display_name
from alerts a
join profiles p on p.id = a.user_id;

create or replace view places_live with (security_invoker = true) as
select id, user_id, name, kind, radius_m, auto_learned,
       st_y(location::geometry) as lat, st_x(location::geometry) as lon,
       floor_height_m, steps_per_floor
from places
where deleted_at is null;

grant select on alerts_live, places_live to authenticated;
revoke all on alerts_live, places_live from anon;
