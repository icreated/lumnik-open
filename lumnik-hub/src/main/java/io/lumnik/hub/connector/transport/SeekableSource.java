package io.lumnik.hub.connector.transport;

import java.io.InputStream;

/**
 * Capability interface: a {@link FileSource} whose transport can open its stream starting
 * at an arbitrary byte offset into the file's content, instead of always from byte 0.
 *
 * <p>Implemented only by transports that offer a real positioned read (local
 * {@code FileChannel}/{@code SeekableByteChannel} seek, S3 ranged {@code GetObject}, SFTP
 * read-from-offset) — never a read-and-discard loop over the skipped prefix, which would
 * defeat the entire point of seeking. A transport that has no such capability simply does
 * not implement this interface; callers detect it with an {@code instanceof} check and fall
 * back to the byte-0 stream + skip-parse path when it is absent. This is deliberate
 * interface segregation: absence of the interface IS the configuration — no registry, no
 * flag, no third mode.
 *
 * <p><b>Contract:</b> the returned stream's first byte is exactly the byte at
 * {@code byteOffset} bytes into the file's content (0-based). Implementations MUST throw —
 * never silently start at byte 0 — when the offset cannot be honoured (a negative offset, or
 * a transport-level failure while seeking/opening). An offset at or beyond end-of-file is
 * honoured, not an error: it yields a stream that reads as empty, since that faithfully
 * represents "nothing after this point" for a file that has not shrunk.
 */
public interface SeekableSource {
    InputStream openAt(FileDescriptor file, long byteOffset);
}
