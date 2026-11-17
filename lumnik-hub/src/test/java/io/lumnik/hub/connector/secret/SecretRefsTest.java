package io.lumnik.hub.connector.secret;

import io.lumnik.hub.secret.SecretResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a {@code *_env} reference resolves to: the tenant's {@code lm secret} registry, and nothing else (#2000).
 *
 * <p>The hub's own environment used to answer what the registry did not hold. It holds the hub's secrets too —
 * the registry's master key, the admin database password — so a manifest naming one sent it to a host of the
 * integrator's choice. The registry is per tenant; the environment is the whole deployment's.
 */
class SecretRefsTest {

    private static final String PROPERTY = "LUMNIK_SECRET_REFS_TEST_2000";

    /** A registry holding exactly what it is given — no tenant, no database, no cache. */
    private static SecretResolver registryOf(Map<String, String> entries) {
        return name -> Optional.ofNullable(entries.get(name));
    }

    @Test
    void theRegistryAnswers() {
        SecretRefs refs = new SecretRefs(registryOf(Map.of("BROKER_PW", "from-the-registry")));

        assertThat(refs.lookup("BROKER_PW")).isEqualTo("from-the-registry");
    }

    @Test
    void anOsVariableTheRegistryDoesNotHoldIsNotRead() {
        assumeTrue(System.getenv("PATH") != null);

        assertThat(new SecretRefs(registryOf(Map.of())).lookup("PATH")).isNull();
    }

    @Test
    void aJvmPropertyTheRegistryDoesNotHoldIsNotRead() {
        System.setProperty(PROPERTY, "from-a-jvm-property");
        try {
            assertThat(new SecretRefs(registryOf(Map.of())).lookup(PROPERTY)).isNull();
        } finally {
            System.clearProperty(PROPERTY);
        }
    }

    @Test
    void aMissIsNull_notAnEmptyStringAndNotAThrow() {
        // Every caller decides for itself what an unresolved reference means — rest drops the header,
        // kafka refuses the run by name. They can only do that if the miss arrives as one value.
        assertThat(SecretRefs.of(name -> null).lookup("BROKER_PW")).isNull();
    }

    @Test
    void aNullReferenceNameResolvesToNullWithoutConsultingTheRegistry() {
        // `password_env` absent from the config reaches resolution as null in several callers; the
        // registry would otherwise be asked for the name "null" and, in production, hit the database.
        SecretRefs refs = new SecretRefs(name -> { throw new AssertionError("the registry was consulted for a null name"); });

        assertThat(refs.lookup(null)).isNull();
    }
}
