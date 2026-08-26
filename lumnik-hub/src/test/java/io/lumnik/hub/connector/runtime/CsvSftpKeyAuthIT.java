package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SFTP transport authenticates with an SSH key (key_env) — not only a password.
 * The atmoz/sftp container appends every {@code /home/<user>/.ssh/keys/*.pub} to authorized_keys
 * at startup, so copying a throwaway RSA public key there enables key-only login.
 * SftpFileSource.ensureConnected() parses the private key via MINA SecurityUtils and registers
 * it as a public-key identity.
 *
 * <p>The keypair is GENERATED here, per run — never committed: a private key in test resources,
 * however throwaway, is a finding in every scanner the day the repo goes public (Act 2 scrub).
 * MINA writes the OpenSSH private format, so the parser on the source side is exercised with
 * exactly what it parses in production.
 */
@QuarkusTest
class CsvSftpKeyAuthIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    static GenericContainer<?> sftp;
    static final String USER = "csvuser";

    @BeforeAll
    static void start() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair kp = gen.generateKeyPair();

        var privOut = new java.io.ByteArrayOutputStream();
        new org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter()
            .writePrivateKey(kp, "lumnik throwaway test key", null, privOut);
        String pem = privOut.toString(StandardCharsets.UTF_8);
        String pubLine = org.apache.sshd.common.config.keys.PublicKeyEntry.toString(kp.getPublic());

        // No password set → key-only auth. The .pub is copied into the atmoz key dir before the
        // entrypoint runs, so it is appended to authorized_keys on startup.
        sftp = new GenericContainer<>("atmoz/sftp:alpine").withExposedPorts(22)
            .withCopyToContainer(Transferable.of(pubLine + "\n"),
                "/home/" + USER + "/.ssh/keys/id_test_rsa.pub")
            .withCommand(USER + "::::upload");
        sftp.start();
        sftp.copyFileToContainer(MountableFile.forClasspathResource("/csv/customers-simple.csv"),
            "/home/" + USER + "/upload/customers.csv");
        sftp.execInContainer("chown", "-R", USER, "/home/" + USER + "/upload");

        System.setProperty("C1B_SFTP_KEY", pem);   // SftpFileSourceFactory resolves key_env via getProperty → getenv
    }

    @AfterAll
    static void stop() {
        System.clearProperty("C1B_SFTP_KEY");
        if (sftp != null) sftp.stop();
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void sftp_key_auth_connector_run_ingests_remote_csv() {
        Map<String, Object> transport = Map.of(
            "kind", "sftp", "host", sftp.getHost(), "port", sftp.getMappedPort(22),
            "user", USER, "path", "/upload/*.csv", "key_env", "C1B_SFTP_KEY",
            "strict_host_key", false);

        UUID id = TenantContext.callAsSystem(() -> {
            try {
                utx.begin();
                var c = new Connector();
                c.setTenantId(1L); c.setName("c1b-sftp-key-" + System.nanoTime()); c.setTypeId("csv-file");
                c.setConfig(Map.of("kind", "csv-file", "transport", transport,
                    "parser", Map.of("has_header", true)));
                connectors.persist(c); connectors.flush();
                var ep = new ConnectorEndpoint();
                ep.setConnectorId(c.getId()); ep.setTenantId(1L); ep.setName("default");
                ep.setMethod("GET"); ep.setPath("/upload/*.csv"); ep.setEnabled(true);
                ep.setConfig(Map.of("name", "c1b_sftp_key"));
                endpoints.persist(ep);
                utx.commit();
                return c.getId();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        RunStatus s = engine.runSync(id, new RunTrigger.Manual(1L));
        assertThat(s).isInstanceOf(RunStatus.Completed.class);
        assertThat(scalar("SELECT count(*) FROM connector.t_c1b_sftp_key")).isGreaterThan(0);
    }
}
