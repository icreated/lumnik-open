package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.RunScope;
import io.lumnik.hub.connector.api.types.SyncMode;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** A context that carries only what a CSV reader reads from it: the run scope. */
final class ReaderTestContexts {

    private ReaderTestContexts() {}

    /**
     * A context whose scope holds nothing: every {@code resource} ask builds a new value and keeps no reference to
     * it, so there is nothing to close. For tests that only need the reader to run. A test that counts what the run
     * builds, or that needs a resource held for the run and closed at its end, opens an {@code OwnedRunScope}, closes
     * it itself and passes it to {@link #of}.
     */
    static ConnectorContext newRun() {
        return of(new RunScope() {
            @Override
            public <T extends AutoCloseable> T resource(Object key, Supplier<T> factory) {
                return factory.get();
            }

            @Override
            public void invalidate(Object key) {
                // nothing is kept
            }
        });
    }

    static ConnectorContext of(RunScope scope) {
        return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "erp", "csv-file",
            Map.of(), new SyncMode.Incremental(), null, null, null, Clock.systemUTC(), null, scope);
    }
}
