package io.lumnik.hub.export.destination.sftp;

import io.lumnik.hub.export.destination.FileDestinationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SftpFileDestinationFactoryTest {

    private static Map<String, Object> cfgWithout(String field) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
            "host", "sftp.example.com",
            "user", "partner",
            "path", "/out/x.csv",
            "host_key_fingerprint", "SHA256:abc"));
        cfg.remove(field);
        return cfg;
    }

    @Test
    void a_missing_required_field_is_refused_before_any_connection() {
        var factory = new SftpFileDestinationFactory(null);
        for (String field : new String[] {"host", "user", "path", "host_key_fingerprint"}) {
            assertThatThrownBy(() -> factory.build(cfgWithout(field)))
                .isInstanceOf(FileDestinationException.class)
                .hasMessage("SFTP destination: '" + field + "' is required");
        }
    }

    @Test
    void a_blank_path_is_refused() {
        Map<String, Object> cfg = cfgWithout("path");
        cfg.put("path", "  ");
        assertThatThrownBy(() -> new SftpFileDestinationFactory(null).build(cfg))
            .isInstanceOf(FileDestinationException.class)
            .hasMessage("SFTP destination: 'path' is required");
    }
}
