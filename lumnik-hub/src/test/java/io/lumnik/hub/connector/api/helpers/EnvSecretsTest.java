package io.lumnik.hub.connector.api.helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import org.junit.jupiter.api.Test;

class EnvSecretsTest {

    private static final String ABSENT = "LUMNIK_ENVSECRETS_TEST_XYZ";

    @Test
    void lookup_fallsBackToSystemProperty_whenEnvAbsent() {
        // Guaranteed-absent OS env var; inject a value via system property and assert the fallback.
        assumeThat(System.getenv(ABSENT)).isNull();
        String prev = System.getProperty(ABSENT);
        try {
            System.setProperty(ABSENT, "v");
            assertThat(EnvSecrets.lookup(ABSENT)).isEqualTo("v"); }
        finally {
            if (prev == null)
                System.clearProperty(ABSENT);
            else
                System.setProperty(ABSENT, prev); }
    }

    @Test
    void lookup_returnsNull_whenNeitherEnvNorSysprop() {
        assumeThat(System.getenv(ABSENT)).isNull();
        assertThat(System.getProperty(ABSENT)).isNull();
        assertThat(EnvSecrets.lookup(ABSENT)).isNull();
    }

    @Test
    void lookup_prefersOsEnv_overSysprop() {
        // PATH is virtually always set in the OS env; env must win over any system property.
        String pathEnv = System.getenv("PATH");
        assumeThat(pathEnv).isNotNull();
        String prev = System.getProperty("PATH");
        try {
            System.setProperty("PATH", "sysprop-should-lose");
            assertThat(EnvSecrets.lookup("PATH")).isEqualTo(pathEnv);
        } finally {
            if (prev == null)
                System.clearProperty("PATH");
            else
                System.setProperty("PATH", prev);
        }
    }
}
