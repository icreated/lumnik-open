package io.lumnik.testkit.search.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.testkit.search.baseline.BaselineIO;
import io.lumnik.testkit.search.corpus.CorpusLoader;
import io.lumnik.testkit.search.corpus.CorpusValidationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and validates the #675 chunk corpus. Validation failures are collected and reported together. The
 * non-vacuity rule is structural: every chunk a leak case forbids must also be the {@code mustRankFirst} of some
 * control query that is allowed to see it — so a leak test can never pass because the forbidden chunk was never
 * retrievable in the first place.
 */
public final class ChunkCorpusLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final int MIN_GRADE = 0;
    private static final int MAX_GRADE = 3;
    private static final int RELEVANT_GRADE = 2;

    public record Loaded(ChunkCorpus corpus, String fingerprint) {
    }

    private ChunkCorpusLoader() {
    }

    /** Loads several YAML files as ONE corpus (core + generated distractors); the fingerprint covers all raw text. */
    public static Loaded loadResources(String... classpathResources) {
        List<String> raws = Arrays.stream(classpathResources).map(CorpusLoader::readResourceRaw).toList();
        List<ChunkCorpus> parts = raws.stream().map(ChunkCorpusLoader::parse).toList();
        List<String> problems = new ArrayList<>();
        int schema = parts.get(0).schemaVersion();
        for (int i = 1; i < parts.size(); i++) {
            if (parts.get(i).schemaVersion() != schema) {
                problems.add("resource " + classpathResources[i] + " has schemaVersion " + parts.get(i).schemaVersion()
                    + ", expected " + schema);
            }
        }
        List<CorpusChunk> chunks = new ArrayList<>();
        List<CorpusChunkQuery> queries = new ArrayList<>();
        for (ChunkCorpus part : parts) {
            chunks.addAll(part.chunks());
            queries.addAll(part.queries());
        }
        ChunkCorpus merged = new ChunkCorpus(schema, parts.get(0).seed(), chunks, queries);
        validate(merged, problems);
        return new Loaded(merged, BaselineIO.fingerprint(String.join("\n---\n", raws)));
    }

    /** Parse + validate one YAML document. */
    public static ChunkCorpus load(String yaml) {
        ChunkCorpus corpus = parse(yaml);
        validate(corpus, new ArrayList<>());
        return corpus;
    }

    static ChunkCorpus parse(String yaml) {
        try {
            return YAML_MAPPER.readValue(yaml, ChunkCorpus.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validate(ChunkCorpus corpus, List<String> problems) {
        Map<String, CorpusChunk> chunksById = new HashMap<>();
        for (CorpusChunk chunk : corpus.chunks()) {
            if (isBlank(chunk.id())) {
                problems.add("a chunk has a null or blank id");
            } else if (chunksById.put(chunk.id(), chunk) != null) {
                problems.add("duplicate chunk id: " + chunk.id());
            }
            if (chunk.tenantId() <= 0) {
                problems.add("chunk " + chunk.id() + ": tenantId must be positive");
            }
            if (isBlank(chunk.corpus())) {
                problems.add("chunk " + chunk.id() + ": corpus must not be blank");
            }
            if (isBlank(chunk.text())) {
                problems.add("chunk " + chunk.id() + ": text must not be blank");
            }
        }

        Set<String> queryIds = new HashSet<>();
        Set<String> controlledChunks = new HashSet<>();
        for (CorpusChunkQuery query : corpus.queries()) {
            if (!queryIds.add(query.id())) {
                problems.add("duplicate query id: " + query.id());
            }
            if (chunksById.containsKey(query.id())) {
                problems.add("query id " + query.id() + " collides with a chunk id");
            }
            if (query.tenantId() <= 0) {
                problems.add("query " + query.id() + ": tenantId must be positive");
            }
            if (isBlank(query.text())) {
                problems.add("query " + query.id() + ": text must not be blank");
            }
            if (query.queryClass() == null) {
                problems.add("query " + query.id() + ": class is required");
            } else if (query.queryClass().scored()) {
                validateScored(query, chunksById, problems);
            } else {
                validateIsolation(query, chunksById, problems, controlledChunks);
            }
        }

        for (CorpusChunkQuery query : corpus.queries()) {
            for (String forbidden : query.mustBeAbsent()) {
                if (!controlledChunks.contains(forbidden)) {
                    problems.add("query " + query.id() + ": forbidden chunk " + forbidden
                        + " is ranked first by no control query — the leak test could pass vacuously");
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new CorpusValidationException(problems);
        }
    }

    private static void validateScored(CorpusChunkQuery query, Map<String, CorpusChunk> chunksById, List<String> problems) {
        if (query.mustRankFirst() != null || !query.mustBeAbsent().isEmpty()) {
            problems.add("query " + query.id() + ": only isolation queries may use mustRankFirst / mustBeAbsent");
        }
        boolean relevant = false;
        for (Map.Entry<String, Integer> judgment : query.judgments().entrySet()) {
            int grade = judgment.getValue();
            if (grade < MIN_GRADE || grade > MAX_GRADE) {
                problems.add("query " + query.id() + ": grade out of range [" + MIN_GRADE + "," + MAX_GRADE + "] for "
                    + judgment.getKey() + ": " + grade);
            }
            relevant |= grade >= RELEVANT_GRADE;
            CorpusChunk chunk = chunksById.get(judgment.getKey());
            if (chunk == null) {
                problems.add("query " + query.id() + ": judgment references unknown chunk " + judgment.getKey());
            } else if (!chunk.visibleTo(query)) {
                problems.add("query " + query.id() + ": judged chunk " + judgment.getKey()
                    + " is not visible to this query (tenant, corpus or scope)");
            }
        }
        if (!relevant) {
            problems.add("query " + query.id() + ": has no judgment with grade >= " + RELEVANT_GRADE);
        }
    }

    private static void validateIsolation(CorpusChunkQuery query, Map<String, CorpusChunk> chunksById,
            List<String> problems, Set<String> controlledChunks) {
        if (!query.judgments().isEmpty()) {
            problems.add("query " + query.id() + ": isolation queries carry no judgments");
        }
        if (query.mustRankFirst() == null && query.mustBeAbsent().isEmpty()) {
            problems.add("query " + query.id() + ": isolation query asserts nothing");
        }
        if (query.mustRankFirst() != null) {
            CorpusChunk first = chunksById.get(query.mustRankFirst());
            if (first == null) {
                problems.add("query " + query.id() + ": mustRankFirst references unknown chunk " + query.mustRankFirst());
            } else if (!first.visibleTo(query)) {
                problems.add("query " + query.id() + ": mustRankFirst chunk " + first.id() + " is not visible to it");
            } else {
                controlledChunks.add(first.id());
            }
        }
        for (String forbidden : query.mustBeAbsent()) {
            CorpusChunk chunk = chunksById.get(forbidden);
            if (chunk == null) {
                problems.add("query " + query.id() + ": mustBeAbsent references unknown chunk " + forbidden);
            } else if (chunk.visibleTo(query)) {
                problems.add("query " + query.id() + ": forbidden chunk " + forbidden
                    + " is visible to this query, so its absence proves nothing");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
