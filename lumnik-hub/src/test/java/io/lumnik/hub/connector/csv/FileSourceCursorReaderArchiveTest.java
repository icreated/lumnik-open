package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A8 (tribunal 2026-07-25): archiving must happen only for files whose chunk cursor was
 * already PERSISTED — never inside the read() that first returns the rows. The old
 * behavior archived (moved/deleted!) the source file mid-transaction: a crash after
 * markProcessed but before commit lost the file's rows unreplayably. The committed
 * cursor is the proof-of-commit; each read() first archives the cursor's entries, then
 * reads new data without marking it.
 */
class FileSourceCursorReaderArchiveTest {

    private OwnedRunScope scope = new OwnedRunScope("test");

    @AfterEach
    void closeRunScope() {
        scope.close();
    }

    private ConnectorContext ctx() {
        return ReaderTestContexts.of(scope);
    }

    private static final Instant T1 = Instant.parse("2026-07-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-25T10:05:00Z");

    static final class FakeSource implements FileSource {
        final List<FileDescriptor> files = new ArrayList<>();
        final List<String> marked = new ArrayList<>();

        @Override public Stream<FileDescriptor> listSince(Instant cursor) {
            return files.stream().filter(f -> !f.lastModified().isBefore(cursor));
        }
        @Override public Optional<FileDescriptor> stat(String path) {
            return files.stream().filter(f -> f.path().equals(path)).findFirst();
        }
        @Override public InputStream open(FileDescriptor file) {
            return new ByteArrayInputStream("id,name\n1,a\n".getBytes(StandardCharsets.UTF_8));
        }
        /** The move fails; with {@code vanish}, the file is gone from the source anyway (moved by someone else). */
        boolean fail, vanish;

        @Override public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
            marked.add(file.path());
            if (vanish || !fail) files.removeIf(f -> f.path().equals(file.path()));   // archive = gone from listing
            if (fail) throw new IllegalStateException("move refused");
        }
    }

    private final FakeSource source = new FakeSource();
    private final FileSourceRegistry registry = new FileSourceRegistry() {
        @Override public FileSource build(Map<String, Object> transportCfg) { return source; }
    };

    private FileSourceCursorReader newReader() {
        // A fresh reader per read() mirrors the engine: ChunkExecutor rebuilds it per chunk.
        Map<String, Object> config = Map.of(
            "transport", Map.of("kind", "sftp"),
            "after_process", "delete",
            "parser", Map.of("has_header", true));
        return new FileSourceCursorReader(config,
            CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true)), registry);
    }

    @Test
    void archiveHappensOneReadLater_neverInTheReadThatReturnsTheRows() {
        source.files.add(new FileDescriptor("/in/a.csv", 12, T1, null));
        source.files.add(new FileDescriptor("/in/b.csv", 12, T2, null));

        // read #1: returns a.csv's rows — the file must NOT be archived yet (its chunk
        // transaction has not committed; a crash here must leave the file in place).
        ReadResult<Map<String, Object>> r1 = newReader().read(500, ConnectorCursor.empty(), ctx());
        assertThat(r1).isInstanceOf(ReadResult.HasMore.class);
        assertThat(source.marked)
            .as("a file must never be archived inside the read() that first returns its rows")
            .isEmpty();

        // read #2: the persisted cursor now proves a.csv's chunk committed → archive it,
        // then read b.csv (not archived yet).
        ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();
        ReadResult<Map<String, Object>> r2 = newReader().read(500, c1, ctx());
        assertThat(source.marked).containsExactly("/in/a.csv");
        assertThat(r2).isInstanceOf(ReadResult.HasMore.class);

        // read #3: b.csv's commit is proven by the cursor → archived; nothing new → EOS.
        ConnectorCursor c2 = ((ReadResult.HasMore<Map<String, Object>>) r2).nextCursor();
        ReadResult<Map<String, Object>> r3 = newReader().read(500, c2, ctx());
        assertThat(source.marked).containsExactly("/in/a.csv", "/in/b.csv");
        assertThat(r3).isInstanceOf(ReadResult.EndOfStream.class);
    }

    @Test
    void leaveActionNeverMarks() {
        source.files.add(new FileDescriptor("/in/a.csv", 12, T1, null));
        Map<String, Object> config = Map.of(
            "transport", Map.of("kind", "sftp"),
            "after_process", "leave",
            "parser", Map.of("has_header", true));
        var overrides = CsvFileConnectorType.buildParserOverrides(Map.of("has_header", true));

        var r1 = new FileSourceCursorReader(config, overrides, registry).read(500, ConnectorCursor.empty(), ctx());
        var c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();
        new FileSourceCursorReader(config, overrides, registry).read(500, c1, ctx());

        assertThat(source.marked).isEmpty();
    }

    // #1386 review: a failed move counts the file as "not archived" only when it is still there as read — one
    // gone (or rewritten) between the check and the move is not said as staying in the source folder.
    @Test
    void a_failed_move_is_counted_only_when_the_file_is_still_there() {
        assertThat(notArchivedAfterAFailedMove(false)).containsExactly("/in/a.csv");
        assertThat(notArchivedAfterAFailedMove(true)).isEmpty();
    }

    private Set<String> notArchivedAfterAFailedMove(boolean vanish) {
        scope.close();
        scope = new OwnedRunScope("test");
        source.files.clear();
        source.marked.clear();
        source.fail = true;
        source.vanish = vanish;
        source.files.add(new FileDescriptor("/in/a.csv", 12, T1, null));
        var r1 = newReader().read(500, ConnectorCursor.empty(), ctx());
        newReader().read(500, ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor(), ctx());
        assertThat(source.marked).containsExactly("/in/a.csv");
        return scope.resource(new FileSourceCursorReader.NotArchivedKey(), FileSourceCursorReader.NotArchived::new).paths;
    }
}
