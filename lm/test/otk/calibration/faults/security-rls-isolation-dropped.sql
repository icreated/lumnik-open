ALTER POLICY tenant_isolation ON connector.connector USING (true);
-- undo:
ALTER POLICY tenant_isolation ON connector.connector USING (current_setting('app.current_tenant', true) = '0' OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint);
