package io.lumnik.hub.connector.transport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The operator's {@code lumnik.csv.local-root}, resolved once, and the folder of it each tenant reads (#2001): every
 * local reader asks here, so none can read the whole root, or another tenant's folder of it.
 */
@Singleton
public class LocalRoots {

    private final LocalRoot root;

    @Inject
    public LocalRoots(@ConfigProperty(name = "lumnik.csv.local-root") Optional<String> configured) {
        this(LocalRoot.of(configured));
    }

    /** Over a root already resolved: a test's. */
    public LocalRoots(LocalRoot root) { this.root = root; }

    /** What a connector of {@code tenantId} reads: {@code <root>/<tenant id>}. */
    public LocalRoot forTenant(long tenantId) { return root.forTenant(tenantId); }

    /** A manifest's relative {@code path}, as the absolute path it names in {@code tenantId}'s folder. */
    public String resolve(long tenantId, String path) { return forTenant(tenantId).resolve(path); }
}
