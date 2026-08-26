-- P10: the execution window — spec.window "HH:MM-HH:MM" (UTC). The scheduler skips cron
-- slots outside it; a manual run bypasses it (an explicit operator act).
ALTER TABLE connector.connector ADD COLUMN IF NOT EXISTS run_window VARCHAR(11);
