package io.lumnik.hub.testsupport;

import io.lumnik.hub.connector.transport.LocalRoot;
import io.lumnik.hub.connector.transport.LocalRoots;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * Every test application's local root: {@code /}, for every tenant. Tests read their own temp folders and
 * {@code target/test-classes}, wherever the JVM puts them: a manifest names them from {@code /}
 * ({@link TestLocalPaths#relative}), a reader built directly may still hand an absolute path over. A path relative to
 * the working directory does not resolve here — it reads from {@code /} — so a test names its files absolutely. The confinement
 * (#2001) is {@code LocalRootTest}'s, {@code LocalFileSourceTest}'s and {@code CsvLocalRootWiringTest}'s to pin, over
 * a real {@link LocalRoots}; production reads {@code lumnik.csv.local-root}, and unset reads nothing.
 */
@Alternative
@Priority(1)
@Singleton
public class AnywhereLocalRoots extends LocalRoots {

    private static final LocalRoot ANYWHERE = LocalRoot.at(Path.of("/"));

    public AnywhereLocalRoots() { super(ANYWHERE); }

    @Override public LocalRoot forTenant(long tenantId) { return ANYWHERE; }

    /**
     * A test's path from {@code /}: a manifest the apply door validates writes it relative (#2001 refuses an absolute
     * one there), see {@link TestLocalPaths#relative}; a reader built directly may still hand over an absolute one.
     */
    @Override public String resolve(long tenantId, String path) { return Path.of("/").resolve(path).toString(); }
}
