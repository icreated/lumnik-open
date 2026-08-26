package io.lumnik.hub.connector.rest.auth;

public interface AuthProviderFactory {
    String kind();
    AuthProvider build(AuthSpec spec);
}
