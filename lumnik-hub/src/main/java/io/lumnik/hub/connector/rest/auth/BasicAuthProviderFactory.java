package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class BasicAuthProviderFactory implements AuthProviderFactory {

    private final Function<String, String> envLookup;
    private final SecretResolver secrets;

    @Inject
    public BasicAuthProviderFactory(SecretResolver secrets) { this(EnvSecrets::lookup, secrets); }

    /** Test ctor: stubbed env + resolver. */
    public BasicAuthProviderFactory(Function<String, String> envLookup, SecretResolver secrets) {
        this.envLookup = envLookup;
        this.secrets = secrets;
    }

    @Override public String kind() { return "basic"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String user = spec.config().get("username");
        String envName = spec.config().get("password_env");
        String pwd = resolve(envName);
        String encoded = Base64.getEncoder().encodeToString(
            (user + ":" + (pwd == null ? "" : pwd)).getBytes(StandardCharsets.UTF_8));
        return new AuthProvider() {
            @Override public RestRequest apply(RestRequest r) {
                return r.withHeader("Authorization", "Basic " + encoded);
            }
            @Override public void probe() {
                if (pwd == null || pwd.isBlank())
                    throw new AuthConfigException("Basic password unavailable (env=" + envName + ")");
            }
        };
    }

    private String resolve(String envName) {
        if (envName == null) return null;
        return secrets.resolve(envName)
            .or(() -> Optional.ofNullable(envLookup.apply(envName)))
            .orElse(null);
    }
}
