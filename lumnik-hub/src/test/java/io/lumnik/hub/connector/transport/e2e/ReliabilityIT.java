package io.lumnik.hub.connector.transport.e2e;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.source.csv.CsvIngestService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReliabilityIT {

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
        System.setProperty("__INLINE_BAD_PW", "x");

        var ex = assertThrows(RuntimeException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)));
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
