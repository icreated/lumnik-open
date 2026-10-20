package io.lumnik.hub.connector.secret;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;
import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Function;

/**
 * What a {@code *_env} key in a manifest resolves to: the tenant's {@code lm secret} registry first,
 * the hub process's own environment second (an OS variable, then a JVM system property for dev/test
 * injection). A manifest names the secret; it never carries it.
 *
 * <p>The order was written out five times before this class existed — once in each REST auth factory
 * and once in each of the two CSV file transports — and kafka, which had no copy at all, read
 * {@link EnvSecrets} directly, so {@code lm secret set} did nothing for it (kafka bench, campaign of
 * 2026-09-11). One of the five copies was already wrong: the two transports read the system property
 * BEFORE the OS variable. A rule in five copies is four that can go quietly false.
 *
 * <p><b>Two paths deliberately do NOT come here</b>, and each says so where an integrator reads
 * it: {@code jdbc-generic}'s {@code password_env} and REST's declarative {@code headers[].value_env}.
 * The reason is about behaviour, not design — folding the others in changed nothing, because
 * {@code rest} basic auth and {@code sftp} already read the registry first and {@code kafka} read
 * nothing at all. Folding these two WOULD change what a live connector resolves: a tenant secret
 * would start outranking an environment variable of the same name on a connector that has been
 * running for months. SOAP's {@code {{secret:}}} took that change on purpose (#1246): its name
 * says registry, and an installer could not set it with {@code lm secret}; {@code {{env:}}} stays
 * environment-only.
 *
 * <p>Resolution reads the tenant from {@code CurrentTenantHolder}, so a caller must already be in a
 * tenant scope — the run thread is ({@code ConnectorEngine} wraps every run in
 * {@code TenantScope}), and a request thread is. A caller outside one gets
 * {@link IllegalStateException} from the registry rather than a silent fall-through to the
 * environment: a miss and a mis-wiring must not look alike.
 */
@ApplicationScoped
public class SecretRefs {

    private final SecretResolver registry;
    private final Function<String, String> environment;

    @Inject
    public SecretRefs(SecretResolver registry) { this(registry, EnvSecrets::lookup); }

    /** A stubbed registry and a stubbed environment — for tests, and for {@link #environmentOnly}. */
    public SecretRefs(SecretResolver registry, Function<String, String> environment) {
        this.registry = registry;
        this.environment = environment;
    }

    /**
     * Resolution with no registry behind it: every reference comes from {@code environment}. The
     * shape a test has — an integration test drives a factory directly, with no tenant and so no
     * registry to read. Production always injects.
     */
    public static SecretRefs environmentOnly(Function<String, String> environment) {
        return new SecretRefs(name -> Optional.empty(), environment);
    }

    /**
     * @param name the value of a {@code *_env} key, or {@code null} when the key is absent
     * @return the secret, or {@code null} when neither source holds it — every caller decides for
     *         itself what an unresolved reference means
     */
    public String lookup(String name) {
        if (name == null) return null;
        return registry.resolve(name).orElseGet(() -> environment.apply(name));
    }
}
