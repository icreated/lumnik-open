package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.rest.RestRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class BasicAuthProviderFactory implements AuthProviderFactory {

    private final SecretRefs secrets;

    @Inject
    public BasicAuthProviderFactory(SecretRefs secrets) { this.secrets = secrets; }

    @Override public String kind() { return "basic"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String user = spec.config().get("username");
        String envName = spec.config().get("password_env");
        String pwd = secrets.lookup(envName);
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
}
