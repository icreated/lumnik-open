package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first net under DiscoveryService — it was at 0% coverage, 60 uncovered lines, which is why the
 * S3776 backlog deliberately left it alone: a refactor's safety IS its test net.
 *
 * <p>These are characterization tests. They pin what the code does today (dedup by FQN, AD
 * enrichment flipping the source type, entity views appended), so the behaviour is described before
 * anyone changes it — not because the behaviour was in doubt.
 *
 * <p>{@code adminDs} is deliberately left null here: {@code sourceCreds} catches everything and
 * returns null, which IS the "no JDBC connector in this scope" path — the common one, and the one
 * that needs no database. The enrichment path lives in {@link DiscoveryServiceAdEnrichmentTest}.
 */
class DiscoveryServiceTest {

    // ── fakes: subclasses rather than mocks, the RecordingStore pattern ─────────────────────────

    static final class FakeRegistry extends RegistrySchemaBuilder {
        List<HubTable> tables = new ArrayList<>();
        @Override public List<HubTable> resolve(long tenantId, String scope) { return tables; }
    }

    static final class FakeEntityViews extends EntitySchemaBuilder {
        List<SchemaCard.Table> tables = new ArrayList<>();
        @Override public List<SchemaCard.Table> resolve(String scope) { return tables; }
    }

    static final class RecordingLabels extends AdLabelProvider {
        int calls = 0;
        @Override public Map<String, String> labelsForTable(String url, String user, String pwd, String table) {
            calls++;
            return Map.of();
        }
    }

    static final class RecordingStore extends SchemaCardStore {
        String sourceType;
        SchemaCard card;
        String deleted;
        @Override public void delete(long tenantId, String scope) { this.deleted = scope; }
        @Override public void save(long tenantId, String sourceType, SchemaCard card) {
            this.sourceType = sourceType;
            this.card = card;
        }
        @Override public Optional<SchemaCard> load(long tenantId, String scope) { return Optional.empty(); }
    }

    private final FakeRegistry registry = new FakeRegistry();
    private final FakeEntityViews entityViews = new FakeEntityViews();
    private final RecordingLabels labels = new RecordingLabels();
    private final RecordingStore store = new RecordingStore();

    private DiscoveryService service() {
        DiscoveryService s = new DiscoveryService();
        s.registry = registry;
        s.entityViews = entityViews;
        s.adLabels = labels;
        s.store = store;
        return s;   // adminDs stays null → sourceCreds returns null → the no-credentials path
    }

    private static RegistrySchemaBuilder.HubTable hub(String schema, String name, String... cols) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : cols) m.put(c, "text");
        return new RegistrySchemaBuilder.HubTable(schema, name, m, "public." + name);
    }

    private static SchemaCard.Table view(String fqn, String... cols) {
        List<SchemaCard.Column> columns = new ArrayList<>();
        for (String c : cols) columns.add(new SchemaCard.Column(c, "text", c));
        return new SchemaCard.Table(fqn, fqn, columns, null);
    }

    // ── the behaviours ─────────────────────────────────────────────────────────────────────────

    @Test
    void without_jdbc_credentials_no_ad_lookup_happens_and_the_card_is_a_registry_card() {
        registry.tables.add(hub("connector", "t_order", "code", "amount"));

        DiscoveryResult r = service().rediscover(1L, "sales");

        assertEquals(0, labels.calls, "no credentials in the scope — the AD provider must not be called");
        assertFalse(r.enriched());
        assertEquals("registry", r.sourceType());
        assertEquals("registry", store.sourceType, "the saved card records the same source type");
    }

    @Test
    void a_column_without_an_ad_label_is_labelled_with_its_own_name() {
        registry.tables.add(hub("connector", "t_order", "code"));

        service().rediscover(1L, "sales");

        SchemaCard.Column col = store.card.tables().get(0).columns().get(0);
        assertEquals("code", col.name());
        assertEquals("code", col.label(), "the fallback label is the column name, never null");
    }

    @Test
    void the_same_hub_table_listed_twice_is_kept_once() {
        registry.tables.add(hub("connector", "t_order", "code"));
        registry.tables.add(hub("connector", "t_order", "code", "amount"));   // same FQN, richer

        DiscoveryResult r = service().rediscover(1L, "sales");

        assertEquals(1, r.tableCount(), "dedup is by schema.name — the first wins");
        assertEquals(1, r.columnCount(), "and the duplicate's columns are not counted either");
    }

    @Test
    void entity_views_are_appended_to_the_hub_tables_and_counted() {
        registry.tables.add(hub("connector", "t_order", "code"));
        entityViews.tables.add(view("connector.v_customer", "customer_code", "name"));

        DiscoveryResult r = service().rediscover(1L, "sales");

        assertEquals(2, r.tableCount());
        assertEquals(3, r.columnCount(), "1 hub column + 2 entity-view columns");
        assertEquals(List.of("connector.t_order", "connector.v_customer"),
            store.card.tables().stream().map(SchemaCard.Table::name).toList(),
            "hub tables first, then the entity views");
    }

    @Test
    void an_entity_view_whose_name_collides_with_a_hub_table_is_skipped() {
        registry.tables.add(hub("connector", "v_customer", "code"));
        entityViews.tables.add(view("connector.v_customer", "a", "b", "c"));

        DiscoveryResult r = service().rediscover(1L, "sales");

        assertEquals(1, r.tableCount(), "the hub table already claimed that FQN");
        assertEquals(1, r.columnCount(), "and the skipped view's columns must not inflate the count");
    }

    @Test
    void the_saved_card_carries_the_scope_it_was_discovered_for() {
        registry.tables.add(hub("connector", "t_order", "code"));

        DiscoveryResult r = service().rediscover(1L, "sales");

        assertEquals("sales", r.scope());
        assertEquals("sales", store.card.scope());
    }

    @Test
    void an_empty_scope_removes_its_card_rather_than_keeping_the_previous_one() {
        // #1188: the last table of the scope went (connector deleted or moved out) — no card at all
        DiscoveryResult r = service().rediscover(1L, "empty");

        assertEquals(0, r.tableCount());
        assertEquals(0, r.columnCount());
        assertNull(store.card, "nothing to describe: no card is written");
        assertEquals("empty", store.deleted, "and the previous card does not survive");
        assertEquals("registry", r.sourceType());
    }

    @Test
    void load_delegates_to_the_store() {
        assertTrue(service().load(1L, "sales").isEmpty());
    }
}
