package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * {@code kind: oauth2} — the client-credentials grant (#1393). A token is asked for once and held by
 * {@link OAuth2Tokens} until shortly before it expires; a 401 from the source drops it, and the original request
 * is signed again with a fresh one, once.
 */
@ApplicationScoped
public class OAuth2AuthProviderFactory implements AuthProviderFactory {

    private final SecretRefs secrets;
    private final OAuth2Tokens tokens;
    private final RestHttpClient http;

    @Inject
    public OAuth2AuthProviderFactory(SecretRefs secrets, OAuth2Tokens tokens, RestHttpClient http) {
        this.secrets = secrets;
        this.tokens = tokens;
        this.http = http;
    }

    @Override public String kind() { return "oauth2"; }

    @Override public AuthProvider build(AuthSpec spec) {
        if (spec.owner() == null) throw new IllegalStateException("an oauth2 credential is built for a connector");
        OAuth2Grant grant = new OAuth2Grant(spec.config(), secrets.lookup(spec.config().get("client_secret_env")));
        return new Provider(grant, grant.whyNotUsable(), spec.owner(), tokens, http);
    }

    /** One chunk's view of a connector's grant; the token itself lives in {@link OAuth2Tokens}. */
    private record Provider(OAuth2Grant grant, Optional<String> unusable, AuthSpec.Owner owner,
                            OAuth2Tokens tokens, RestHttpClient http) implements AuthProvider {

        @Override public void probe() {
            current();
        }

        @Override public RestRequest apply(RestRequest r) {
            return sign(r, current());
        }

        @Override public RestResponse send(RestRequest unsigned, Sender sender) throws Exception {
            OAuth2Token used = current();
            RestResponse resp = sender.send(sign(unsigned, used));
            if (resp.status() != 401) return resp;
            tokens.invalidate(owner, used);
            return sender.send(sign(unsigned, current()));
        }

        private OAuth2Token current() {
            unusable.ifPresent(why -> { throw new AuthConfigException(why); });
            try {
                return tokens.current(owner, grant.fingerprint(), now -> grant.request(http, now));
            } catch (RuntimeException refused) {
                throw refused;
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new ConnectorException("OAuth2 token request interrupted", cancelled);
            } catch (Exception call) {
                // The kind, never the message: a JDK message can quote the token URL (#1166).
                throw new AuthConfigException("OAuth2 token request failed: " + OutboundFailure.describe(call));
            }
        }

        private static RestRequest sign(RestRequest r, OAuth2Token token) {
            return r.withHeader("Authorization", "Bearer " + token.value());
        }

        @Override public String toString() {
            return "OAuth2Provider[" + grant + "]";
        }
    }
}
