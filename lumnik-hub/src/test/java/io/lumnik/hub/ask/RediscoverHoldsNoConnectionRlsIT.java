package io.lumnik.hub.ask;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.source.csv.CsvIngestService;
import io.lumnik.hub.webhook.SecretCipher;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rediscovery reads the customer's Application Dictionary once per hub table — a connect and up to
 * 5 s each, against a database that is not ours. It used to do so inside a transaction: the door was
 * {@code @Transactional}, and the two background callers wrapped the call in {@code requiringNew()},
 * because the one {@code default}-datasource read on the path (the source's credential) needed one
 * to be visible. One hub connection was then held for N × (connect + 5 s), and an unreachable source
 * multiplied that by the tables of the scope (#938, the fifth site of the class #909 closed).
 *
 * <p>The credential is now resolved through {@code CredentialResolver}, which opens its own short
 * transaction — so the door and both wrappers can go, and the AD reads run with nothing open. That
 * also settles the callers that never had a transaction (entity apply and delete): their credential
 * read found no row under RLS, and the card they rebuilt silently lost its AD labels.
 *
 * <p>Under the RLS-enforced profile on purpose: a read outside a transaction carries no tenant and
 * sees no row there, which the default profile (connecting as the owner) cannot tell.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RediscoverHoldsNoConnectionRlsIT {

    private static final long TENANT = 8380L;
    private static final String SCOPE = "ntxrediscover";

    @InjectMock AdLabelProvider adLabels;
    @Inject DiscoveryService discovery;
    @Inject SecretCipher cipher;
    @Inject Event<ConnectorRunCompleted> runCompleted;
    @Inject CsvIngestService csvIngest;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;   // the owner, so the fixture can seed past RLS

    /** Fail-closed: starts EMPTY, so an assertion on its contents cannot pass without a real call. */
    private final List<Boolean> txActiveAtAdRead = new CopyOnWriteArrayList<>();

    private UUID connectorId;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM connector.connector WHERE tenant_id = " + TENANT);
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = " + TENANT);
        exec("DELETE FROM connector.connector_credential WHERE tenant_id = " + TENANT);
        exec("DELETE FROM connector.schema_card WHERE tenant_id = " + TENANT);

        UUID credId = UUID.randomUUID();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector.connector_credential (id, tenant_id, name, kind, ciphertext, iv) "
               + "VALUES (?, ?, 'ntx--credential', 'Basic', ?, ?)")) {
            ps.setObject(1, credId); ps.setLong(2, TENANT);
            ps.setBytes(3, cipher.encrypt("aduser:adpass")); ps.setBytes(4, new byte[0]);
            ps.executeUpdate();
        }
        connectorId = UUID.randomUUID();
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, credential_id, "
           + "enabled, tags, sync_mode, skip_policy) VALUES ('" + connectorId + "', " + TENANT
           + ", 'ntx--c_order', 'jdbc-generic', '{\"url\":\"jdbc:postgresql://erp.invalid/db\",\"user\":\"aduser\"}'::jsonb, '"
           + credId + "', true, ARRAY['" + SCOPE + "'], 'Incremental', "
           + "'{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), " + TENANT + ", 'jdbc:c_order', 'connector', 't_c_order', "
           + "'{\"documentno\":\"text\"}'::jsonb, '" + connectorId + "')");

        txActiveAtAdRead.clear();
        when(adLabels.labelsForTable(anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            txActiveAtAdRead.add(QuarkusTransaction.isActive());
            return Map.of("documentno", "Numéro de commande");
        });
    }

    private String storedSourceType() {
        return storedSourceType(SCOPE);
    }

    private String storedSourceType(String scope) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT source_type FROM connector.schema_card WHERE tenant_id = "
                 + TENANT + " AND scope = '" + scope + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void the_door_reads_the_source_with_no_transaction_open() {
        given().header("X-Tenant-ID", String.valueOf(TENANT)).contentType(ContentType.JSON)
        .when().post("/api/platform/rag/rediscover?scope=" + SCOPE)
        .then().statusCode(200);

        assertThat(txActiveAtAdRead).as("the AD provider was reached").hasSize(1);
        assertThat(txActiveAtAdRead).as("and no transaction pinned a hub connection while it was").containsOnly(false);
    }

    @Test
    void a_caller_that_never_had_a_transaction_still_finds_the_credential_and_keeps_the_labels() {
        // Entity apply and delete call rediscover exactly so: a tenant context, no transaction.
        DiscoveryResult r = TenantContext.callAs(TENANT, () -> discovery.rediscover(TENANT, SCOPE));

        assertThat(r.sourceType()).as("the credential was visible: the card is a jdbc-ad one").isEqualTo("jdbc-ad");
        assertThat(r.enriched()).isTrue();
        assertThat(txActiveAtAdRead).containsOnly(false);
    }

    @Test
    void the_background_refresh_reads_the_source_with_no_transaction_open() throws Exception {
        // schemaChangedDuring: the dynamic table was written a moment ago, inside the look-back window.
        runCompleted.fireAsync(new ConnectorRunCompleted(connectorId, TENANT, UUID.randomUUID(),
            Instant.now().minusSeconds(30))).toCompletableFuture().get();

        assertThat(txActiveAtAdRead).as("the refresher reached the AD provider").hasSize(1);
        assertThat(txActiveAtAdRead).containsOnly(false);
        assertThat(storedSourceType()).isEqualTo("jdbc-ad");
    }

    /**
     * A connector moved to another scope rebuilds both cards from the commit callback (#1194), like
     * the one-shot below: the credential must still be visible there, or the new scope's card is
     * written without its AD labels.
     */
    @Test
    void a_connector_moved_to_another_scope_rebuilds_its_card_with_the_credential_visible() {
        String moved = SCOPE + "-moved";
        exec("DELETE FROM connector.schema_card WHERE tenant_id = " + TENANT);

        given().headers(TestIdentity.tenantAndUser(TENANT)).contentType(ContentType.JSON)
            .body(List.of(moved))
        .when().put("/api/platform/connectors/" + connectorId + "/tags")
        .then().statusCode(200);

        assertThat(txActiveAtAdRead).as("the AD provider was reached").isNotEmpty();
        assertThat(txActiveAtAdRead).containsOnly(false);
        assertThat(storedSourceType(moved)).as("the credential was visible from the commit callback").isEqualTo("jdbc-ad");
    }

    /**
     * The one-shot CSV ingest rediscovers from an {@code afterCompletion} callback: the thread's
     * transaction has just committed but is still associated with it. The resolver must still get a
     * transaction of its own there, or the credential is invisible under RLS and the card is rebuilt
     * without its AD labels, silently.
     */
    @Test
    void a_one_shot_csv_ingest_rediscovers_with_the_credential_visible_and_no_transaction_open() throws Exception {
        Path csv = Files.createTempFile("ntxrediscover", ".csv");
        Files.writeString(csv, "code\nA1\n", StandardCharsets.UTF_8);
        csv.toFile().deleteOnExit();
        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "ntx_rediscover_probe";
        form.scope = SCOPE;

        QuarkusTransaction.requiringNew().call(() -> TenantContext.callAs(TENANT, () -> csvIngest.execute(form, TENANT)));

        // the scope now holds the seeded table and the ingested one: one AD read each
        assertThat(txActiveAtAdRead).as("the AD provider was reached").isNotEmpty();
        assertThat(txActiveAtAdRead).containsOnly(false);
        assertThat(storedSourceType()).as("the credential was visible from the commit callback").isEqualTo("jdbc-ad");
    }
}
