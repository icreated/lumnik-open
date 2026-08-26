-- lumnik Identity & Tenancy — V2
-- Role, User, UserRole, Session aggregates

-- ─────────────────────────────────────────────────────
-- APP_ROLE  (iDempiere: AD_Role)
-- ─────────────────────────────────────────────────────
CREATE SEQUENCE app_role_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE app_role (
    id          BIGINT      NOT NULL DEFAULT nextval('app_role_id_seq'),
    tenant_id   BIGINT      NOT NULL,
    org_id      BIGINT      NOT NULL DEFAULT 0,
    name        VARCHAR(60) NOT NULL,
    description VARCHAR(255),
    user_level  VARCHAR(2)  NOT NULL DEFAULT 'CO',
    is_manual   BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_role      PRIMARY KEY (id),
    CONSTRAINT fk_role_tenant   FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT uq_role_name     UNIQUE (tenant_id, name),
    CONSTRAINT chk_role_level   CHECK (user_level IN ('S', 'C', 'O', 'CO'))
);

COMMENT ON TABLE  app_role IS 'Access role. Equivalent to iDempiere AD_Role.';
COMMENT ON COLUMN app_role.user_level IS 'S=System, C=Client, O=Org, CO=Client+Org.';
COMMENT ON COLUMN app_role.is_manual IS 'If true, org-access assignments are explicit (not auto-granted).';

-- ─────────────────────────────────────────────────────
-- APP_USER  (iDempiere: AD_User)
-- ─────────────────────────────────────────────────────
CREATE SEQUENCE app_user_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE app_user (
    id                  BIGINT       NOT NULL DEFAULT nextval('app_user_id_seq'),
    tenant_id           BIGINT       NOT NULL,
    org_id              BIGINT       NOT NULL DEFAULT 0,
    login               VARCHAR(255) NOT NULL,
    name                VARCHAR(60)  NOT NULL,
    email               VARCHAR(120),
    description         VARCHAR(255),
    password_hash       VARCHAR(255),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_locked           BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count  INT          NOT NULL DEFAULT 0,
    last_login_at       TIMESTAMPTZ,
    password_expires_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          BIGINT       NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user      PRIMARY KEY (id),
    CONSTRAINT fk_user_tenant   FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT uq_user_login    UNIQUE (tenant_id, login),
    CONSTRAINT chk_user_failed  CHECK (failed_login_count >= 0)
);

COMMENT ON TABLE  app_user IS 'System user. Equivalent to iDempiere AD_User.';
COMMENT ON COLUMN app_user.is_locked IS 'Set after too many failed login attempts.';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt hash. Null when auth delegates to Keycloak.';

-- ─────────────────────────────────────────────────────
-- USER_ROLE  (iDempiere: AD_User_Roles)
-- ─────────────────────────────────────────────────────
CREATE TABLE user_role (
    user_id    BIGINT  NOT NULL,
    role_id    BIGINT  NOT NULL,
    tenant_id  BIGINT  NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_role   PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user     FOREIGN KEY (user_id)   REFERENCES app_user(id),
    CONSTRAINT fk_ur_role     FOREIGN KEY (role_id)   REFERENCES app_role(id),
    CONSTRAINT fk_ur_tenant   FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

-- ─────────────────────────────────────────────────────
-- SESSION  (iDempiere: AD_Session)
-- ─────────────────────────────────────────────────────
CREATE TABLE session (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   BIGINT      NOT NULL,
    org_id      BIGINT      NOT NULL DEFAULT 0,
    user_id     BIGINT      NOT NULL,
    login_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    logout_at   TIMESTAMPTZ,
    processed   BOOLEAN     NOT NULL DEFAULT FALSE,
    remote_addr VARCHAR(60),
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_session     PRIMARY KEY (id),
    CONSTRAINT fk_sess_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_sess_user   FOREIGN KEY (user_id)   REFERENCES app_user(id)
);

COMMENT ON TABLE  session IS 'Active and closed user sessions. Equivalent to iDempiere AD_Session.';
COMMENT ON COLUMN session.processed IS 'TRUE when the session has been explicitly closed (logout).';
