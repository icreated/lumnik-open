package io.lumnik.hub.connector.naming;

import com.ibm.icu.text.Transliterator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Single home for turning arbitrary labels — OpenAPI path segments, DDD model names, JSON field names —
 * into safe lowercase identifiers usable as manifest endpoint names, {@code ext.*} table names and
 * promoted column names.
 *
 * <p>The "Mal d'Intelligences" this guards against (all seen in real ERP/SaaS specs):
 * <ul>
 *   <li><b>Unicode renames</b> — accents / apostrophes / CJK from "user-friendly" Swagger renames. We
 *       <em>romanize</em> via ICU ({@code Modèle d'adaptation → modele_d_adaptation},
 *       {@code 日本語 → ri_ben_yu}) instead of collapsing everything to {@code _}, preserving meaning.</li>
 *   <li><b>Empty results</b> — pure punctuation/emoji that survive nothing; we fall back to a caller hint.</li>
 *   <li><b>Collisions</b> — two distinct inputs that slug identically (the DDD {@code Person_1}/{@code Person_2}
 *       case). {@link Unique} disambiguates with a controlled {@code _2}, {@code _3} suffix.</li>
 * </ul>
 */
public final class Identifiers {

    private Identifiers() {}

    /** {@code Any-Latin} romanizes scripts (Han, Cyrillic, …) to Latin; {@code Latin-ASCII} strips diacritics. */
    private static final Transliterator TO_ASCII = Transliterator.getInstance("Any-Latin; Latin-ASCII");

    /**
     * Romanize to ASCII, lowercase, collapse runs of non-alphanumerics to {@code _}, trim edge underscores.
     *
     * <p>The result is always a valid unquoted SQL identifier / YAML scalar: it never starts with a
     * digit (digit-leading slugs are prefixed with {@code _}). When nothing survives (pure punctuation /
     * emoji / non-romanizable symbols), the result is {@code fallback} plus a truncated SHA-256 digest of
     * the raw input — NOT the bare constant — so two distinct names that both collapse don't share one
     * identifier and overwrite each other's {@code ext.*} table. A {@code null} raw returns the bare fallback.
     *
     * @param raw      the source label (may be null)
     * @param fallback the disambiguation base used when nothing survives sanitization
     */
    // S6395: the (?:^_+)|(?:_+$) groups are not redundant here — S5850 requires each anchored branch
    // of the alternation to be grouped, so unwrapping them would re-introduce that issue.
    @SuppressWarnings("java:S6395")
    public static String slug(String raw, String fallback) {
        if (raw == null) return fallback;
        String s = TO_ASCII.transliterate(raw)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("(?:^_+)|(?:_+$)", "");
        if (s.isEmpty()) {
            // fallback is a letter-leading constant, so the digest suffix needs no digit guard.
            return fallback + "_" + digest(raw);
        }
        // Unquoted SQL identifiers (and YAML scalars) must not start with a digit.
        return Character.isDigit(s.charAt(0)) ? "_" + s : s;
    }

    /**
     * An endpoint id, which lives under a different contract from a SQL identifier:
     * {@code ^[a-z][a-z0-9-]*$} — hyphens, and a letter first. {@link #slug} sanitises for SQL
     * (underscores, and it may lead with one so an unquoted identifier never starts with a digit),
     * so the two cannot share a derivation: a generated manifest whose endpoint id carried the
     * table's underscores was rejected by the hub's own validator.
     */
    // S6395: same anchored-alternation grouping as slug above — see that comment.
    @SuppressWarnings("java:S6395")
    public static String kebab(String raw, String fallback) {
        String s = slug(raw, fallback).replace('_', '-').replaceAll("(?:^-+)|(?:-+$)", "");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? fallback + "-" + s : s;
    }

    /** A deterministic, collision-resistant short suffix: the first 5 bytes of SHA-256, hex-encoded. */
    private static String digest(String raw) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /**
     * De-duplicates identifiers within one scope (e.g. all endpoints of one import, all columns of one row):
     * the first claim of a name wins it, later claims get {@code name_2}, {@code name_3}, …
     */
    public static final class Unique {
        private final Set<String> used = new HashSet<>();

        public String take(String name) {
            String candidate = name;
            int n = 2;
            while (!used.add(candidate)) {
                candidate = name + "_" + n++;
            }
            return candidate;
        }
    }
}
