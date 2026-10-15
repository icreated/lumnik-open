package io.lumnik.hub.connector.transport.sftp;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The SFTP test image, pinned by digest and named once for every SFTP IT.
 *
 * <p>{@code atmoz/sftp} has no living version tag: {@code alpine-3.4}..{@code alpine-3.7} are all
 * from 2017-2018 on long-EOL Alpine, {@code latest} has been frozen since 2024, and the maintained
 * {@code :alpine} carries no version at all — it is rebuilt against the current Alpine every few
 * weeks. The house convention is a version tag; here upstream offers none, so the digest is what
 * serves its intent: a gate that does not move on its own.
 *
 * <p>It matters more than a pin usually would because the SFTP ITs wait on an upstream log line,
 * {@code Server listening on 0.0.0.0}. A silent rebuild changing sshd's log format would turn all
 * four of them red — at 60 s each — in a tree nobody touched.
 *
 * <p>To bump it: take the digest {@code :alpine} currently resolves to, confirm it is pullable
 * <em>by reference</em> (remove the local tag first, or the cache answers for the registry), and
 * change the one line below.
 */
public final class SftpTestImage {

    public static final DockerImageName PINNED = DockerImageName.parse(
        "atmoz/sftp@sha256:dfa61ea26a339a46bda98accfa31f24d912b19812517ecaf3be49e6a15422b24");

    /**
     * A started-ready container of the pinned image: {@code commandLine} is atmoz/sftp's own
     * ({@code user:password:uid:gid:dir}). It waits for sshd to LISTEN, not merely for the published port to accept: the
     * docker-proxy binds that port at container start while the entrypoint is still generating host keys, and a
     * connection landing in that window is accepted and then dropped. The log line is the one this class's javadoc warns
     * about, so it lives here once.
     */
    public static GenericContainer<?> container(String commandLine) {
        return new GenericContainer<>(PINNED)
            .withCommand(commandLine)
            .withExposedPorts(22)
            .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0.*", 1));
    }

    private SftpTestImage() {
    }
}
