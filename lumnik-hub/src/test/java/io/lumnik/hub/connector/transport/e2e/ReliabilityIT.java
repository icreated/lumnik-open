package io.lumnik.hub.connector.transport.e2e;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.source.csv.CsvIngestService;
import io.lumnik.hub.testsupport.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReliabilityIT {

    @Inject TestSecrets testSecrets;

    @Inject CsvIngestService service;

    @Test
    void sftp_list_fails_with_clear_error_on_invalid_host() {
        CsvIngestForm form = new CsvIngestForm();
        form.name = "bad_host_" + System.nanoTime();
        form.scope = "system";
        form.transport = new HashMap<>(Map.of(
            "kind", "sftp",
            "host", "no.such.host.invalid.lumnik.test",
            "port", 22,
            "user", "x",
            "password_env", "__INLINE_BAD_PW",
            "path", "/*.csv",
            "strict_host_key", false
        ));
        // Served to the ingest's scope (#2015), so what fails is the host, not the secret.
        testSecrets.put(1L, "__INLINE_BAD_PW", "x", List.of("system"));

        var ex = assertThrows(RuntimeException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)));
        assertFalse(String.valueOf(ex.getMessage()).contains("does not serve"), ex.getMessage());
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        Throwable t = ex;
        while (t != null && !msg.contains("sftp") && !msg.contains("connect") && !msg.contains("filesource")) {
            t = t.getCause();
            if (t != null) msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        }
        assertTrue(msg.contains("sftp") || msg.contains("connect") || msg.contains("filesource"),
            "expected SFTP/connect/filesource error in chain, got root: " + ex.getMessage());
    }
}
