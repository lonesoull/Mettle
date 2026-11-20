-- Mettle — read-friendly views for the admin dashboard.
-- Run after 20261126000000_advisor_fixes.sql.
-- security_invoker: the caller's own privacy rules apply (admin sees everyone,
-- a member sees only themselves), so Supabase's advisor is happy.

create or replace view member_live with (security_invoker = true) as
select s.user_id,
       p.display_name,
       st_y(s.location::geometry) as lat,
       st_x(s.location::geometry) as lon,
       s.altitude_m, s.floor, s.accuracy_m, s.state, s.battery_pct, s.charging,
       s.tracking_ok, s.problem, s.position_source, s.updated_at,
       p.phone_capabilities
from member_status s
join profiles p on p.id = s.user_id;

create or replace view stays_live with (security_invoker = true) as
select id, user_id, start_at, end_at, st_y(location::geometry) as lat, st_x(location::geometry) as lon,
       altitude_m, floor, accuracy_m, label
from stays;

grant select on member_live, stays_live to authenticated;
revoke all on member_live, stays_live from anon;
