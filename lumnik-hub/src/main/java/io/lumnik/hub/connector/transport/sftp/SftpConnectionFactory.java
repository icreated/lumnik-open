package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.KeyPair;
import java.security.Security;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstraps one authenticated, host-key-verified {@link SftpConnection}. Extracted verbatim from
 * the original {@code SftpFileSource#ensureConnected}, so {@code SftpFileDestination} (#678)
 * shares the exact same fail-closed host-key wiring the reader side already has, instead of a
 * second copy of security-sensitive code that could silently drift from it.
 */
public final class SftpConnectionFactory {

    private SftpConnectionFactory() {
    }

    public static SftpConnection connect(String host, int port, String user, SftpAuthMethod auth,
                                          boolean strictHostKey, String hostKeyFingerprint) {
        // Ensure the Bouncy Castle JCE provider is registered so MINA SSHD can handle Ed25519 key
        // types (sshd's supported EdDSA provider since 2.15).
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        // Scoped to this one attempt on purpose. A field would outlive the connect that wrote it,
        // and a later failure of another kind would then report itself as a host-key refusal —
        // the same misattribution one variant further on.
        var refused = new AtomicReference<String>();
        SshClient sshClient = null;
        ClientSession session = null;
        try {
            sshClient = SshClient.setUpDefaultClient();
            // ALWAYS set a verifier. Leaving MINA's own default in place is not "strict": its
            // ClientBuilder.DEFAULT_SERVER_KEY_VERIFIER is AcceptAllServerKeyVerifier.
            sshClient.setServerKeyVerifier(serverKeyVerifier(strictHostKey, hostKeyFingerprint, refused));
            sshClient.start();
            session = sshClient.connect(user, host, port).verify(10_000).getSession();
            authenticate(session, auth);
            session.auth().verify(10_000);
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            return new SftpConnection(sshClient, session, sftp);
        } catch (Exception e) {
            // Whatever got started must be stopped here — the caller never receives a handle to
            // clean it up otherwise.
            new SftpConnection(sshClient, session, null).close();
            if (refused.get() != null) {
                throw new ConnectorException(hostKeyRefusal(host, port, hostKeyFingerprint, refused.get()), e);
            }
            throw new RuntimeException("SFTP connect failed: " + e.getMessage(), e);
        }
    }

    /**
     * Three cases, and the third one refuses. Falling back to "accept anything" when no
     * fingerprint is declared would rewrite the same untruth in better syntax: the setting would
     * still read as verification and still verify nothing.
     */
    private static ServerKeyVerifier serverKeyVerifier(boolean strictHostKey, String hostKeyFingerprint,
                                                         AtomicReference<String> refused) {
        if (!strictHostKey) {
            return AcceptAllServerKeyVerifier.INSTANCE;
        }
        return (clientSession, remoteAddress, serverKey) -> {
            if (HostKeyFingerprint.matches(hostKeyFingerprint, serverKey)) {
                return true;
            }
            refused.set(HostKeyFingerprint.of(serverKey));
            return false;
        };
    }

    /** The refusal teaches: it carries the fingerprint to paste and how to check it first. */
    private static String hostKeyRefusal(String host, int port, String hostKeyFingerprint, String presented) {
        String why = (hostKeyFingerprint == null || hostKeyFingerprint.isBlank())
            ? "no host_key_fingerprint is declared for this transport"
            : "it does not match the declared " + hostKeyFingerprint.trim();
        return "SFTP host key refused for " + host + ":" + port + " — the server presented "
            + presented + ", and " + why + ". Confirm that fingerprint out of band"
            + " (ssh-keyscan -p " + port + " " + host + " | ssh-keygen -lf -), then set"
            + " host_key_fingerprint: \"" + presented + "\" in the transport block."
            + " Setting strict_host_key: false connects without checking at all — which accepts"
            + " an impostor as readily as the real server.";
    }

    /** Register a password or public-key identity on the live session before auth().verify(). */
    private static void authenticate(ClientSession session, SftpAuthMethod auth) {
        if (auth instanceof SftpAuthMethod.Password p && p.value() != null) {
            session.addPasswordIdentity(p.value());
        } else if (auth instanceof SftpAuthMethod.Key k) {
            for (KeyPair kp : parseKeyPairs(k)) {
                session.addPublicKeyIdentity(kp);
            }
        }
    }

    private static Collection<KeyPair> parseKeyPairs(SftpAuthMethod.Key k) {
        Collection<KeyPair> pairs;
        try {
            pairs = SecurityUtils.getKeyPairResourceParser()
                .loadKeyPairs(null, NamedResource.ofName("sftp-key"),
                              FilePasswordProvider.EMPTY,
                              k.pemContent());
        } catch (Exception e) {
            throw new ConnectorException(
                "SFTP key auth: failed to parse private key: " + e.getMessage(), e);
        }
        if (pairs == null || pairs.isEmpty()) {
            throw new ConnectorException("SFTP key auth: no key pair parsed from key_env PEM");
        }
        return pairs;
    }
}
