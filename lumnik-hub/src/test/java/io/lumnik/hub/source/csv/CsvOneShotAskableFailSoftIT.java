package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.ask.DiscoveryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The SchemaCardRefresher contract, inherited: a rediscover failure must never fail an
 * ingest whose rows are already durable — it warns, and {@code lm source rediscover}
 * remains the manual recovery.
 */
@QuarkusTest
class CsvOneShotAskableFailSoftIT {

    @Inject CsvIngestService service;

    @InjectMock DiscoveryService discovery;

    @Test
    void a_poisoned_rediscover_does_not_fail_the_ingest() throws Exception {
        when(discovery.rediscover(anyLong(), anyString()))
            .thenThrow(new RuntimeException("discovery deliberately broken for the test"));

        Path csv = Files.createTempFile("failsoft", ".csv");
        Files.writeString(csv, "code\nA1\n", StandardCharsets.UTF_8);
        csv.toFile().deleteOnExit();

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "failsoft_probe";
        form.scope = "failsoft";

        CsvIngestResult result = QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));

        assertThat(result.rowsWritten()).isEqualTo(1);
        assertThat(result.askableScopes()).contains("failsoft");
    }
}
