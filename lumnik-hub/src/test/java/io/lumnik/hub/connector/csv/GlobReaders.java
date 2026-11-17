package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.transport.LocalRoot;

import java.nio.file.Path;

/**
 * A {@link CsvFileConnectorType.CsvGlobCursorReader} over a test's own files, wherever the test put them: the reader's
 * confinement to {@code lumnik.csv.local-root} (#2001) is {@code LocalRootTest}'s and {@code LocalFileSourceTest}'s to
 * pin, not every reading test's. Main code has one constructor, and it takes the root.
 */
final class GlobReaders {

    static final LocalRoot ANYWHERE = LocalRoot.at(Path.of("/"));

    private GlobReaders() {}

    static CsvFileConnectorType.CsvGlobCursorReader of(String pattern, CsvFileConnectorType.ParserOverrides overrides) {
        return of(pattern, overrides, false);
    }

    static CsvFileConnectorType.CsvGlobCursorReader of(String pattern, CsvFileConnectorType.ParserOverrides overrides,
                                                        boolean holdsTheSourceState) {
        return of(pattern, overrides, holdsTheSourceState, false);
    }

    static CsvFileConnectorType.CsvGlobCursorReader of(String pattern, CsvFileConnectorType.ParserOverrides overrides,
                                                        boolean holdsTheSourceState, boolean oneFilePerChunk) {
        return new CsvFileConnectorType.CsvGlobCursorReader(ANYWHERE, pattern, overrides, holdsTheSourceState, oneFilePerChunk);
    }
}
