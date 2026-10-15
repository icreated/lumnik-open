package io.lumnik.testkit.search.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Reads/writes {@link BaselineSnapshot} JSON. Never writes into {@code src/}: candidates land under
 * {@code target/search-baselines/} and a human copies the reviewed file into module resources.
 */
public final class BaselineIO {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private BaselineIO() {
    }

    /** SHA-256 of the exact raw corpus text, as {@code sha256:<hex>}. */
    public static String fingerprint(String rawCorpusYaml) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawCorpusYaml.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }

    public static void write(Path path, BaselineSnapshot snapshot) {
        try {
            Files.createDirectories(path.getParent());
            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<BaselineSnapshot> read(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON_MAPPER.readValue(path.toFile(), BaselineSnapshot.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
