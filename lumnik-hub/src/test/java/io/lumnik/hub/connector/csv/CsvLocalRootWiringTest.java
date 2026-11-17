package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.transport.LocalRoot;
import io.lumnik.hub.connector.transport.LocalRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2001: a {@code csv-file} connector's local {@code transport.path} reads inside its tenant's folder of
 * {@code lumnik.csv.local-root} only — the wiring, the connector type handing the operator's root, narrowed to the run's
 * tenant, to the reader it builds. What the root accepts is
 * {@code LocalRootTest}'s to pin.
 */
class CsvLocalRootWiringTest {

    @TempDir Path drop;

    private final CsvFileConnectorType type = new CsvFileConnectorType();

    private static ConnectorContext ctx(String path) {
        return new ConnectorContext(null, null, null, 1L, "c", CsvFileConnectorType.TYPE_ID,
            Map.of("transport", Map.of("kind", "local", "path", path)), null, null, null, null, null, null);
    }

    @Test
    void a_connector_reads_its_tenants_folder_of_the_root_only() throws Exception {
        type.localRoots = new LocalRoots(LocalRoot.at(drop));
        Path own = Files.createDirectories(drop.resolve("1"));
        Files.writeString(own.resolve("orders.csv"), "id\n1\n");
        Path other = Files.createDirectories(drop.resolve("2"));
        Files.writeString(other.resolve("orders.csv"), "id\n2\n");

        assertThatCode(() -> type.reader(ctx("orders.csv"))).doesNotThrowAnyException();
        assertThatCode(() -> type.reader(ctx("*.csv"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> type.reader(ctx("../2/*.csv")))
            .as("tenant 1 never reads tenant 2's folder")
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("lumnik.csv.local-root/<tenant id>");
        assertThatThrownBy(() -> type.reader(ctx(other + "/orders.csv")))
            .as("an absolute path, even to a folder of the root")
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("relative to its tenant's folder");
        assertThatThrownBy(() -> type.reader(ctx("/etc/hostname"))).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void unset_the_local_transport_reads_nothing() {
        type.localRoots = new LocalRoots(LocalRoot.of(Optional.empty()));

        assertThatThrownBy(() -> type.reader(ctx("*.csv")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("LUMNIK_CSV_LOCAL_ROOT");
    }
}
