package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.hub.connector.exceptions.CredentialNotFoundException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The level-2 half of discovery: when the scope holds a JDBC connector with a credential, the AD
 * label provider is consulted and the card is promoted from {@code registry} to {@code jdbc-ad}.
 *
 * <p>Reaching it needs {@code sourceCreds} to succeed, so the JDBC round trip is mocked — the only
 * place in this net where a mock earns its keep. Everything else stays a subclass fake.
 */
class DiscoveryServiceAdEnrichmentTest {

    private static final String CRED_ID = "11111111-2222-3333-4444-555555555555";

    static final class FakeRegistry extends RegistrySchemaBuilder {
        List<HubTable> tables = new ArrayList<>();
        @Override public List<HubTable> resolve(long tenantId, String scope) { return tables; }
    }

    static final class FakeEntityViews extends EntitySchemaBuilder {
        @Override public List<SchemaCard.Table> resolve(String scope) { return List.of(); }
    }

    static final class CapturingLabels extends AdLabelProvider {
        String url, user, password, table;
        Map<String, String> answer = Map.of();
        @Override public Map<String, String> labelsForTable(String u, String usr, String pwd, String t) {
            this.url = u; this.user = usr; this.password = pwd; this.table = t;
            return answer;
        }
    }

    static final class RecordingStore extends SchemaCardStore {
        String sourceType;
        SchemaCard card;
        @Override public void save(long tenantId, String sourceType, SchemaCard card) {
            this.sourceType = sourceType; this.card = card;
        }
        @Override public Optional<SchemaCard> load(long tenantId, String scope) { return Optional.empty(); }
    }

    /** The resolver's two answers that matter here: a secret, or the not-found it throws for a missing one. */
    static final class FakeCredentials implements CredentialResolver {
        ResolvedCredential cred = new ResolvedCredential.Basic("aduser", "adpass");
        @Override public ResolvedCredential resolve(UUID id) {
            if (cred == null) throw new CredentialNotFoundException("credential not found: " + id);
            return cred;
        }
    }

    private final FakeRegistry registry = new FakeRegistry();
    private final CapturingLabels labels = new CapturingLabels();
    private final RecordingStore store = new RecordingStore();
    private final FakeCredentials credentials = new FakeCredentials();

