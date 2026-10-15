-- V53__connector_run_status_check.sql — connector_run.status is a closed set.
-- The five values mirror io.lumnik.hub.connector.domain.ConnectorRunStatus#sqlValue().
ALTER TABLE connector.connector_run
    ADD CONSTRAINT connector_run_status_check
    CHECK (status IN ('Running', 'Completed', 'Partial', 'Failed', 'Cancelled'));
