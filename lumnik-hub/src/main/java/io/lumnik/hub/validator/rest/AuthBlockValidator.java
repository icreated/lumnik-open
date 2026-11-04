package io.lumnik.hub.validator.rest;

import io.lumnik.hub.connector.rest.auth.SecretRedactor;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.Optional;

public final class AuthBlockValidator {
    private AuthBlockValidator() {}

    private static final String BASIC = "basic";
    private static final String API_KEY = "api-key";
    private static final String OAUTH2 = "oauth2";
    private static final String SESSION = "session";

    public static ConfigDef configDef() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH,
                "Authentication method", "bearer", BASIC, API_KEY, OAUTH2, SESSION, "none")
            .optional("username", Type.STRING, Importance.HIGH,
                "Username (basic, session)", null)
                .requiredWhen("kind", BASIC, SESSION)
            .optional("password_env", Type.STRING, Importance.HIGH,
                "Password source: lm secret name, else env var (basic, session)", null)
                .requiredWhen("kind", BASIC, SESSION)
            .requireWhen("name", Type.STRING, Importance.HIGH,
                "Header/query parameter name for API key", "kind", API_KEY)
            .requireWhen("value_env", Type.STRING, Importance.HIGH,
                "API key value source: lm secret name, else env var", "kind", API_KEY)
            .optional("in", Type.STRING, Importance.MEDIUM,
                "Where to place the API key (header|query)", "header")
            .optional("token_env", Type.STRING, Importance.HIGH,
                "Bearer token source: lm secret name, else env var (bearer)", null)
            // The secret itself, in a file the integrator is told to commit and review. Not the same hazard as jdbc's
            // `password` — BearerAuthProviderFactory reads token_env FIRST and only falls back to the literal when no
            // reference is named, so nothing is silently overridden — but it is the same secret in the same repository,
            // and the docs' CI grep is the only thing that stood between it and a git history (#530). Under bearer
            // only: elsewhere nothing reads the field, and this sentence would name a bearer token that is not one.
            .optional("token_value", Type.STRING, Importance.LOW,
                "Literal bearer token (dev only — prefer token_env)", null)
                .warnWhenGiven("kind", "bearer",
                    "literal bearer token in the manifest — this is the secret itself, not a name, "
                        + "and a manifest is meant to be committed",
                    "Remove it and name the secret: token_env: <NAME> "
                        + "(`lm secret set <NAME> …`, else an env var set on the hub)",
                    "Keep it only in a manifest that is never committed — dev/test")
            // scheme is bearer's alone (#1640): one word before the token, as the header's grammar has it.
            .optional("scheme", Type.STRING, Importance.LOW,
                "The Authorization header's scheme word (bearer): Bearer by default, Token for Django REST Framework", "Bearer")
                .ignoredUnless("kind", "bearer")
                .check(v -> v.isTextual() && !(SecretRedactor.isSchemeWord(v.asText()) && v.asText().length() <= 32)
                    ? Optional.of("scheme must be one word starting with a letter — letters, digits, '-' or '_' — at most 32")
                    : Optional.empty())
                .hint("Bearer (the default), or the word the API expects, e.g. Token for Django REST Framework")
            // Bearer accepts either an env-var name or an inline value; blank, null or missing counts as neither.
            .atLeastOneOf("kind is bearer", block -> "bearer".equals(block.path("kind").asText()),
                "Use `lm secret set <NAME> ...` then reference via token_env: <NAME>", "token_env", "token_value")
            // The oauth2 keys are declared for every kind (the block is one closed key set); under another kind they
            // are named as ignored instead of validating in silence (Copilot on #1394).
            .requireWhen("token_url", Type.STRING, Importance.HIGH,
                "OAuth2 token endpoint (client-credentials grant)", "kind", OAUTH2)
                .ignoredUnless("kind", OAUTH2)
            .requireWhen("client_id", Type.STRING, Importance.HIGH,
                "OAuth2 client id", "kind", OAUTH2)
                .ignoredUnless("kind", OAUTH2)
            .requireWhen("client_secret_env", Type.STRING, Importance.HIGH,
                "OAuth2 client secret source: lm secret name, else env var", "kind", OAUTH2)
                .ignoredUnless("kind", OAUTH2)
            .optional("scope", Type.STRING, Importance.MEDIUM,
                "OAuth2 scope, space-separated", null)
                .ignoredUnless("kind", OAUTH2)
            .optional("client_auth", Type.STRING, Importance.LOW,
                "How the OAuth2 client authenticates to the token endpoint (basic | post)", "basic")
                .ignoredUnless("kind", OAUTH2)
                .oneOf("basic", "post")
                .hint("basic (the default) sends the client id and secret as HTTP Basic; post puts them in the body")
            // session (#1751): an older web application's form login, the session cookie kept.
            .requireWhen("login_url", Type.STRING, Importance.HIGH,
                "Where the login form posts: Spring Security's /login, the container's j_security_check", "kind", SESSION)
                .ignoredUnless("kind", SESSION)
            .optional("prime_url", Type.STRING, Importance.MEDIUM,
                "The page GET first, for the session cookie and the CSRF token; login_url by default", null)
                .ignoredUnless("kind", SESSION)
            .optional("username_field", Type.STRING, Importance.LOW,
                "The form's username field (j_username for the container's login)", "username")
                .ignoredUnless("kind", SESSION)
            .optional("password_field", Type.STRING, Importance.LOW,
                "The form's password field (j_password for the container's login)", "password")
                .ignoredUnless("kind", SESSION)
            .optional("csrf_field", Type.STRING, Importance.MEDIUM,
                "The hidden input carrying the CSRF token in the primed page (_csrf under Spring Security)", null)
                .ignoredUnless("kind", SESSION)
            .closed();
    }
}
