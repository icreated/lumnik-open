package io.lumnik.hub.proposal;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The catalogue the hub holds, trusted only as far as its own lock vouches for it (spec 2026-10-04,
 * section 3): a version, and each rule, is returned when the digest recomputed from the file equals
 * the lock's entry. Anything else is "not held" — the caller abstains rather than guesses.
 */
final class TrustedCatalogue {

    private final Catalogue catalogue;
    private final boolean catalogueIntact;
    private final Set<String> intactRules = new HashSet<>();

    TrustedCatalogue(String catalogueJson, String lockJson) {
        try {
            JsonNode tree = Catalogue.STRICT_JSON.readTree(catalogueJson);
            JsonNode lock = Catalogue.STRICT_JSON.readTree(lockJson);
            this.catalogue = Catalogue.from(tree);
            this.catalogueIntact = locked(lock, "catalogue@" + catalogue.catalogue(), CanonicalJson.digest(tree));
            for (JsonNode rule : tree.get("rules")) {
                String ref = rule.get("id").asText() + "@" + rule.get("version").asInt();
                if (locked(lock, "rule:" + ref, CanonicalJson.digest(rule))) intactRules.add(ref);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TrustedCatalogue load() {
        return new TrustedCatalogue(resource(Catalogue.RESOURCE), resource("proposal/catalogue.lock.json"));
    }

    Optional<Catalogue> held(String version) {
        return catalogueIntact && catalogue.catalogue().equals(version) ? Optional.of(catalogue) : Optional.empty();
    }

    Optional<Catalogue.Rule> rule(String ref) {
        if (!intactRules.contains(ref)) return Optional.empty();
        return catalogue.rules().stream().filter(r -> (r.id() + "@" + r.version()).equals(ref)).findFirst();
    }

    private static boolean locked(JsonNode lock, String key, String digest) {
        return digest.equals(lock.path(key).asText(null));
    }

    private static String resource(String name) {
        try (InputStream in = TrustedCatalogue.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
