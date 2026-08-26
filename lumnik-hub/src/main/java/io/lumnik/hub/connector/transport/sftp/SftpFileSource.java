package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.SeekableSource;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.Security;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SftpFileSource implements FileSource, SeekableSource {

    private final String host;
    private final int port;
    private final String user;
    private final SftpAuthMethod auth;
    private final String pathGlob;
    private final boolean strictHostKey;
    private final String hostKeyFingerprint;

    private SshClient sshClient;
    private ClientSession session;
    private SftpClient sftp;

    /** Test-only password override. */
    private String passwordOverride;

    public SftpFileSource(String host, int port, String user, SftpAuthMethod auth,
                          String pathGlob, boolean strictHostKey, String hostKeyFingerprint) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.auth = auth;
        this.pathGlob = pathGlob;
        this.strictHostKey = strictHostKey;
        this.hostKeyFingerprint = hostKeyFingerprint;
    }

    public void setPasswordOverrideForTest(String pwd) {
        this.passwordOverride = pwd;
    }

    private synchronized void ensureConnected() {
        if (sftp != null) return;
        // Ensure the EdDSA JCE provider is registered so MINA SSHD can handle Ed25519 key types.
        if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new EdDSASecurityProvider());
        }
        // Scoped to this one attempt on purpose. A field would outlive the connect that wrote
        // it, and a later failure of another kind would then report itself as a host-key refusal
        // — the same misattribution one variant further on.
        var refused = new AtomicReference<String>();
        try {
            sshClient = SshClient.setUpDefaultClient();
            // ALWAYS set a verifier. Leaving MINA's own default in place is not "strict": its
            // ClientBuilder.DEFAULT_SERVER_KEY_VERIFIER is AcceptAllServerKeyVerifier, so the
            // branch that meant to verify was accepting every key just as widely as the branch
            // that said so out loud.
            sshClient.setServerKeyVerifier(serverKeyVerifier(refused));
            sshClient.start();
            session = sshClient.connect(user, host, port).verify(10_000).getSession();
            authenticate();
            session.auth().verify(10_000);
            sftp = SftpClientFactory.instance().createSftpClient(session);
        } catch (Exception e) {
            // A refused host key is the DEFAULT path now, not a rare accident, so a scheduled
            // connector walks this branch on every tick. Whatever got started must be stopped
            // here: the next attempt reassigns sshClient, and a client left running would lose
            // its last reference and could never be stopped again.
            close();
            if (refused.get() != null) {
                throw new ConnectorException(hostKeyRefusal(refused.get()), e);
            }
            throw new RuntimeException("SFTP connect failed: " + e.getMessage(), e);
        }
    }

    /**
     * Three cases, and the third one refuses. Falling back to "accept anything" when no
     * fingerprint is declared would rewrite the same untruth in better syntax: the setting
     * would still read as verification and still verify nothing.
     */
    private ServerKeyVerifier serverKeyVerifier(AtomicReference<String> refused) {
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
    private String hostKeyRefusal(String presented) {
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
    private void authenticate() {
        String pwd = passwordOverride != null ? passwordOverride
            : (auth instanceof SftpAuthMethod.Password p ? p.value() : null);
        if (pwd != null) {
            session.addPasswordIdentity(pwd);
        } else if (auth instanceof SftpAuthMethod.Key k) {
            for (KeyPair kp : parseKeyPairs(k)) {
                session.addPublicKeyIdentity(kp);
            }
        }
    }

    private java.util.Collection<KeyPair> parseKeyPairs(SftpAuthMethod.Key k) {
        java.util.Collection<KeyPair> pairs;
        try {
            pairs = org.apache.sshd.common.util.security.SecurityUtils.getKeyPairResourceParser()
                .loadKeyPairs(null, org.apache.sshd.common.NamedResource.ofName("sftp-key"),
                              org.apache.sshd.common.config.keys.FilePasswordProvider.EMPTY,
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

    @Override
    public Stream<FileDescriptor> listSince(Instant cursor) {
        ensureConnected();
        String parent = parentDir(pathGlob);
        String pattern = pathGlob.substring(pathGlob.lastIndexOf('/') + 1);
        Pattern regex = globToRegex(pattern);
        try {
            List<FileDescriptor> out = new ArrayList<>();
            for (SftpClient.DirEntry e : sftp.readDir(parent)) {
                if (e.getFilename().equals(".") || e.getFilename().equals("..")) continue;
                if (!regex.matcher(e.getFilename()).matches()) continue;
                long size = e.getAttributes().getSize();
                if (size <= 0) continue;
                Instant mtime = Instant.ofEpochMilli(e.getAttributes().getModifyTime().toMillis());
                if (mtime.isBefore(cursor)) continue;   // >= cursor: inclusive boundary (callers dedup via FileProcessingCursor)
                String fullPath = parent.endsWith("/") ? parent + e.getFilename() : parent + "/" + e.getFilename();
                out.add(new FileDescriptor(fullPath, size, mtime, null));
            }
            out.sort(Comparator.comparing(FileDescriptor::lastModified));
            return out.stream();
        } catch (IOException e) {
            throw new RuntimeException("SFTP list failed for " + parent, e);
        }
    }

    @Override
    public InputStream open(FileDescriptor file) {
        ensureConnected();
        try {
            return sftp.read(file.path());
        } catch (IOException e) {
            throw new RuntimeException("SFTP open failed for " + file.path(), e);
        }
    }

    /**
     * Positioned read over a fresh handle: each underlying {@code read} request targets
     * {@code position} directly via the low-level {@code SftpClient.read(handle, fileOffset,
     * ...)} API — a real seek, not a discard loop over the skipped prefix.
     */
    @Override
    public InputStream openAt(FileDescriptor file, long byteOffset) {
        ensureConnected();
        if (byteOffset < 0) {
            throw new IllegalArgumentException(
                "SftpFileSource.openAt: negative offset " + byteOffset + " for " + file.path());
        }
        try {
            SftpClient.CloseableHandle handle = sftp.open(file.path());
            return new PositionedInputStream(sftp, handle, byteOffset);
        } catch (IOException e) {
            throw new RuntimeException(
                "SFTP openAt failed for " + file.path() + " at offset " + byteOffset, e);
        }
    }

    /** Streams an open SFTP handle from a fixed starting offset, advancing it read by read. */
    private static final class PositionedInputStream extends InputStream {
        private final SftpClient sftp;
        private final SftpClient.CloseableHandle handle;
        private long position;
        private boolean eof;

        PositionedInputStream(SftpClient sftp, SftpClient.CloseableHandle handle, long startOffset) {
            this.sftp = sftp;
            this.handle = handle;
            this.position = startOffset;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            int n = sftp.read(handle, position, b, off, len);
            if (n < 0) {
                eof = true;
                return -1;
            }
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            handle.close();
        }
    }

    @Override
    public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
        ensureConnected();
        try {
            switch (action) {
                case LEAVE -> {}
                case DELETE -> sftp.remove(file.path());
                case MOVE -> {
                    String archive = (String) cfg.getOrDefault("archive_path", "/archive/");
                    String newPath = archive + (archive.endsWith("/") ? "" : "/") + file.name();
                    mkdirs(parentDir(newPath));
                    sftp.rename(file.path(), newPath);
                }
                case CUSTOM_KOTLIN -> throw new IllegalStateException(
                    "CUSTOM_KOTLIN must be invoked by the connector runtime");
            }
        } catch (IOException e) {
            throw new RuntimeException("SFTP markProcessed failed for " + file.path(), e);
        }
    }

    private void mkdirs(String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) {
                current.append("/");
                continue;
            }
            current.append(p).append("/");
            try {
                sftp.mkdir(current.toString());
            } catch (IOException ignored) {}
        }
    }

    private static String parentDir(String pathGlob) {
        int slash = pathGlob.lastIndexOf('/');
        return slash >= 0 ? pathGlob.substring(0, slash) : ".";
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                default  -> sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    @Override
    public void close() {
        try { if (sftp     != null) sftp.close();     } catch (Exception ignored) {}
        try { if (session  != null) session.close();  } catch (Exception ignored) {}
        try { if (sshClient != null) sshClient.stop(); } catch (Exception ignored) {}
        // Clear the handles too, so close() is idempotent and ensureConnected() always starts
        // from a known-empty state rather than overwriting a live client.
        sftp = null;
        session = null;
        sshClient = null;
    }

}
