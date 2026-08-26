package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class BearerAuthProviderFactory implements AuthProviderFactory {

    private final Function<String, String> envLookup;
    private final SecretResolver secrets;

    @Inject
    public BearerAuthProviderFactory(SecretResolver secrets) {
        this(EnvSecrets::lookup, secrets);
    }

    /** Test ctor: stubbed env + resolver. */
    public BearerAuthProviderFactory(Function<String, String> envLookup, SecretResolver secrets) {
        this.envLookup = envLookup;
        this.secrets = secrets;
    }

    @Override public String kind() { return "bearer"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String envName = spec.config().get("token_env");
        String inline  = spec.config().get("token_value");
        String token = resolve(envName, inline);
        return new AuthProvider() {
            @Override public RestRequest apply(RestRequest r) {
                return token == null ? r : r.withHeader("Authorization", "Bearer " + token);
            }
            @Override public void probe() {
                if (token == null || token.isBlank())
                    throw new AuthConfigException("Bearer token unavailable (env=" + envName + ")");
            }
        };
    }

    private String resolve(String envName, String inline) {
        if (envName == null) return inline;
        return secrets.resolve(envName)
            .or(() -> Optional.ofNullable(envLookup.apply(envName)))
            .orElse(null);
    }
}
