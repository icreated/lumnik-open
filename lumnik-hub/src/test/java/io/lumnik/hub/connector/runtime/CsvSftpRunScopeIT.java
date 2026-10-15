package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #898: a run opens ONE SFTP source however many chunks it takes, and closes it when it ends. The source is the real
 * SFTP source wrapped by a counting test factory ({@code sftp-counted}).
 */
@QuarkusTest
class CsvSftpRunScopeIT {

    private static final int ROWS = 1600;   // chunk size 500 => 4 chunks

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    static GenericContainer<?> sftp;
    static final String USER = "scopeuser";
    static final String PASS = "scopepass";

    @BeforeAll
    static void start() throws Exception {
        sftp = SftpTestImage.container(USER + ":" + PASS + ":1001::upload");
        sftp.start();
        StringBuilder csv = new StringBuilder("email,name,country\n");
        for (int i = 1; i <= ROWS; i++) csv.append("user").append(i).append("@example.com,User").append(i).append(",FR\n");
        try (var helper = new SftpTestHelper(sftp.getHost(), sftp.getMappedPort(22), USER, PASS)) {
            helper.put("upload/scope/big.csv", csv.toString());
        }
        System.setProperty("SCOPE_SFTP_PASSWORD", PASS);
    }

    @AfterAll
    static void stop() {
        System.clearProperty("SCOPE_SFTP_PASSWORD");
        if (sftp != null) sftp.stop();
    }

    @BeforeEach
    void resetCounts() {
        CountingSftpFileSourceFactory.reset();
    }

    private long count(String table) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM connector." + table + " WHERE tenant_id = 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID seedConnector(String table) {
        Map<String, Object> transport = Map.of(
            "kind", "sftp-counted", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/scope/*.csv", "password_env", "SCOPE_SFTP_PASSWORD",
            "strict_host_key", false);
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("scope-sftp-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport, "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/scope/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", table));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* no transaction to roll back */ }
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void a_run_of_four_chunks_builds_one_source_and_closes_it_at_the_end() {
        UUID id = seedConnector("scope_sftp_one");

        RunStatus status = engine.runSync(id, new RunTrigger.Manual(1L));

        assertThat(status).isInstanceOf(RunStatus.Completed.class);
        assertThat(count("t_scope_sftp_one")).as("every row arrived").isEqualTo(ROWS);
        assertThat(CountingSftpFileSourceFactory.builds()).as("four chunks, one connection").isEqualTo(1);
        assertThat(CountingSftpFileSourceFactory.closes()).as("closed when the run ended").isEqualTo(1);
    }

    @Test
    void two_runs_do_not_share_a_source() {
        UUID id = seedConnector("scope_sftp_two");

        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
        assertThat(engine.runSync(id, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);

        assertThat(count("t_scope_sftp_two")).as("the second run found nothing new").isEqualTo(ROWS);
        assertThat(CountingSftpFileSourceFactory.builds()).isEqualTo(2);
        assertThat(CountingSftpFileSourceFactory.closes()).isEqualTo(2);
    }
}
