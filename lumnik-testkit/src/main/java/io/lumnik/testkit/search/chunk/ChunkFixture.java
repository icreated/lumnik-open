package io.lumnik.testkit.search.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The frozen embeddings of the #675 corpus: a compact float32 little-endian binary plus a readable manifest. The
 * loader refuses a fixture whose texts, ids, schema version or bytes no longer match what the vectors were generated
 * for — texts and vectors cannot drift silently. Regenerating vectors is a NEW corpus version, not a refresh.
 */
public final class ChunkFixture {

    public static final String DIR = "search-corpus/chunks-v1/";
    public static final String MANIFEST_RESOURCE = DIR + "manifest.json";
    public static final String VECTORS_RESOURCE = DIR + "vectors.f32";

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Manifest(int schemaVersion, String model, String modelDigest, String ollamaVersion, int dimension,
                           String normalisation, String embeddingPath, String generatedAt, String regenerateCommand,
                           String textsSha256, String vectorsSha256, List<String> ids) {
    }

    public record Loaded(Manifest manifest, Map<String, float[]> vectors) {
    }

    public static final class FixtureIntegrityException extends IllegalStateException {
        public FixtureIntegrityException(String message) {
            super(message + " — regenerating the vectors is a NEW corpus version (bump schemaVersion, establish a new"
                + " baseline), not a technical refresh");
        }
    }

    private ChunkFixture() {
    }

    /** Chunk ids sorted, then query ids sorted — the order of the vectors in the binary. */
    public static List<String> orderedIds(ChunkCorpus corpus) {
        List<String> ids = new ArrayList<>(corpus.chunks().stream().map(CorpusChunk::id).sorted().toList());
        ids.addAll(corpus.queries().stream().map(CorpusChunkQuery::id).sorted().toList());
        return ids;
    }

    /** {@code sha256:<hex>} over {@code id TAB text NEWLINE} lines in {@link #orderedIds} order. */
    public static String textsChecksum(ChunkCorpus corpus) {
        Map<String, String> textById = new HashMap<>();
        corpus.chunks().forEach(c -> textById.put(c.id(), c.text()));
        corpus.queries().forEach(q -> textById.put(q.id(), q.text()));
        StringBuilder canonical = new StringBuilder();
        for (String id : orderedIds(corpus)) {
            String text = textById.get(id);
            if (text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("text of " + id + " contains a tab or newline");
            }
            canonical.append(id).append('\t').append(text).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }

    public static byte[] encode(List<float[]> vectors) {
        int dimension = vectors.get(0).length;
        ByteBuffer buffer = ByteBuffer.allocate(vectors.size() * dimension * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] vector : vectors) {
            if (vector.length != dimension) {
                throw new IllegalArgumentException("vectors differ in length: " + vector.length + " vs " + dimension);
            }
            for (float value : vector) {
                buffer.putFloat(value);
            }
        }
        return buffer.array();
    }

    public static Loaded loadResources(ChunkCorpus corpus) {
        return load(ChunkFixture::readClasspath, corpus);
    }

    public static Loaded load(Function<String, byte[]> readResource, ChunkCorpus corpus) {
        Manifest manifest;
        try {
            manifest = JSON.readValue(readResource.apply(MANIFEST_RESOURCE), Manifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> expectedIds = orderedIds(corpus);
        if (manifest.schemaVersion() != corpus.schemaVersion()) {
            throw new FixtureIntegrityException("manifest schemaVersion " + manifest.schemaVersion()
                + " does not match the corpus schemaVersion " + corpus.schemaVersion());
        }
        if (!manifest.ids().equals(expectedIds)) {
            throw new FixtureIntegrityException("the corpus ids (" + expectedIds.size() + ") differ from the ids the"
                + " vectors were generated for (" + manifest.ids().size() + ")");
        }
        if (!manifest.textsSha256().equals(textsChecksum(corpus))) {
            throw new FixtureIntegrityException("the corpus texts changed since the vectors were generated");
        }
        byte[] bytes = readResource.apply(VECTORS_RESOURCE);
        if (!manifest.vectorsSha256().equals(sha256(bytes))) {
            throw new FixtureIntegrityException("vectors.f32 does not match its recorded checksum");
        }
        long expectedLength = (long) expectedIds.size() * manifest.dimension() * Float.BYTES;
        if (bytes.length != expectedLength) {
            throw new FixtureIntegrityException("vectors.f32 length " + bytes.length + " != expected " + expectedLength);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (String id : expectedIds) {
            float[] vector = new float[manifest.dimension()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = buffer.getFloat();
            }
            vectors.put(id, vector);
        }
        return new Loaded(manifest, vectors);
    }

    private static byte[] readClasspath(String resource) {
        try (InputStream in = ChunkFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture resource: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
