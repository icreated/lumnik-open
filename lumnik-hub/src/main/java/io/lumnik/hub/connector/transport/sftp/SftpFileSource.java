package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.SeekableSource;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private SftpConnection connection;
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

    /**
     * How long a probe waits for the server before the session is declared dead. Without a bound MINA waits for its
     * idle timeout (10 minutes by default) on a connection that silently stopped answering (a firewall dropped an idle
     * mapping while a chunk was being written): no reset, no reply.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    private volatile Duration probeTimeout = PROBE_TIMEOUT;

    public void setProbeTimeoutForTest(Duration timeout) {
        this.probeTimeout = timeout;
    }

    @Override
    public boolean usable() {
        SftpClient client;
        SftpConnection conn;
        synchronized (this) {                              // only to read the two fields, never across the round trip
            if (sftp == null) return true;                 // never connected, or closed: connects lazily
            client = sftp;
            conn = connection;
        }
        if (!client.isOpen()) return false;
        var answer = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> answer.complete(probe(client)));
        try {
            return answer.get(probeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException silent) {
            conn.close();                                  // aborts the stuck request; the caller replaces the source
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();            // a cancel or a shutdown: keep it for the run, and an
            return true;                                   // interrupt says nothing about the session
        }
    }

    private boolean probe(SftpClient client) {
        try {
            client.lstat(parentDir(pathGlob));
            return true;
        } catch (IOException io) {
            return answered(io);
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    /**
     * Did the SERVER answer? An {@code SftpException} carries the status of a reply, except {@code NO_CONNECTION} and
     * {@code CONNECTION_LOST}, which say the session is gone. Anything else (closed channel, timeout, broken pipe) is a
     * dead session too.
     */
    static boolean answered(IOException failure) {
        return failure instanceof SftpException e
            && e.getStatus() != SftpConstants.SSH_FX_NO_CONNECTION
            && e.getStatus() != SftpConstants.SSH_FX_CONNECTION_LOST;
    }

    /** Test hook, like {@link #setPasswordOverrideForTest}: the session dies under the source, which still believes it is connected. */
    public synchronized void dropConnectionForTest() {
        if (connection != null) connection.close();
    }

    synchronized boolean isConnectedForTest() {
        return sftp != null;
    }

    private synchronized void ensureConnected() {
        if (sftp != null) return;
        SftpAuthMethod effectiveAuth = passwordOverride != null
            ? new SftpAuthMethod.Password(passwordOverride)
            : auth;
        connection = SftpConnectionFactory.connect(host, port, user, effectiveAuth, strictHostKey, hostKeyFingerprint);
        sftp = connection.sftp();
    }

    @Override
    public Stream<FileDescriptor> listSince(Instant cursor) {
        ensureConnected();
        String parent = parentDir(pathGlob);
        Pattern regex = nameRegex();
        try {
            List<FileDescriptor> out = new ArrayList<>();
            for (SftpClient.DirEntry e : sftp.readDir(parent)) {
                FileDescriptor d = listed(parent, regex, e.getFilename(), e.getAttributes());
                if (d == null) continue;
                if (d.lastModified().isBefore(cursor)) continue;   // >= cursor: inclusive boundary (callers dedup via FileProcessingCursor)
                out.add(d);
            }
            out.sort(Comparator.comparing(FileDescriptor::lastModified));
            return out.stream();
        } catch (IOException e) {
            throw new RuntimeException("SFTP list failed for " + parent, e);
        }
    }

    /**
     * {@code lstat}: for anything but a symlink it reports what READDIR does. A symlink's READDIR
     * attributes are the server's choice (OpenSSH: the link's own; a server that follows links:
     * the target's), so a symlink is looked up in the listing itself.
     */
    @Override
    public Optional<FileDescriptor> stat(String path) {
        ensureConnected();
        String parent = parentDir(pathGlob);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!path.equals(childPath(parent, name))) return Optional.empty();
        try {
            SftpClient.Attributes a = sftp.lstat(path);
            if (a.isSymbolicLink()) {
                return listSince(Instant.MIN).filter(d -> d.path().equals(path)).findFirst();
            }
            return Optional.ofNullable(listed(parent, nameRegex(), name, a));
        } catch (IOException e) {
            if (e instanceof SftpException s && (s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE
                    || s.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH)) return Optional.empty();
            throw new RuntimeException("SFTP stat failed for " + path, e);
        }
    }

    /** The glob's last segment as a file-name regex — the one pattern both listing and stat match. */
    private Pattern nameRegex() {
        return globToRegex(pathGlob.substring(pathGlob.lastIndexOf('/') + 1));
    }

    /** The listing's filters, shared with {@link #stat}; {@code null} when the entry is not listed. */
    private static FileDescriptor listed(String parent, Pattern regex, String name, SftpClient.Attributes a) {
        if (name.equals(".") || name.equals("..")) return null;
        if (!regex.matcher(name).matches()) return null;
        long size = a.getSize();
        if (size <= 0) return null;
        Instant mtime = Instant.ofEpochMilli(a.getModifyTime().toMillis());
        return new FileDescriptor(childPath(parent, name), size, mtime, null);
    }

    private static String childPath(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
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
    // S2095: the handle is not leaked — ownership transfers to the returned stream, whose
    // close() closes it, and PositionedInputStream's constructor is three field assignments and
    // cannot throw between open and transfer. Every caller reads it in a try-with-resources or
    // closes it on the failure path (FileSourceCursorReader.openResumed,
    // CsvFileConnectorType.tryFirstWindow/openResumed). Sonar cannot see ownership cross a
    // constructor; closing here would close the handle the caller is about to read from.
    @SuppressWarnings("java:S2095")
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
                    String newPath = archive + (archive.endsWith("/") ? "" : "/") + file.archivedName();
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
        if (connection != null) connection.close();
        connection = null;
        sftp = null;
    }

}
