package io.lumnik.hub.export;

import java.util.List;

/**
 * A parsed kind:Export manifest — the tenant-bound delivery contract over one canonical entity.
 *
 * @param order the declared, business-meaningful sort — and <b>not, on its own, a total order</b>.
 *     The execution contract frozen by #676 for whichever ticket writes the actual query
 *     (#677/#680) to implement unchanged: {@code ORDER BY} these columns, in declared order,
 *     <b>then</b> every remaining projected column from {@code columns} in its declared order as a
 *     deterministic tiebreak, with {@code NULLS LAST} throughout. Two rows still identical after
 *     every projected column has been used as a sort key are byte-identical in the output, so
 *     their relative order is genuinely irrelevant. Without the tiebreak, {@code order: [city]}
 *     lets two rows sharing a city swap between runs, which breaks the epic's invariant that a
 *     retry over unchanged data generates identical CSV bytes.
 *     <p>One caveat the rule leans on and does not settle: "byte-identical" only follows from a
 *     deterministic text comparison. Under a non-deterministic collation two distinct byte
 *     sequences can compare equal for {@code ORDER BY} and still land either way round, so the
 *     executing ticket must either force a deterministic comparison (a {@code C} collation on the
 *     relevant expressions) or document why the default collation suffices for the columns in
 *     play — adding every column to {@code ORDER BY} does not solve it by itself.
 */
public record ExportManifest(
    String name,
    String scope,
    String entity,
    List<String> columns,
    List<String> order,
    Format format,
    Destination destination,
    String mode,
    String schedule
) {

    public record Format(String kind, char delimiter, String encoding, boolean header) {

        /** What an absent spec.format field means, and the one-shot download door's shape. */
        public static final Format DEFAULT = new Format("csv", ',', "UTF-8", true);
    }

    public sealed interface Destination permits Destination.Sftp, Destination.S3 {
        String kind();

        record Sftp(String host, int port, String user, String path, String keyEnv,
                     String passwordEnv, String hostKeyFingerprint) implements Destination {
            @Override public String kind() { return "sftp"; }
        }

        record S3(String bucket, String prefix, String region, String endpoint, String filename,
                   String accessKeyEnv, String secretKeyEnv) implements Destination {
            @Override public String kind() { return "s3"; }
        }
    }
}
