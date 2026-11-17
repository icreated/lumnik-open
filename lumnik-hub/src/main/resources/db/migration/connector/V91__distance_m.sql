-- V91 — connector.distance_m: metres between two WGS84 points (#1974, design 2026-11-13 geography, step 2a).
-- Shipped by migration, not by the earthdistance extension: that one is not trusted, and only a superuser can
-- create it — the migration role is deliberately not one. A sphere of mean radius 6,371,008.8 m (haversine).
-- An argument out of range answers NULL; a NULL argument answers NULL (STRICT). The name and the signature
-- are a contract: they do not change.

CREATE OR REPLACE FUNCTION connector.distance_m(
    lat1 double precision, lon1 double precision,
    lat2 double precision, lon2 double precision)
RETURNS double precision
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
  SELECT CASE
    WHEN abs(lat1) > 90 OR abs(lat2) > 90 OR abs(lon1) > 180 OR abs(lon2) > 180 THEN NULL
    ELSE 2 * 6371008.8 * asin(least(1, sqrt(
           power(sin(radians(lat2 - lat1) / 2), 2)
         + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2))))
  END
$$;

REVOKE ALL ON FUNCTION connector.distance_m(double precision, double precision, double precision, double precision)
  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION connector.distance_m(double precision, double precision, double precision, double precision)
  TO lumnik_app;

-- The read-only role (Ask-to-SQL) is provisioned outside Flyway; guarded like V15 where it is absent.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lumnik_readonly') THEN
    GRANT EXECUTE ON FUNCTION connector.distance_m(double precision, double precision, double precision, double precision)
      TO lumnik_readonly;
  ELSE
    RAISE NOTICE 'lumnik_readonly role absent — skipping the distance_m grant.';
  END IF;
END $$;
