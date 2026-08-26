-- Mini legacy-ERP-like source schema for IT.
-- Loaded into a Testcontainers Postgres instance separate from the hub DB.

CREATE TABLE c_bp_group (
    c_bp_group_id BIGINT PRIMARY KEY,
    name          VARCHAR(60)  NOT NULL,
    description   VARCHAR(255),
    updated       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE c_bpartner (
    c_bpartner_id  BIGINT PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    description    VARCHAR(255),
    is_active      CHAR(1)      NOT NULL DEFAULT 'Y',
    c_bp_group_id  BIGINT REFERENCES c_bp_group(c_bp_group_id),
    updated        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE m_product (
    m_product_id BIGINT PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    sku          VARCHAR(40)  UNIQUE,
    description  VARCHAR(255),
    updated      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE c_order (
    c_order_id    BIGINT PRIMARY KEY,
    document_no   VARCHAR(40)  NOT NULL,
    c_bpartner_id BIGINT REFERENCES c_bpartner(c_bpartner_id),
    grand_total   NUMERIC(12,2),
    updated       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Should be skipped by default discovery patterns
CREATE TABLE t_temp (id BIGINT PRIMARY KEY, payload TEXT);
CREATE TABLE ad_meta (id BIGINT PRIMARY KEY, key VARCHAR(60), value TEXT);

INSERT INTO c_bp_group VALUES (1, 'Standard', 'Default group', '2026-05-01 10:00');
INSERT INTO c_bpartner VALUES
  (1, 'Acme Corp',     'Strategic account', 'Y', 1, '2026-05-20 09:00'),
  (2, 'Beta Industries','Pilot client',     'Y', 1, '2026-05-21 11:30'),
  (3, 'Gamma SARL',    NULL,                'N', 1, '2026-05-22 14:15');
INSERT INTO m_product VALUES
  (10, 'Service A', 'SVC-A', 'Hourly service A', '2026-05-22 10:00'),
  (11, 'Service B', 'SVC-B', NULL,               '2026-05-22 10:10');
INSERT INTO c_order VALUES
  (100, 'SO-0001', 1, 1234.56, '2026-05-22 16:00');
