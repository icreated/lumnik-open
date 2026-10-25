package io.lumnik.hub.validator.rest;

import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

public final class AuthBlockValidator {
    private AuthBlockValidator() {}

    private static final String BASIC = "basic";
    private static final String API_KEY = "api-key";
    private static final String OAUTH2 = "oauth2";

    public static ConfigDef configDef() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH,
                "Authentication method", "bearer", BASIC, API_KEY, OAUTH2, "none")
            .requireWhen("username", Type.STRING, Importance.HIGH,
                "Username for basic auth", "kind", BASIC)
            .requireWhen("password_env", Type.STRING, Importance.HIGH,
                "Basic password source: lm secret name, else env var", "kind", BASIC)
            .requireWhen("name", Type.STRING, Importance.HIGH,
                "Header/query parameter name for API key", "kind", API_KEY)
            .requireWhen("value_env", Type.STRING, Importance.HIGH,
                "API key value source: lm secret name, else env var", "kind", API_KEY)
            .optional("in", Type.STRING, Importance.MEDIUM,
                "Where to place the API key (header|query)", "header")
            .optional("token_env", Type.STRING, Importance.HIGH,
                "Bearer token source: lm secret name, else env var (bearer)", null)
            .optional("token_value", Type.STRING, Importance.LOW,
                "Literal bearer token (dev only — prefer token_env)", null)
            .requireWhen("token_url", Type.STRING, Importance.HIGH,
                "OAuth2 token endpoint (client-credentials grant)", "kind", OAUTH2)
            .requireWhen("client_id", Type.STRING, Importance.HIGH,
                "OAuth2 client id", "kind", OAUTH2)
            .requireWhen("client_secret_env", Type.STRING, Importance.HIGH,
                "OAuth2 client secret source: lm secret name, else env var", "kind", OAUTH2)
            .optional("scope", Type.STRING, Importance.MEDIUM,
                "OAuth2 scope, space-separated", null)
            .optional("client_auth", Type.STRING, Importance.LOW,
                "How the OAuth2 client authenticates to the token endpoint (basic | post)", "basic")
            .closed();
    }
}
