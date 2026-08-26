package io.lumnik.hub.connector.rest.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthProviderRegistry {
    @Inject Instance<AuthProviderFactory> factories;

    public AuthProvider build(AuthSpec spec) {
        for (AuthProviderFactory f : factories) {
            if (f.kind().equals(spec.kind())) return f.build(spec);
        }
        throw new IllegalArgumentException("Unknown auth kind: " + spec.kind());
    }
}
