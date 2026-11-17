package io.lumnik.hub.connector.secret;

import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Function;

/**
 * What a {@code *_env} key in a manifest resolves to: the tenant's {@code lm secret} registry, and nothing else. A
 * manifest names the secret; it never carries it.
 *
 * <p><b>The hub's environment is not a source (#2000).</b> It used to answer what the registry did not hold — an OS
 * variable, then a JVM property. But it holds the hub's own secrets beside a source's: the registry's master key,
 * the admin database password. A manifest naming one sent it to a host of the integrator's choice, from any tenant.
 * A prefix would have kept the hub's secrets out, not one tenant's sources from another's: the environment is the
 * whole deployment's, the registry is per tenant. The {@code _env} suffix stays, so manifests keep their syntax.
 *
 * <p>Every manifest reference comes here, SOAP's {@code {{env:}}} and jdbc's {@code password_env} included: the rule
 * stated once. No production code reads {@code System.getenv} or {@code System.getProperty};
 * {@code scripts/check-no-process-environment.sh} keeps it so.
 *
 * <p>Resolution reads the tenant from {@code CurrentTenantHolder}, so a caller must already be in a tenant scope —
 * the run thread is ({@code ConnectorEngine} wraps every run in {@code TenantScope}), and a request thread is. A
 * caller outside one gets {@link IllegalStateException} from the registry rather than a silent miss: a miss and a
 * mis-wiring must not look alike.
 */
@ApplicationScoped
public class SecretRefs {

    private final SecretResolver registry;

    @Inject
    public SecretRefs(SecretResolver registry) { this.registry = registry; }

    /**
     * Resolution from a fixed lookup, with no tenant and no database behind it — the shape a test has when it drives
     * a factory directly. Production always injects.
     */
    public static SecretRefs of(Function<String, String> lookup) {
        return new SecretRefs(name -> Optional.ofNullable(lookup.apply(name)));
    }

    /**
     * @param name the value of a {@code *_env} key, or {@code null} when the key is absent
     * @return the secret, or {@code null} when the registry does not hold it — every caller decides for itself what
     *         an unresolved reference means
     */
    public String lookup(String name) {
        if (name == null) return null;
        return registry.resolve(name).orElse(null);
    }
}
