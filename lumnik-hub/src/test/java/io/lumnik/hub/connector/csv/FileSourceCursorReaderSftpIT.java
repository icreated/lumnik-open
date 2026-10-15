package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.CountingFileSource;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceRegistry;
import io.lumnik.hub.connector.transport.sftp.SftpFileSource;
import io.lumnik.hub.connector.transport.sftp.SftpFileSourceFactory;
import io.lumnik.hub.connector.transport.sftp.SftpTestHelper;
import io.lumnik.hub.connector.transport.sftp.SftpTestImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #898 against a real SFTP server: the reader keeps ONE source for the run (the scope's), replaces it ONCE when its
 * session died between two chunks, and never retries a chunk that failed after it had started.
 */
class FileSourceCursorReaderSftpIT {

    private static final int ROWS = 1600;
    private static final int CHUNK = 500;
    private static final String USER = "lumi";
    private static final String PASS = "lumipass";

    static GenericContainer<?> sftp;
    static int port;

    private OwnedRunScope scope;
    private RecordingRegistry registry;

    @BeforeAll
    static void startSftp() throws Exception {
        sftp = SftpTestImage.container(USER + ":" + PASS + ":1001::upload");
        sftp.start();
        port = sftp.getMappedPort(22);
    }

    @AfterAll
    static void stopSftp() {
        if (sftp != null) sftp.stop();
    }

    @BeforeEach
    void freshRun() throws Exception {
        StringBuilder csv = new StringBuilder("id,name\n");
        for (int i = 1; i <= ROWS; i++) csv.append(i).append(",row-").append(i).append('\n');
        try (var helper = new SftpTestHelper("localhost", port, USER, PASS)) {
            helper.put("upload/big.csv", csv.toString());
        }
        scope = new OwnedRunScope("sftp-it");
        registry = new RecordingRegistry();
    }

    @AfterEach
    void endRun() {
        scope.close();
    }

    /** Builds real SFTP sources, wraps each in a close counter and remembers it. */
    private static final class RecordingRegistry extends FileSourceRegistry {
        final List<CountingFileSource> built = new ArrayList<>();
        Consumer<CountingFileSource> onBuild = s -> { };

        @Override public FileSource build(Map<String, Object> transportCfg) {
            SftpFileSource real = (SftpFileSource) new SftpFileSourceFactory(SecretRefs.environmentOnly(n -> null))
                .build(transportCfg);
            real.setPasswordOverrideForTest(PASS);
            CountingFileSource counted = new CountingFileSource(real);
            built.add(counted);
            onBuild.accept(counted);
            return counted;
        }
    }

    private FileSourceCursorReader reader(String afterProcess) {
        Map<String, Object> parser = Map.of("encoding", "UTF-8", "delimiter", ",", "has_header", true);
        Map<String, Object> transport = Map.of("kind", "sftp", "host", "localhost", "port", port, "user", USER,
            "password_env", "SFTP_PASS_UNSET", "path", "/upload/*.csv", "strict_host_key", false);
        Map<String, Object> config = Map.of("transport", transport, "after_process", afterProcess, "parser", parser);
        return new FileSourceCursorReader(config, CsvFileConnectorType.buildParserOverrides(parser), registry);
    }

    private ConnectorContext ctx() {
        return ReaderTestContexts.of(scope);
    }

    private static void drop(CountingFileSource source) {
        ((SftpFileSource) source.delegate()).dropConnectionForTest();
    }

    /** Reads chunks until the end, calling {@code afterChunk} with the 1-based number of the chunk just read. */
    private List<Map<String, Object>> readAll(Consumer<Integer> afterChunk) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ConnectorCursor cursor = ConnectorCursor.empty();
        for (int chunk = 1; ; chunk++) {
            ReadResult<Map<String, Object>> res = reader("leave").read(CHUNK, cursor, ctx());
            if (res instanceof ReadResult.HasMore<Map<String, Object>> hm) {
                rows.addAll(hm.records());
                cursor = hm.nextCursor();
                afterChunk.accept(chunk);
            } else {
                rows.addAll(((ReadResult.EndOfStream<Map<String, Object>>) res).records());
                return rows;
            }
        }
    }

    private static void assertAllRowsOnceInOrder(List<Map<String, Object>> rows) {
        assertThat(rows).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) assertThat(rows.get(i).get("id")).isEqualTo(String.valueOf(i + 1));
    }

    @Test
    void a_session_that_stays_alive_is_never_replaced() {
        List<Map<String, Object>> rows = readAll(chunk -> { });

        assertAllRowsOnceInOrder(rows);
        assertThat(registry.built).as("four chunks, one connection").hasSize(1);
        assertThat(registry.built.get(0).closes()).as("the run is not over").isZero();
    }

    @Test
    void a_session_dropped_between_two_chunks_is_replaced_once_and_no_row_is_lost_or_doubled() {
        List<Map<String, Object>> rows = readAll(chunk -> {
            if (chunk == 1) drop(registry.built.get(0));      // the session dies while chunk 1 is being written
        });

        assertAllRowsOnceInOrder(rows);
        assertThat(registry.built).as("one replacement, not one build per chunk").hasSize(2);
        assertThat(registry.built.get(0).closes()).as("the dead source was closed at the start of chunk 2").isEqualTo(1);
        assertThat(registry.built.get(1).closes()).isZero();
    }

    @Test
    void a_session_dropped_in_the_middle_of_a_chunk_is_not_retried() throws Exception {
        registry.onBuild = source -> source.beforeOpen(() -> drop(source));     // dies when the chunk goes to read

        assertThatThrownBy(() -> reader("delete").read(CHUNK, ConnectorCursor.empty(), ctx()))
            .isInstanceOf(RuntimeException.class);

        assertThat(registry.built).as("no second build inside a chunk").hasSize(1);
        try (var helper = new SftpTestHelper("localhost", port, USER, PASS)) {
            assertThat(helper.exists("upload/big.csv")).as("nothing was deleted or archived").isTrue();
        }
    }
}
