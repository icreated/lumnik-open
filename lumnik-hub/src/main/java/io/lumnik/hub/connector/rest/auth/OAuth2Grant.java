package io.lumnik.hub.connector.rest.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Urls;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One client-credentials grant (RFC 6749 §4.4): where a token is asked for, as whom, for what (#1393). The secret
 * goes into the token request and nowhere else — not the fingerprint, not {@link #toString}, not a refusal.
 */
final class OAuth2Grant {

    /** A token whose lifetime the server did not state, or stated in a form this does not read: a 401 renews it anyway. */
    private static final long UNSTATED_SECONDS = 300;
    private static final long MAX_MARGIN_SECONDS = 60;
    /** An RFC 6749 §5.2 error code, read only in this shape: the body around it is the server's and is never shown. */
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SECONDS = Pattern.compile("\\d{1,9}");

    private final String tokenUrl;
    private final String clientId;
    private final String scope;
    private final String clientAuth;
    private final String secretRef;
    private final String secret;

    OAuth2Grant(Map<String, String> cfg, String secret) {
        this.tokenUrl = cfg.get("token_url");
        this.clientId = cfg.get("client_id");
        this.scope = cfg.get("scope");
        this.clientAuth = cfg.getOrDefault("client_auth", "basic");
        this.secretRef = cfg.get("client_secret_env");
        this.secret = secret;
    }

    /** What is wrong with this grant before anything is sent, or empty. Names keys, never values. */
    Optional<String> whyNotUsable() {
        Optional<String> url = Urls.whyNotHttp(tokenUrl);
        if (url.isPresent()) return Optional.of("OAuth2 token_url " + url.get());
        if (clientId == null || clientId.isBlank()) return Optional.of("OAuth2 client_id is required");
        if (!clientAuth.equals("basic") && !clientAuth.equals("post")) {
            return Optional.of("OAuth2 client_auth must be basic or post");
        }
        if (secretRef == null || secretRef.isBlank()) return Optional.of("OAuth2 client_secret_env is required");
        if (secret == null || secret.isBlank()) return Optional.of("OAuth2 client secret unavailable (env=" + secretRef + ")");
        return Optional.empty();
    }

    /** The grant without its secret: a re-apply that changes any of it misses the token held for the old one. */
    String fingerprint() {
        return String.join("\u0000", tokenUrl, clientId, String.valueOf(scope), clientAuth, String.valueOf(secretRef));
    }

    /** Asks for a token. Not retried: a refused grant asked again is refused again. */
    OAuth2Token request(RestHttpClient http, Instant now) throws Exception {
        RestResponse resp = http.execute(tokenRequest());
        if (resp.status() / 100 != 2) throw new AuthConfigException(refusal(resp));
        return token(resp.json(), now);
    }

    RestRequest tokenRequest() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scope != null && !scope.isBlank()) form.put("scope", scope);
        RestRequest req = RestRequest.post(tokenUrl, null)
            .withHeader("Content-Type", "application/x-www-form-urlencoded")
            .withHeader("Accept", "application/json");
        if (clientAuth.equals("post")) {
            form.put("client_id", clientId);
            form.put("client_secret", secret);
        } else {
            // RFC 6749 §2.3.1: each half form-encoded, then Basic.
            String pair = encode(clientId) + ":" + encode(secret);
            req = req.withHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        return req.withBody(RestHttpClient.formEncode(form));
    }

    static OAuth2Token token(JsonNode body, Instant now) {
        JsonNode value = body.path("access_token");
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new AuthConfigException("OAuth2 token response carries no access_token");
        }
        if (value.asText().length() < SecretRedactor.MIN_LENGTH) {
            throw new AuthConfigException("OAuth2 access_token is shorter than " + SecretRedactor.MIN_LENGTH
                + " characters: too short to keep out of the source's responses");
        }
        JsonNode type = body.path("token_type");
        if (!type.isMissingNode() && !type.isNull() && !type.asText().equalsIgnoreCase("bearer")) {
            throw new AuthConfigException("OAuth2 token_type is not Bearer");
        }
        long lifetime = lifetime(body.path("expires_in"));
        return new OAuth2Token(value.asText(), now.plusSeconds(lifetime - Math.min(lifetime / 10, MAX_MARGIN_SECONDS)));
    }

    /** {@code expires_in} as a positive whole number of seconds, a JSON number or a digit string; else the default. */
    private static long lifetime(JsonNode expiresIn) {
        long seconds = 0;
        if (expiresIn.isIntegralNumber() && expiresIn.canConvertToLong()) seconds = expiresIn.asLong();
        else if (expiresIn.isTextual() && SECONDS.matcher(expiresIn.asText()).matches()) seconds = Long.parseLong(expiresIn.asText());
        return seconds > 0 ? seconds : UNSTATED_SECONDS;
    }

    static String refusal(RestResponse resp) {
        JsonNode error = resp.json().path("error");
        String code = error.isTextual() && ERROR_CODE.matcher(error.asText()).matches() ? " " + error.asText() : "";
        return "OAuth2 token refused by the authorization server: HTTP " + resp.status() + code;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override public String toString() {
        return "OAuth2Grant[" + Urls.forDisplay(tokenUrl) + ", client_id " + clientId + "]";
    }
}
