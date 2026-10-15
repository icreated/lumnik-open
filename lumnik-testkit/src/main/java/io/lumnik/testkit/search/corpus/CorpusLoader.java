package io.lumnik.testkit.search.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and validates a {@link SearchCorpus} from YAML. Validation failures are collected and
 * reported together rather than one at a time, so a single fixed-up run tells the whole story.
 */
public final class CorpusLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private CorpusLoader() {
    }

    /** Loads and validates a corpus from a classpath resource, e.g. {@code search-corpus/v1/direct-search.yaml}. */
    public static SearchCorpus loadResource(String classpathResource) {
        try (InputStream in = openResource(classpathResource)) {
            return load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a classpath resource's raw text, e.g. for corpus fingerprinting. */
    public static String readResourceRaw(String classpathResource) {
        try (InputStream in = openResource(classpathResource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream openResource(String classpathResource) {
        InputStream in = CorpusLoader.class.getClassLoader().getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException("missing corpus resource: " + classpathResource);
        }
        return in;
    }

    public static SearchCorpus load(Reader yaml) {
        SearchCorpus corpus;
        try {
            corpus = YAML_MAPPER.readValue(yaml, SearchCorpus.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        validate(corpus);
        return corpus;
    }

    private record IndexedRow(CorpusRow row, String entityName) {
    }

    private static void validate(SearchCorpus corpus) {
        List<String> problems = new ArrayList<>();
        Map<String, IndexedRow> rowsById = new HashMap<>();

        for (CorpusEntity entity : corpus.entities()) {
            if (isBlank(entity.name())) {
                problems.add("entity has a null or blank name");
            }
            for (CorpusRow row : entity.rows()) {
                if (isBlank(row.id())) {
                    problems.add("entity " + entity.name() + ": row has a null or blank id");
                } else if (rowsById.containsKey(row.id())) {
                    problems.add("duplicate row id: " + row.id());
                } else {
                    rowsById.put(row.id(), new IndexedRow(row, entity.name()));
                }
                if (row.fields() == null) {
                    problems.add("row " + row.id() + ": fields must not be null");
                }
            }
        }

        Set<String> queryIds = new HashSet<>();
        for (CorpusQuery query : corpus.queries()) {
            if (!queryIds.add(query.id())) {
                problems.add("duplicate query id: " + query.id());
            }

            boolean hasPositiveJudgment = false;
            Set<String> judgedHits = new HashSet<>();
            for (Judgment judgment : query.judgments()) {
                if (!judgedHits.add(judgment.hit())) {
                    problems.add("query " + query.id() + ": duplicate judgment for hit " + judgment.hit());
                }
                if (judgment.relevance() >= Judgment.POSITIVE_RELEVANCE_THRESHOLD) {
                    hasPositiveJudgment = true;
                }
                if (judgment.relevance() < Judgment.MIN_RELEVANCE || judgment.relevance() > Judgment.MAX_RELEVANCE) {
                    problems.add("query " + query.id() + ": relevance out of range [" + Judgment.MIN_RELEVANCE + ","
                            + Judgment.MAX_RELEVANCE + "] for hit " + judgment.hit() + ": " + judgment.relevance());
                }
                IndexedRow indexed = rowsById.get(judgment.hit());
                if (indexed == null) {
                    problems.add("query " + query.id() + ": judgment references unknown row id "
                            + judgment.hit());
                    continue;
                }
                if (indexed.row().tenantId() != query.tenantId()) {
                    problems.add("query " + query.id() + ": judged hit " + judgment.hit()
                            + " belongs to tenant " + indexed.row().tenantId() + ", not the query's tenant "
                            + query.tenantId());
                }
                if (!query.allowedEntities().contains(indexed.entityName())) {
                    problems.add("query " + query.id() + ": judged hit " + judgment.hit()
                            + " belongs to entity " + indexed.entityName()
                            + ", which is not in the query's allowedEntities " + query.allowedEntities());
                }
            }

            if (!hasPositiveJudgment) {
                problems.add("query " + query.id() + ": has no positive judgment (relevance >= "
                        + Judgment.POSITIVE_RELEVANCE_THRESHOLD + ")");
            }
        }

        if (!problems.isEmpty()) {
            throw new CorpusValidationException(problems);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
