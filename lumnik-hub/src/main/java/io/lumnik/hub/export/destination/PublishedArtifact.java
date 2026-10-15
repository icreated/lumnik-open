package io.lumnik.hub.export.destination;

/**
 * What a successful {@link FileDestination#commit()} published — ledger-ready, transport-neutral.
 *
 * <p>Deliberately carries no content checksum: the only SHA-256 a ledger records is
 * {@code SnapshotStats.sha256Hex}, so an ETag can never be mistaken for one.
 *
 * @param kind      the destination transport: {@code "sftp"} or {@code "s3"}
 * @param location  sftp: the final path · s3: {@code "s3://<bucket>/<key>"}
 * @param versionId s3 versioned bucket only; otherwise {@code null}
 * @param etag      s3 only; an opaque object identity, never a content hash; otherwise {@code null}
 */
public record PublishedArtifact(String kind, String location, String versionId, String etag) {
}
