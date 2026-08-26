package io.lumnik.hub.connector.transport.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer instrumentation for FileSource operations.
 *
 * <p>Counters and timers are registered lazily on first use so that unused transports
 * never appear in metrics output.
 */
@ApplicationScoped
public class FileSourceMetrics {

    private static final String TAG_TRANSPORT = "transport";
    private static final String TAG_CONNECTOR = "connector";

    @Inject MeterRegistry registry;

    /** Records files listed and the time taken for the list operation. */
    public void recordListed(String transport, String connector, int count, long durationMs) {
        registry.counter("csv_filesource_list_total",
            TAG_TRANSPORT, transport, TAG_CONNECTOR, connector).increment(count);
        registry.timer("csv_filesource_list_duration_seconds",
            TAG_TRANSPORT, transport, TAG_CONNECTOR, connector).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Records a file open attempt; {@code result} is {@code "ok"} or {@code "error"}. */
    public void recordOpen(String transport, String connector, String result) {
        registry.counter("csv_filesource_open_total",
            TAG_TRANSPORT, transport, TAG_CONNECTOR, connector, "result", result).increment();
    }

    /** Records bytes read from a remote (buffered) file. */
    public void recordBytesRead(String transport, String connector, long bytes) {
        registry.counter("csv_filesource_bytes_read_total",
            TAG_TRANSPORT, transport, TAG_CONNECTOR, connector).increment(bytes);
    }

    /** Records a markProcessed call; {@code action} is the AfterProcessAction name; {@code result} is {@code "ok"} or {@code "error"}. */
    public void recordAfterProcess(String transport, String action, String result) {
        registry.counter("csv_filesource_after_process_total",
            TAG_TRANSPORT, transport, "action", action, "result", result).increment();
    }
}
