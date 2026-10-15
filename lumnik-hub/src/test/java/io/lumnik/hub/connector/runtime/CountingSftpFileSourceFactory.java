package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.transport.CountingFileSource;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceFactory;
import io.lumnik.hub.connector.transport.sftp.SftpFileSourceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Test-only transport kind {@code sftp-counted}: the real SFTP source, wrapped so a test can count how many the engine
 * builds for a run and how many it closes. Reset with {@link #reset()} before each test.
 */
@ApplicationScoped
public class CountingSftpFileSourceFactory implements FileSourceFactory {

    private static final List<CountingFileSource> BUILT = Collections.synchronizedList(new ArrayList<>());

    @Inject SftpFileSourceFactory delegate;

    public static void reset() { BUILT.clear(); }

    public static int builds() { return BUILT.size(); }

    public static int closes() {
        synchronized (BUILT) {
            return BUILT.stream().mapToInt(CountingFileSource::closes).sum();
        }
    }

    @Override public String kind() { return "sftp-counted"; }

    @Override public FileSource build(Map<String, Object> cfg) {
        CountingFileSource counted = new CountingFileSource(delegate.build(cfg));
        BUILT.add(counted);
        return counted;
    }
}
