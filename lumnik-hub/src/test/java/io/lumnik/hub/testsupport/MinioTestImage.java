package io.lumnik.hub.testsupport;

/**
 * The MinIO test image, pinned by digest and named once for every MinIO IT (#708, #725).
 *
 * <p>MinIO locked down anonymous pulls of {@code quay.io/minio/minio} (and {@code
 * docker.io/minio/minio}) on 2026-09-24 — every tag, including the previously-pinned {@code
 * RELEASE.2025-09-07T16-13-09Z}, now returns {@code 401} to an anonymous pull. Re-hosted at
 * {@code ghcr.io/icreated/minio} so neither CI nor a contributor's laptop needs a MinIO account.
 *
 * <p>The first re-host (#708) pushed an image re-tagged from a Mac's local Docker cache — which,
 * on Apple Silicon, only ever holds the {@code linux/arm64} half of the upstream multi-arch
 * manifest. That single-platform image ran fine on an arm64 laptop and failed on every amd64 CI
 * runner (#725): the container started and immediately exited, an aarch64 binary with no amd64
 * counterpart to fall back to.
 *
 * <p>By the time #725 was diagnosed, {@code dl.min.io} had also gone fully dark ({@code 410 Gone}
 * on every release, including this one) and {@code github.com/minio/minio} had been archived —
 * there is no longer an official binary to re-download from anywhere, for any platform. {@link
 * #PINNED} is therefore built from the archived source tree itself: {@code github.com/minio/minio}
 * at tag {@code RELEASE.2025-09-07T16-13-09Z} (commit {@code
 * 07c3a429bfed433e49018cb0f78a52145d4bedeb}), cross-compiled with plain {@code go build} for both
 * {@code linux/amd64} and {@code linux/arm64} (no {@code dl.min.io} download involved), and
 * packaged the same way the upstream {@code Dockerfile.release} did. Not byte-identical to the
 * withdrawn official binary anymore — same source, same tag, same commit, compiled by us instead
 * of downloaded — and pushed as a genuine multi-arch manifest list, verified booting on both
 * platforms before pinning. {@code mc} is built the same way, from {@code github.com/minio/mc}
 * (also archived) at the closest available tag, {@code RELEASE.2025-08-13T08-35-41Z} (commit
 * {@code 7394ce0dd2a80935aded936b09fa12cbb3cb8096}) — {@code S3FileDestinationIT} execs {@code mc}
 * inside this container, and the first rebuild of this image, missing it entirely, broke that test
 * with exit code 127.
 *
 * <p>To bump it: since the upstream binaries are gone for good, the only path is rebuilding from
 * newer {@code github.com/minio/minio}/{@code github.com/minio/mc} tags the same way (clone the
 * tag, {@code go build} for both platforms, package per {@code Dockerfile.release}, push a
 * multi-arch manifest to {@code ghcr.io/icreated/minio}) — never push a single-platform image
 * re-tagged from one machine's local cache; verify with {@code docker manifest inspect} that the
 * pushed reference is an image index covering both platforms, and that both {@code minio} and
 * {@code mc} still run inside it, before changing the digest below. Never push {@code :latest}.
 */
public final class MinioTestImage {

    public static final String PINNED =
        "ghcr.io/icreated/minio@sha256:0683da3477bed633ad5db374a10bb0eb57cc743cf5dadb1fcf867a35b1285b85";

    private MinioTestImage() {
    }
}
