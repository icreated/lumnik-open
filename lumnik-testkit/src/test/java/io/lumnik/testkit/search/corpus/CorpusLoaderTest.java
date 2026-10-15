package io.lumnik.testkit.search.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class CorpusLoaderTest {

    private static final String MINIMAL_VALID_YAML = """
            schemaVersion: 1
            seed: 704
            entities:
              - name: customer
                rows:
                  - id: customer-de-001
                    tenantId: 1
                    fields:
                      name: Müller GmbH
                      customerCode: DE-0042
            queries:
              - id: de-transliteration-mueller
                language: de
                text: Mueller
                tenantId: 1
                allowedEntities: [customer]
                tags: [transliteration, company-name]
                judgments:
                  - hit: customer-de-001
                    relevance: 3
            """;

    @Test
    void loadsAMinimalValidCorpus() {
        SearchCorpus corpus = CorpusLoader.load(new StringReader(MINIMAL_VALID_YAML));

        assertThat(corpus.schemaVersion()).isEqualTo(1);
        assertThat(corpus.seed()).isEqualTo(704);
        assertThat(corpus.entities()).hasSize(1);
        assertThat(corpus.entities().get(0).name()).isEqualTo("customer");
        assertThat(corpus.entities().get(0).rows()).hasSize(1);
        CorpusRow row = corpus.entities().get(0).rows().get(0);
        assertThat(row.id()).isEqualTo("customer-de-001");
        assertThat(row.tenantId()).isEqualTo(1L);
        assertThat(row.fields()).containsEntry("name", "Müller GmbH");

        assertThat(corpus.queries()).hasSize(1);
        CorpusQuery query = corpus.queries().get(0);
        assertThat(query.id()).isEqualTo("de-transliteration-mueller");
        assertThat(query.language()).isEqualTo("de");
        assertThat(query.text()).isEqualTo("Mueller");
        assertThat(query.tenantId()).isEqualTo(1L);
        assertThat(query.allowedEntities()).containsExactly("customer");
        assertThat(query.tags()).containsExactly("transliteration", "company-name");
        assertThat(query.judgments()).containsExactly(new Judgment("customer-de-001", 3));
    }

    @Test
    void rejectsADuplicateRowId() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: dup-001
                        tenantId: 1
                        fields:
                          name: Alpha
                      - id: dup-001
                        tenantId: 1
                        fields:
                          name: Beta
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: dup-001
                        relevance: 3
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("dup-001");
    }

    @Test
    void rejectsADuplicateQueryId() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries:
                  - id: dup-q
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-001
                        relevance: 3
                  - id: dup-q
                    language: en
                    text: Alpha again
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-001
                        relevance: 2
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("dup-q");
    }

    @Test
    void rejectsAJudgmentReferencingAnUnknownRowId() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: does-not-exist
                        relevance: 3
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void rejectsARelevanceGradeOutOfRange() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-001
                        relevance: 4
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("relevance");
    }

    @Test
    void rejectsAJudgedHitBelongingToADifferentTenantThanTheQuery() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 2
                        fields:
                          name: Alpha
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-001
                        relevance: 3
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void rejectsAJudgedHitWhoseEntityIsNotInTheQuerysAllowedEntities() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                  - name: order
                    rows:
                      - id: row-002
                        tenantId: 1
                        fields:
                          reference: ORD-1
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-002
                        relevance: 3
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("allowedEntities");
    }

    @Test
    void rejectsAQueryWithNoPositiveJudgment() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments: []
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("no positive judgment");
    }

    @Test
    void toleratesAMissingEntitiesSection() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                queries: []
                """;

        SearchCorpus corpus = CorpusLoader.load(new StringReader(yaml));

        assertThat(corpus.entities()).isEmpty();
    }

    @Test
    void toleratesAMissingQueriesSection() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities: []
                """;

        SearchCorpus corpus = CorpusLoader.load(new StringReader(yaml));

        assertThat(corpus.queries()).isEmpty();
    }

    @Test
    void toleratesAnEntityWithNoRowsSection() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                queries: []
                """;

        SearchCorpus corpus = CorpusLoader.load(new StringReader(yaml));

        assertThat(corpus.entities().get(0).rows()).isEmpty();
    }

    @Test
    void toleratesAQueryWithNoJudgmentsSectionAndReportsItAsHavingNoPositiveJudgment() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities: []
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("no positive judgment");
    }

    @Test
    void rejectsAQueryWithTwoJudgmentsForTheSameHit() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries:
                  - id: q1
                    language: en
                    text: Alpha
                    tenantId: 1
                    allowedEntities: [customer]
                    tags: [exact-token]
                    judgments:
                      - hit: row-001
                        relevance: 3
                      - hit: row-001
                        relevance: 1
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("duplicate judgment")
                .hasMessageContaining("row-001");
    }

    @Test
    void rejectsARowWithABlankId() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: ""
                        tenantId: 1
                        fields:
                          name: Alpha
                queries: []
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("blank id");
    }

    @Test
    void rejectsAnEntityWithABlankName() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: ""
                    rows:
                      - id: row-001
                        tenantId: 1
                        fields:
                          name: Alpha
                queries: []
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("blank name");
    }

    @Test
    void rejectsARowWithNullFields() {
        String yaml = """
                schemaVersion: 1
                seed: 704
                entities:
                  - name: customer
                    rows:
                      - id: row-001
                        tenantId: 1
                queries: []
                """;

        assertThatThrownBy(() -> CorpusLoader.load(new StringReader(yaml)))
                .isInstanceOf(CorpusValidationException.class)
                .hasMessageContaining("row-001")
                .hasMessageContaining("fields");
    }

    @Test
    void loadResourceFailsClearlyWhenTheResourceIsMissing() {
        assertThatThrownBy(() -> CorpusLoader.loadResource("does/not/exist.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does/not/exist.yaml");
    }

    @Test
    void loadResourceLoadsTheRealCommittedCorpus() {
        SearchCorpus corpus = CorpusLoader.loadResource("search-corpus/v1/direct-search.yaml");

        assertThat(corpus.schemaVersion()).isEqualTo(1);
        assertThat(corpus.seed()).isEqualTo(704);
    }

    @Test
    void readResourceRawFailsClearlyWhenTheResourceIsMissing() {
        assertThatThrownBy(() -> CorpusLoader.readResourceRaw("does/not/exist.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does/not/exist.yaml");
    }
}
