package io.lumnik.hub.connector.secret;

import io.lumnik.hub.secret.SecretResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order a {@code *_env} reference resolves in, stated once so it stops being restated. Five call
 * sites carried their own copy of {@code secrets.resolve(n).or(env)} before this class existed, and
 * kafka — which had none — is what made the sixth copy the wrong answer.
 *
 * <p>The layered order is pinned end to end against a real database by
 * {@code AuthProviderLayeredResolutionTest}; this pins the rule itself, including the two cases a
 * container test cannot reach: a registry miss with nothing in the environment either, and a null
 * reference name.
 */
class SecretRefsTest {

    /** A registry holding exactly what it is given — no tenant, no database, no cache. */
    private static SecretResolver registryOf(Map<String, String> entries) {
        return name -> Optional.ofNullable(entries.get(name));
    }

    @Test
    void theRegistryIsConsultedBeforeTheProcessEnvironment() {
        SecretRefs refs = new SecretRefs(
            registryOf(Map.of("BROKER_PW", "from-the-registry")),
            name -> "from-the-environment");

        assertThat(refs.lookup("BROKER_PW")).isEqualTo("from-the-registry");
    }

    @Test
    void theProcessEnvironmentAnswersWhatTheRegistryDoesNotHold() {
        SecretRefs refs = SecretRefs.environmentOnly(
            name -> "BROKER_PW".equals(name) ? "from-the-environment" : null);

        assertThat(refs.lookup("BROKER_PW")).isEqualTo("from-the-environment");
    }

    @Test
    void neitherSourceHoldingItIsNull_notAnEmptyStringAndNotAThrow() {
        // Every caller decides for itself what an unresolved reference means — rest drops the header,
        // kafka refuses the run by name. They can only do that if the miss arrives as one value.
        SecretRefs refs = SecretRefs.environmentOnly(name -> null);

        assertThat(refs.lookup("BROKER_PW")).isNull();
    }

    @Test
    void aNullReferenceNameResolvesToNullWithoutConsultingEitherSource() {
        // `password_env` absent from the config reaches resolution as null in several callers; the
        // registry would otherwise be asked for the name "null" and, in production, hit the database.
        SecretRefs refs = new SecretRefs(
            name -> { throw new AssertionError("the registry was consulted for a null name"); },
            name -> { throw new AssertionError("the environment was consulted for a null name"); });

        assertThat(refs.lookup(null)).isNull();
    }
}
