package io.lumnik.hub.export.destination;

import io.quarkus.test.junit.QuarkusTest;
import io.lumnik.hub.export.destination.s3.S3FileDestinationFactory;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class FileDestinationRegistryTest {

    @Inject FileDestinationRegistry registry;
    @Inject Instance<FileDestinationFactory> factories;

    /** Resolved through the same lookup the registry runs; building one would need a live store
     *  (the S3 factory pre-checks the key eagerly). */
    @Test
    void s3_kind_resolves_to_the_s3_factory() {
        assertThat(factories.stream().filter(f -> f.kind().equals("s3")))
            .singleElement().isInstanceOf(S3FileDestinationFactory.class);
    }

    @Test
    void unknown_kind_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.build(Map.of("kind", "nonsense")));
    }

    @Test
    void null_config_throws_there_is_no_default_destination() {
        assertThrows(IllegalArgumentException.class, () -> registry.build(null));
    }

    @Test
    void missing_kind_key_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.build(Map.of("host", "localhost")));
    }
}
