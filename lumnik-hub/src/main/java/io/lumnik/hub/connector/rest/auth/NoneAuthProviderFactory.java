package io.lumnik.hub.connector.rest.auth;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * No-op auth provider for unauthenticated endpoints. Selected when a connector config omits the
 * {@code auth} block (the readers default the kind to {@code "none"}). Leaves the request unchanged.
 */
@ApplicationScoped
public class NoneAuthProviderFactory implements AuthProviderFactory {

    @Override public String kind() { return "none"; }

    @Override public AuthProvider build(AuthSpec spec) {
        return request -> request;
    }
}
