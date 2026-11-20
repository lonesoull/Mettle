-- Mettle — make sure signed-in members can reach the tables through the API.
-- Row-level security (set up in the earlier scripts) still decides which ROWS
-- each person can see; this only opens the door for the "authenticated" role.
-- Safe to run more than once. Run after 20261124000000_api_usage_limits.sql.

grant usage on schema public to authenticated;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;
grant execute on all functions in schema public to authenticated;

-- Signed-out visitors get nothing.
revoke all on all tables in schema public from anon;

-- Tables created later get the same treatment.
alter default privileges in schema public grant select, insert, update, delete on tables to authenticated;
alter default privileges in schema public grant usage, select on sequences to authenticated;
alter default privileges in schema public grant execute on functions to authenticated;
alter default privileges in schema public revoke all on tables from anon;
