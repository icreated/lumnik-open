-- V89 — One durable e-mail per rule and event, sent from its own row (#1848).
--
-- The e-mail consumer rendered a mail in the outbox batch and sent it from memory once the batch had committed: a render
-- or SMTP failure was a log line, and a crash between the commit and the send lost the batch's mails. One event reaches
-- several recipients, so the event is not the unit that can be redone: the delivery is, as for a webhook (V24, V37).
--
-- The batch now writes the delivery (rendered, leased), and the send happens from that row with its own bounded retry.
-- (rule_id, event_id) is unique: recording an event twice (a batch replayed after a failed commit, a dead letter
-- replayed) never creates a second delivery. Sending is at-least-once: when the outcome of a send cannot be written (the
-- hub stops, the database is away) the delivery is sent again once its lease has run out; a lease taken over is the only
-- writer of the outcome.
--
-- error is the exception's class, never its message: an SMTP message quotes the address.

CREATE SEQUENCE core.notification_delivery_id_seq START 1;

CREATE TABLE core.notification_delivery (
    id            BIGINT       NOT NULL DEFAULT nextval('core.notification_delivery_id_seq'),
    tenant_id     BIGINT       NOT NULL,
    -- no foreign key: a deleted rule leaves the history of what it sent
    rule_id       BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL,
    event_type    VARCHAR(120) NOT NULL,
    recipient     VARCHAR(320) NOT NULL,
    subject       TEXT         NOT NULL,
    -- the mail as rendered; empty when the render itself failed (the delivery is then given up, attempts = 3)
    body          TEXT         NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    succeeded     BOOLEAN      NOT NULL DEFAULT FALSE,
    error         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    attempted_at  TIMESTAMPTZ,
    -- lease: a sweep owns the delivery until this instant. NULL = unclaimed, past = expired and reclaimable
    claimed_until TIMESTAMPTZ,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (id),
    CONSTRAINT notification_delivery_once UNIQUE (rule_id, event_id)
);

-- what the retry sweep claims: unresolved deliveries only
CREATE INDEX idx_notification_delivery_retry ON core.notification_delivery (id)
    WHERE succeeded = FALSE AND attempts < 3;
-- what the retention walks
CREATE INDEX idx_notification_delivery_created_at ON core.notification_delivery (created_at);

ALTER TABLE core.notification_delivery ENABLE ROW LEVEL SECURITY;
-- the consumer works in system mode ('0', as for core.event_outbox, V4); everything else sees its own tenant
CREATE POLICY notification_delivery_tenant_isolation ON core.notification_delivery
    USING (current_setting('app.current_tenant', true) = '0'
           OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);

COMMENT ON TABLE core.notification_delivery IS
    'One e-mail per notification rule and outbox event (#1848): recorded by the batch, sent from this row, retried up to 3 times.';
