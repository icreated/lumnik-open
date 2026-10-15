package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchException;
import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal, configurable in-memory {@link IndexedSearchPort}/{@link ProjectionSink} double used only
 *  to exercise {@link ProviderContractHarness}'s own generated-test logic — never a faithful
 *  reimplementation of a real provider's generation lifecycle. */
class StubSearchProvider implements IndexedSearchPort, ProjectionSink {
    private record StoredDoc(String entity, ProjectionDocument doc) {}

    // ConcurrentHashMap, deliberately: Task 4's LIFECYCLE_CUTOVER atomicity test races a reader
    // thread's search() against a writer thread's publish() — a plain HashMap would risk a
    // ConcurrentModificationException on the reader's entrySet() iteration, not just a wrong answer.
    private final Map<String, Long> live = new ConcurrentHashMap<>();
    private final Map<String, List<StoredDoc>> byGeneration = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private String currentEntity = "unset";

    boolean leakAcrossTenants = false;
    boolean failWrites = false;
    boolean failSearch = false;

    @Override public String name() { return "stub"; }

    /** Derived, not a settable field — mirrors the real design (§3 of the spec): status() probes the
     *  same backend reachability search()/upsert() itself depends on, non-cached. */
    @Override
    public ProviderStatus status() {
        return (failSearch || failWrites) ? ProviderStatus.degraded("stub_backend_unreachable") : ProviderStatus.available();
    }

    @Override
    public IndexedSearchResult search(IndexedSearchQuery query) {
        if (failSearch) {
            throw new IndexedSearchException("search failed", new RuntimeException("stub backend unavailable"));
        }
        List<IndexedSearchHit> hits = new ArrayList<>();
        for (Map.Entry<String, Long> e : live.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            long tenantId = Long.parseLong(parts[0]);
            String index = parts[1];
            if (!leakAcrossTenants && tenantId != query.tenantId()) continue;
            for (StoredDoc stored : byGeneration.getOrDefault(e.getKey() + ":" + e.getValue(), List.of())) {
                if (!query.allowedEntities().contains(stored.entity())) continue;
                if (!matchesText(stored.doc(), query.text())) continue;
                hits.add(new IndexedSearchHit(index, stored.entity(), stored.doc().key(), 1.0,
                    stored.doc().fields(), Map.of()));
            }
        }
        return new IndexedSearchResult(hits, Map.of(), null, Instant.now());
    }

    /** A minimal, case-insensitive substring match against every field value — just enough for
     *  ProviderContractHarness's QUERY_ABUSE dimension to exercise genuine text matching against
     *  this stub (#674 finding), never a faithful reimplementation of a real provider's ranking. */
    private static boolean matchesText(ProjectionDocument doc, String text) {
        if (text == null || text.isEmpty()) return true;
        String needle = text.toLowerCase(Locale.ROOT);
        for (String value : doc.fields().values()) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    @Override
    public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
        if (failWrites) throw new IllegalStateException("stub backend unavailable");
        currentEntity = definition.entity();
        String token = String.valueOf(seq.incrementAndGet());
        byGeneration.put(tenantId + ":" + searchIndex + ":" + token, new ArrayList<>());
        return token;
    }

    @Override
    public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
        if (failWrites) throw new IllegalStateException("stub backend unavailable");
        List<StoredDoc> bucket = byGeneration.computeIfAbsent(
            tenantId + ":" + searchIndex + ":" + generation, k -> new ArrayList<>());
        for (ProjectionDocument d : documents) bucket.add(new StoredDoc(currentEntity, d));
    }

    @Override
    public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
        List<StoredDoc> bucket = byGeneration.get(tenantId + ":" + searchIndex + ":" + generation);
        if (bucket != null) bucket.removeIf(sd -> ids.contains(sd.doc().id()));
    }

    @Override
    public void publish(long tenantId, String searchIndex, String generation) {
        live.put(tenantId + ":" + searchIndex, Long.parseLong(generation));
    }

    @Override
    public void abandon(long tenantId, String searchIndex, String generation) {
        byGeneration.remove(tenantId + ":" + searchIndex + ":" + generation);
    }

    @Override
    public void purgeIndex(long tenantId, String searchIndex) {
        live.remove(tenantId + ":" + searchIndex);
    }

    @Override
    public long liveDocumentCount(long tenantId, String searchIndex) {
        Long gen = live.get(tenantId + ":" + searchIndex);
        if (gen == null) return 0;
        return byGeneration.getOrDefault(tenantId + ":" + searchIndex + ":" + gen, List.of()).size();
    }

    ProviderFaultController asFaultController() {
        StubSearchProvider self = this;
        return new ProviderFaultController() {
            public AutoCloseable loseBackendDuringQuery() {
                self.failSearch = true;
                return () -> self.failSearch = false;
            }
            public AutoCloseable loseBackendDuringIndexing() {
                self.failWrites = true;
                return () -> self.failWrites = false;
            }
            public IndexedSearchPort bootWithBackendAbsent() {
                StubSearchProvider fresh = new StubSearchProvider();
                fresh.failSearch = true;
                fresh.failWrites = true;
                return fresh;
            }
        };
    }
}
