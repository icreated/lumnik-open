package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.FacetCount;
import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #673's HTTP mapping over {@link SearchGate}, mocked exactly as {@code SearchOperationsResourceIT}
 * mocks it — the SPI query/result shapes, the absence/degraded/rejected outcomes and the scope
 * primitive are each proven elsewhere (SPI contract tests, {@code SearchGateTest}, the many existing
 * {@code ScopeGrants.allowsAny} consumers); this class proves only this resource's own translation.
 */
@QuarkusTest
class SearchResourceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchGate realSearchGate;
    @Inject SearchIndexStore realIndexStore;

    private static RequestSpecification as(long tenant) {
        return given().headers(TestIdentity.tenantAndUser(tenant));
    }

    @AfterEach
    void restoreRealGate() {
        QuarkusMock.installMockForType(realSearchGate, SearchGate.class);
        QuarkusMock.installMockForType(realIndexStore, SearchIndexStore.class);
    }

    private static SearchGate mockGate(SearchOutcome outcome) {
        SearchGate g = mock(SearchGate.class);
        when(g.search(any())).thenReturn(outcome);
        QuarkusMock.installMockForType(g, SearchGate.class);
        return g;
    }

    private static String unique(String prefix) {
        return prefix + System.nanoTime();
    }

    private String applyEntity(long tenant, String name) throws Exception {
        String yaml = SearchIndexItSupport.entity(adminDs, name, "t_" + name, List.of(), "code", "label");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml").body(yaml)
            .post("/api/entities/apply").then().statusCode(200);
        return name;
    }

    // -- request validation, no provider involved -------------------------------------------------

    @Test
    void blankQIsRejected() {
        as(1).get("/api/search?q=").then().statusCode(400).body("error", equalTo("q must be 1-200 characters"));
    }

    @Test
    void missingQIsRejected() {
        as(1).get("/api/search").then().statusCode(400).body("error", equalTo("q must be 1-200 characters"));
    }

    @Test
    void qOverTwoHundredCharsIsRejected() {
        as(1).get("/api/search?q=" + "a".repeat(201)).then().statusCode(400);
    }

    @Test
    void limitBelowOneIsRejected() {
        as(1).get("/api/search?q=acme&limit=0").then().statusCode(400)
            .body("error", equalTo("limit must be between 1 and 100"));
    }

    @Test
    void limitAboveMaxIsRejected() {
        as(1).get("/api/search?q=acme&limit=101").then().statusCode(400);
    }

    @Test
    void moreThanTenFacetKeysIsRejected() {
        StringBuilder qs = new StringBuilder("/api/search?q=acme");
        for (int i = 0; i < 11; i++) qs.append("&facet.f").append(i).append("=v");
        as(1).get(qs.toString()).then().statusCode(400);
    }

    @Test
    void aRepeatedFacetKeyIsRejected() {
        as(1).get("/api/search?q=acme&facet.status=open&facet.status=closed").then().statusCode(400);
    }

    // -- scope --------------------------------------------------------------------------------------

    @Test
    void anUnknownEntityIsRefusedUniformly() {
        as(1).get("/api/search?q=acme&entity=" + unique("nosuch")).then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }

    @Test
    void aKnownEntityNarrowsTheAllowedSet() throws Exception {
        String name = applyEntity(2, unique("searchresit"));
        SearchGate g = mockGate(new SearchOutcome.Hits(IndexedSearchResult.empty()));

        as(2).get("/api/search?q=acme&entity=" + name).then().statusCode(200);

        ArgumentCaptor<IndexedSearchQuery> captor = ArgumentCaptor.forClass(IndexedSearchQuery.class);
        verify(g).search(captor.capture());
        assertThat(captor.getValue().allowedEntities()).containsExactly(name);

        long rows = SearchIndexItSupport.count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE tenant_id = ? AND entity_type = 'Search' AND entity_key = ?", 2L, name);
        assertThat(rows).isEqualTo(1);
    }

    // -- outcome mapping ------------------------------------------------------------------------------

    @Test
    void hitsAreMappedWithSortedCanonicalLinkAndNullAsOf() {
        IndexedSearchHit hit = new IndexedSearchHit("idx", "customer",
            Map.of("region", "eu", "id", "42"), 3.5, Map.of("name", "Acme"), Map.of("name", List.of("<b>Acme</b>")));
        IndexedSearchResult result = new IndexedSearchResult(List.of(hit),
            Map.of("status", List.of(new FacetCount("open", 3))), "next-cursor", null);
        mockGate(new SearchOutcome.Hits(result));

        as(1).get("/api/search?q=acme").then().statusCode(200)
            .body("hits[0].entity", equalTo("customer"))
            .body("hits[0].key.id", equalTo("42"))
            .body("hits[0].key.region", equalTo("eu"))
            .body("hits[0].score", equalTo(3.5f))
            .body("hits[0].fields.name", equalTo("Acme"))
            .body("hits[0].highlights.name[0]", equalTo("<b>Acme</b>"))
            .body("hits[0].canonicalLink", equalTo("/api/entities/customer?id=42&region=eu"))
            .body("facets.status[0].value", equalTo("open"))
            .body("facets.status[0].count", equalTo(3))
            .body("nextCursor", equalTo("next-cursor"))
            .body("asOf", nullValue());
    }

    // -- the business title (#1044) -----------------------------------------------------------------------

    private void applyIndex(long tenant, String index, String entity, String titleLine) {
        String yaml = SearchIndexItSupport.searchIndex(index, entity, "code", "label")
            .replace("  fields:\n", titleLine + "  fields:\n");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml").body(yaml)
            .post("/api/search-indexes/apply").then().statusCode(200);
    }

    private static IndexedSearchHit hitOf(String index, String entity, Map<String, String> fields) {
        return new IndexedSearchHit(index, entity, Map.of("code", "A1"), 1.0, fields, Map.of());
    }

    @Test
    void aHitCarriesTheTitleOfItsIndexsDeclaredField() throws Exception {
        String entity = applyEntity(5, unique("titled"));
        String index = unique("idx-titled");
        applyIndex(5, index, entity, "  titleField: code\n");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "2195.300.037", "label", "Pompe"))), Map.of(), null, null)));

        as(5).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].title", equalTo("2195.300.037"))
            .body("hits[0].titleField", equalTo("code"))
            .body("hits[0].entity", equalTo(entity));
    }

    @Test
    void anIndexWithoutATitleFieldFallsBackToNothingAndNeverGuesses() throws Exception {
        String entity = applyEntity(6, unique("untitled"));
        String index = unique("idx-untitled");
        applyIndex(6, index, entity, "");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "A1", "label", "Pompe"))), Map.of(), null, null)));

        as(6).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].title", nullValue())
            .body("hits[0].titleField", nullValue());
    }

    @Test
    void aMissingOrBlankTitleValueIsNullNotAnEmptyHeading() throws Exception {
        String entity = applyEntity(7, unique("blanktitle"));
        String index = unique("idx-blank");
        applyIndex(7, index, entity, "  titleField: code\n");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(List.of(
            hitOf(index, entity, Map.of("code", "  ", "label", "Pompe")),
            hitOf(index, entity, Map.of("label", "Pompe"))), Map.of(), null, null)));

        as(7).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].title", nullValue()).body("hits[0].titleField", nullValue())
            .body("hits[1].title", nullValue()).body("hits[1].titleField", nullValue());
    }

    @Test
    void aTitleIsTrimmedLikeALegacyPaddedCharColumnNeedsIt() throws Exception {
        String entity = applyEntity(9, unique("paddedtitle"));
        String index = unique("idx-padded");
        applyIndex(9, index, entity, "  titleField: code\n");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "  2195.300.037   ", "label", "Pompe"))), Map.of(), null, null)));

        as(9).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].title", equalTo("2195.300.037"))
            .body("hits[0].titleField", equalTo("code"));
    }

    @Test
    void aFailingTitleLookupNeverTurnsAGoodSearchIntoAnError() {
        SearchIndexStore broken = mock(SearchIndexStore.class);
        when(broken.presentations(anyLong(), any())).thenThrow(new IllegalStateException("pool exhausted"));
        QuarkusMock.installMockForType(broken, SearchIndexStore.class);
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf("idx", "customer", Map.of("code", "A1"))), Map.of(), null, null)));

        as(8).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].entity", equalTo("customer"))
            .body("hits[0].title", nullValue());
    }

    // -- external links on hits (#1051) -------------------------------------------------------------------

    private static final String ERP = "https://erp.example.com/articles/{code}";

    private void applyLinkedIndex(long tenant, String index, String entity, String linksBlock) {
        String yaml = SearchIndexItSupport.searchIndex(index, entity, "code", "label")
            .replace("  fields:\n", linksBlock + "  fields:\n");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml").body(yaml)
            .post("/api/search-indexes/apply").then().statusCode(200);
    }

    @Test
    void aHitCarriesTheLinksItsIndexDeclared() throws Exception {
        String entity = applyEntity(10, unique("linked"));
        String index = unique("idx-linked");
        applyLinkedIndex(10, index, entity, "  links:\n    - field: code\n      url: \"" + ERP + "\"\n");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "A1", "label", "Pompe"))), Map.of(), null, null)));

        as(10).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].links.size()", equalTo(1))
            .body("hits[0].links[0].field", equalTo("code"))
            .body("hits[0].links[0].url", equalTo(ERP));
    }

    @Test
    void anIndexWithoutLinksLeavesTheKeyAbsentNotEmpty() throws Exception {
        String entity = applyEntity(11, unique("unlinked"));
        String index = unique("idx-unlinked");
        applyLinkedIndex(11, index, entity, "");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "A1", "label", "Pompe"))), Map.of(), null, null)));

        as(11).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0]", not(hasKey("links")));
    }

    @Test
    void aTitleAndLinksTravelTogetherFromOneLookup() throws Exception {
        String entity = applyEntity(12, unique("both"));
        String index = unique("idx-both");
        applyLinkedIndex(12, index, entity,
            "  titleField: code\n  links:\n    - field: code\n      url: \"" + ERP + "\"\n");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, entity, Map.of("code", "A1", "label", "Pompe"))), Map.of(), null, null)));

        as(12).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0].title", equalTo("A1"))
            .body("hits[0].links[0].url", equalTo(ERP));
    }

    @Test
    void aFailingLookupDegradesToNoLinksNotAnError() {
        SearchIndexStore broken = mock(SearchIndexStore.class);
        when(broken.presentations(anyLong(), any())).thenThrow(new IllegalStateException("pool exhausted"));
        QuarkusMock.installMockForType(broken, SearchIndexStore.class);
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf("idx", "customer", Map.of("code", "A1"))), Map.of(), null, null)));

        as(13).get("/api/search?q=pompe").then().statusCode(200)
            .body("hits[0]", not(hasKey("links")));
    }

    // -- the entity's column labels (#1055) --------------------------------------------------------------

    @Test
    void theAnswerCarriesTheLabelsOfTheEntitiesItHitsAndOnlyThose() throws Exception {
        String labelled = unique("labelled");
        String other = unique("otherent");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(14)).contentType("application/yaml; charset=UTF-8")
            .body(SearchIndexItSupport.entity(adminDs, labelled, "t_" + labelled, List.of(), "code", "label")
                + "columns:\n  code:\n    label: Référence\n")
            .post("/api/entities/apply").then().statusCode(200);
        applyEntity(14, other);
        String index = unique("idx-labelled");
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf(index, labelled, Map.of("code", "A1", "label", "Pompe"))), Map.of(), null, null)));

        as(14).get("/api/search?q=pompe").then().statusCode(200)
            .body("fieldLabels." + labelled + ".code", equalTo("Référence"))
            .body("fieldLabels", not(hasKey(other)));
    }

    @Test
    void noLabelAnywhereLeavesTheKeyAbsent() throws Exception {
        String entity = applyEntity(15, unique("nolabels"));
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(
            List.of(hitOf("idx", entity, Map.of("code", "A1"))), Map.of(), null, null)));

        as(15).get("/api/search?q=pompe").then().statusCode(200).body("$", not(hasKey("fieldLabels")));
    }

    @Test
    void notInstalledIs501() {
        mockGate(new SearchOutcome.Unavailable(SearchCapability.notInstalled()));
        as(1).get("/api/search?q=acme").then().statusCode(501).body("error", equalTo("search not installed"));
    }

    @Test
    void degradedIs503WithRetryAfter() {
        mockGate(new SearchOutcome.Unavailable(SearchCapability.degraded("postgres", "provider_error")));
        as(1).get("/api/search?q=acme").then().statusCode(503)
            .header("Retry-After", equalTo("60"))
            .body("error", equalTo("search degraded"))
            .body("reason", equalTo("provider_error"));
    }

    @Test
    void rejectedIs400WithTheProvidersOwnMessage() {
        mockGate(new SearchOutcome.Rejected("cursor does not match this query"));
        as(1).get("/api/search?q=acme&cursor=stale").then().statusCode(400)
            .body("error", equalTo("cursor does not match this query"));
    }

    // -- audit ----------------------------------------------------------------------------------------

    @Test
    void aSuccessfulSearchIsAuditedWithDistinctEntityNamesAndHitCountNeverRawText() {
        IndexedSearchHit hit = new IndexedSearchHit("idx", "customer", Map.of("id", "1"), 1.0, Map.of(), Map.of());
        mockGate(new SearchOutcome.Hits(new IndexedSearchResult(List.of(hit), Map.of(), null, null)));

        as(3).get("/api/search?q=super-secret-query-text").then().statusCode(200);

        long rows = SearchIndexItSupport.count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE tenant_id = ? AND entity_type = 'Search' AND operation = 'READ' AND entity_key = '*' "
            + "AND changes->>'hitCount' = '1' AND changes->'entities' @> '\"customer\"'::jsonb "
            + "AND changes::text NOT LIKE '%super-secret-query-text%'", 3L);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aRejectedSearchIsNeverAudited() {
        mockGate(new SearchOutcome.Rejected("cursor does not match this query"));
        as(4).get("/api/search?q=acme&cursor=stale").then().statusCode(400);

        long rows = SearchIndexItSupport.count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE tenant_id = ? AND entity_type = 'Search'", 4L);
        assertThat(rows).isEqualTo(0);
    }
}