    /** A datasource whose single row is the JDBC connector discovery looks for. */
    private AgroalDataSource datasourceReturningCredentials() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("url")).thenReturn("jdbc:postgresql://erp/db");
        when(rs.getString("usr")).thenReturn("erpuser");
        when(rs.getString("cid")).thenReturn(CRED_ID);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        Connection cx = mock(Connection.class);
        when(cx.prepareStatement(anyString())).thenReturn(ps);
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(ds.getConnection()).thenReturn(cx);
        return ds;
    }

    private DiscoveryService service(AgroalDataSource ds) {
        DiscoveryService s = new DiscoveryService();
        s.registry = registry;
        s.entityViews = new FakeEntityViews();
        s.adLabels = labels;
        s.store = store;
        s.credentials = credentials;
        s.adminDs = ds;
        return s;
    }

    private static RegistrySchemaBuilder.HubTable hub(String sourceEntity, String... cols) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : cols) m.put(c, "text");
        return new RegistrySchemaBuilder.HubTable("connector", "t_order", m, sourceEntity);
    }

    @Test
    void an_ad_label_enriches_the_column_and_promotes_the_card_to_jdbc_ad() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));
        labels.answer = Map.of("code", "Numéro de commande");

        DiscoveryResult r = service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertTrue(r.enriched(), "one labelled column is enough to mark the card enriched");
        assertEquals("jdbc-ad", r.sourceType());
        assertEquals("jdbc-ad", store.sourceType);
        assertEquals("Numéro de commande", store.card.tables().get(0).columns().get(0).label());
    }

    @Test
    void the_label_lookup_lowercases_the_column_so_the_ad_map_must_be_keyed_lowercase() throws Exception {
        registry.tables.add(hub("public.c_order", "CODE"));
        labels.answer = Map.of("code", "Numéro de commande");   // lower-case key: matches

        DiscoveryResult r = service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertTrue(r.enriched(), "the lookup key is column.toLowerCase — a lower-case AD key matches");
        assertEquals("CODE", store.card.tables().get(0).columns().get(0).name(), "the NAME keeps its case");
        assertEquals("Numéro de commande", store.card.tables().get(0).columns().get(0).label());
    }

    /**
     * Not a defect today — {@code AdLabelProvider} keys its map with
     * {@code col.toLowerCase(Locale.ROOT)} and says so in its javadoc. This pins the INVARIANT the
     * two classes share: the day one side stops lower-casing, enrichment degrades in silence — no
     * error, just an unlabelled column and a card that quietly stays {@code registry}.
     */
    @Test
    void an_ad_map_keyed_in_upper_case_never_matches_and_the_card_stays_a_registry_card() throws Exception {
        registry.tables.add(hub("public.c_order", "CODE"));
        labels.answer = Map.of("CODE", "Numéro de commande");   // upper-case key: silently missed

        DiscoveryResult r = service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertFalse(r.enriched(), "the lookup only ever tries the lower-cased column name");
        assertEquals("registry", r.sourceType());
        assertEquals("CODE", store.card.tables().get(0).columns().get(0).label(), "falls back to the name");
    }

    @Test
    void the_source_entity_is_reduced_to_its_bare_table_name_before_the_ad_lookup() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));

        service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertEquals("c_order", labels.table, "'public.c_order' → 'c_order' — the schema is dropped");
        assertEquals("jdbc:postgresql://erp/db", labels.url);
    }

    @Test
    void the_connector_user_column_wins_over_the_one_inside_the_credential() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));

        service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertEquals("erpuser", labels.user, "config->>'user' is preferred when present");
        assertEquals("adpass", labels.password, "the password always comes from the decrypted secret");
    }

    @Test
    void a_credential_row_that_vanished_falls_back_to_no_enrichment() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));
        DiscoveryService s = service(datasourceReturningCredentials());
        credentials.cred = null;                    // deleted between the query and the lookup: the resolver throws

        DiscoveryResult r = s.rediscover(1L, "sales");

        assertFalse(r.enriched(), "a missing credential is best-effort, never a failure");
        assertEquals("registry", r.sourceType());
        assertNull(labels.table, "and the AD provider is not called at all");
    }

    @Test
    void a_secret_that_is_not_basic_falls_back_to_no_enrichment() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));
        DiscoveryService s = service(datasourceReturningCredentials());
        credentials.cred = new ResolvedCredential.ApiKey("token");

        DiscoveryResult r = s.rediscover(1L, "sales");

        assertEquals("registry", r.sourceType(), "a JDBC source needs user and password; a token enriches nothing");
        assertNull(labels.table);
    }

    @Test
    void a_datasource_that_throws_degrades_to_a_registry_card_rather_than_failing_discovery() throws Exception {
        registry.tables.add(hub("public.c_order", "code"));
        AgroalDataSource broken = mock(AgroalDataSource.class);
        when(broken.getConnection()).thenThrow(new IllegalStateException("pool exhausted"));

        DiscoveryResult r = service(broken).rediscover(1L, "sales");

        assertEquals("registry", r.sourceType(), "sourceCreds swallows everything — discovery still returns a card");
        assertEquals(1, r.tableCount());
    }

    @Test
    void a_source_entity_without_a_schema_is_used_as_the_table_name_verbatim() throws Exception {
        registry.tables.add(hub("c_order", "code"));

        service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertEquals("c_order", labels.table, "no dot to strip — the value passes through");
    }

    @Test
    void a_hub_table_with_no_source_entity_skips_the_ad_lookup_for_that_table() throws Exception {
        registry.tables.add(hub(null, "code"));

        DiscoveryResult r = service(datasourceReturningCredentials()).rediscover(1L, "sales");

        assertNull(labels.table, "no source entity — nothing to ask AD about");
        assertEquals("registry", r.sourceType());
    }
}
