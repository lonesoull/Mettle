-- Mettle — each phone reports what it can do, so the app (and the admin) know
-- which accuracy tier applies. Run after 20261122000000_safety_health_extras.sql.
--
-- Example value:
-- {"model":"motorola edge 50 pro","android":15,
--  "barometer":false,"wifi_rtt":false,"gnss_dual_freq":true,"gnss_raw":true,
--  "step_counter":true,"gyroscope":true,"magnetometer":true,"sensor_fifo":3000,
--  "camera_flash":true,"bluetooth_le":true,"health_connect":true,
--  "checked_at":"2026-11-23T10:00:00Z"}
alter table profiles  add column phone_capabilities jsonb;
alter table member_status add column position_source text;   -- 'gps_dual','gps','wifi_rtt','wifi_fingerprint','network','dead_reckoning'
