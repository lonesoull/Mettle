-- Mettle — heart rate on workouts (from a Bluetooth strap/watch). Run after 20261128000000_alert_views.sql.
alter table activities
  add column if not exists avg_hr smallint check (avg_hr between 30 and 230),
  add column if not exists max_hr smallint check (max_hr between 30 and 240);
