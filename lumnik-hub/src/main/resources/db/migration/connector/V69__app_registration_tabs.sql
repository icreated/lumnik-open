-- kind:App spec.tabs (#1030): the tabs an app asks for. NULL = inherit every tab the hub offers,
-- so an app written today discovers a facet shipped next year. Never materialised from the offer.
-- Expand-only: an older replica names its columns in every statement and never sees this one.
ALTER TABLE connector.app_registration
    ADD COLUMN tabs TEXT[],
    ADD CONSTRAINT app_registration_tabs_not_empty CHECK (tabs IS NULL OR (cardinality(tabs) > 0 AND array_position(tabs, NULL) IS NULL));
