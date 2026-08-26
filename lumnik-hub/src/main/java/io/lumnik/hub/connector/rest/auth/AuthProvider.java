package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.rest.RestRequest;

public interface AuthProvider {
    RestRequest apply(RestRequest request);
    default void probe() {}
}
