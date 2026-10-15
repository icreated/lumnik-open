package io.lumnik.hub.connector.transport;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Wraps a real source and counts its {@code close()} calls: {@code usable()} is deliberately {@code true} after a close
 * (a closed source reconnects lazily), so closedness cannot be read off the source itself. {@code beforeOpen} lets a test
 * break the session at the exact moment a chunk starts using it.
 */
public final class CountingFileSource implements FileSource, SeekableSource {

    private final FileSource delegate;
    private final AtomicInteger closes = new AtomicInteger();
    private volatile Runnable beforeOpen = () -> { };

    public CountingFileSource(FileSource delegate) {
        this.delegate = delegate;
    }

    public FileSource delegate() { return delegate; }

    public int closes() { return closes.get(); }

    public void beforeOpen(Runnable hook) { this.beforeOpen = hook; }

    @Override public Stream<FileDescriptor> listSince(Instant cursor) { return delegate.listSince(cursor); }

    @Override public Optional<FileDescriptor> stat(String path) { return delegate.stat(path); }

    @Override public boolean usable() { return delegate.usable(); }

    @Override public InputStream open(FileDescriptor file) {
        beforeOpen.run();
        return delegate.open(file);
    }

    @Override public InputStream openAt(FileDescriptor file, long byteOffset) {
        beforeOpen.run();
        return ((SeekableSource) delegate).openAt(file, byteOffset);
    }

    @Override public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
        delegate.markProcessed(file, action, cfg);
    }

    @Override public void close() {
        closes.incrementAndGet();
        delegate.close();
    }
}
