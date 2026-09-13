package io.lumnik.hub.validator;

import io.lumnik.hub.api.connector.handler.ApplyUpsert;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard the last two closures were missing, and the reason this file exists rather than a
 * fourth javadoc paragraph.
 *
 * <p>Closing a connector's root key set means enumerating every key the door accepts — and the
 * door is wider than the connector's own package: {@link ApplyUpsert}, the apply preamble EVERY
 * type shares, reads {@code schedule} and {@code window} from the spec into the columns the
 * scheduler selects and fences on. An inventory taken from a connector's own code misses them:
 * it happened for kafka (#472, caught in review) and would have happened again here. The sweep
 * then advises removing a key that, removed, unschedules the connector or lifts its fence.
 *
 * <p>Anchored on {@link ApplyUpsert#PREAMBLE_KEYS} — the reader's own declaration, beside the
 * lines that read it — and on the CDI beans, so a fifth connector type and a sixth preamble key
 * both fail HERE instead of in a customer's manifest. Every registered validator is asked, which
 * in the open edition is three and in the closed edition four.
 */
@QuarkusTest
class PreambleKeysAreDeclaredTest {

    @Inject Instance<ConnectorValidator> validators;

    @Test void everyConnectorDeclaresEveryKeyTheApplyPreambleReads() {
        List<String> missing = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (ConnectorValidator v : validators) {
            kinds.add(v.kind());
            ConfigDef def = v.configDef();
            for (String key : ApplyUpsert.PREAMBLE_KEYS) {
                if (!def.declares(key)) missing.add(v.kind() + " does not declare spec." + key);
            }
        }
        assertEquals(List.of(), missing,
            "ApplyUpsert stores these from the spec for every type; a connector that does not "
                + "declare one either refuses it or, once closed, calls it 'ignored by the runtime'");
        // Fail-closed: an empty CDI list would make the loop above pass while asserting nothing.
        assertTrue(kinds.size() >= 3,
            () -> "expected the open edition's three connector validators at least, got " + kinds);
    }
}
