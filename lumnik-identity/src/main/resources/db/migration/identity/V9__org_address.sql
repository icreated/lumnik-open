-- lumnik Identity — V9 Org Master Registry: Address & Geocoding
-- Add address and geocoding support to org for multi-site enterprises (franchises, agency networks, hospital groups)

ALTER TABLE org
  ADD COLUMN address_line1 VARCHAR(255),
  ADD COLUMN address_line2 VARCHAR(255),
  ADD COLUMN postal_code   VARCHAR(20),
  ADD COLUMN city          VARCHAR(120),
  ADD COLUMN country       VARCHAR(2),
  ADD COLUMN latitude      DECIMAL(9,6),
  ADD COLUMN longitude     DECIMAL(9,6),
  ADD COLUMN geocoded_at   TIMESTAMPTZ;

CREATE INDEX idx_org_country_city ON org (country, city) WHERE country IS NOT NULL;

COMMENT ON COLUMN org.address_line1 IS 'Primary address line.';
COMMENT ON COLUMN org.address_line2 IS 'Secondary address line (apartment, suite, etc).';
COMMENT ON COLUMN org.postal_code IS 'ZIP/postal code for location.';
COMMENT ON COLUMN org.city IS 'City or locality.';
COMMENT ON COLUMN org.country IS 'ISO 3166-1 alpha-2 country code (e.g. FR, US).';
COMMENT ON COLUMN org.latitude IS 'Geographic latitude; populated by geocoder.';
COMMENT ON COLUMN org.longitude IS 'Geographic longitude; populated by geocoder.';
COMMENT ON COLUMN org.geocoded_at IS 'Timestamp of last successful geocoding.';
