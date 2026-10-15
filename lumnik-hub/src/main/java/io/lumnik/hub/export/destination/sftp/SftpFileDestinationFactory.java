package io.lumnik.hub.export.destination.sftp;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.sftp.SftpAuthMethod;
import io.lumnik.hub.connector.transport.sftp.SftpConnection;
import io.lumnik.hub.connector.transport.sftp.SftpConnectionFactory;
import io.lumnik.hub.export.destination.FileDestination;
import io.lumnik.hub.export.destination.FileDestinationFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import static io.lumnik.hub.export.destination.FileDestinationFactory.required;

/**
 * Builds one connected, authenticated SFTP destination per call — the connection is eager, not
 * lazy like the reader side's {@code ensureConnected()}, so a bad host key or bad credential
 * surfaces at build time, before any row has been read from the entity being exported.
 *
 * <p>Host-key verification is always strict: unlike the reader side, an export destination has no
 * {@code strict_host_key:false} escape hatch. A {@code kind:Export} manifest's
 * {@code host_key_fingerprint} is itself a required field
 * ({@code ExportManifestParser.parseSftp}), so there is never a declared-but-unchecked state to
 * fall back from.
 */
@ApplicationScoped
public class SftpFileDestinationFactory implements FileDestinationFactory {

    private final SecretRefs secrets;

    @Inject
    public SftpFileDestinationFactory(SecretRefs secrets) {
        this.secrets = secrets;
    }

    @Override
    public String kind() {
        return "sftp";
    }

    @Override
    public FileDestination build(Map<String, Object> cfg) {
        String host = required(cfg, "SFTP", "host");
        int port = ((Number) cfg.getOrDefault("port", 22)).intValue();
        String user = required(cfg, "SFTP", "user");
        String finalPath = required(cfg, "SFTP", "path");
        String hostKeyFingerprint = required(cfg, "SFTP", "host_key_fingerprint");

        SftpAuthMethod auth = SftpAuthMethod.fromConfig(cfg, secrets);

        SftpConnection connection =
            SftpConnectionFactory.connect(host, port, user, auth, true, hostKeyFingerprint);
        return new SftpFileDestination(connection, finalPath);
    }
}
