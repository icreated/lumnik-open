package io.lumnik.hub.connector.rest.auth;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code kind: session} — an older web application's form login (#1751): Spring Security's {@code POST /login} with
 * its CSRF token, or the servlet container's {@code j_security_check}. One mechanism: GET {@code prime_url} for the
 * first session cookie (and the CSRF token in its page), POST the form to {@code login_url}, keep the session cookie
 * the login sets. The session is held per connector by {@link SessionCookies}; a 401 or a redirect — an expired
 * session sent to the login page — drops it, and the original request is replayed once after a fresh login.
 *
 * <p>The login goes through the same sender as the data: the connector's CA, rate limit and retries. The password
 * goes into the login form and nowhere else — not the fingerprint, not a refusal; a refusal names a status, never
 * the login page's body.
 */
@ApplicationScoped
public class SessionAuthProviderFactory implements AuthProviderFactory {

    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = attributePattern("name");
    private static final Pattern VALUE = attributePattern("value");

    private static Pattern attributePattern(String name) {
        return Pattern.compile("\\s" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    }

    private final SecretRefs secrets;
    private final SessionCookies sessions;

    @Inject
    public SessionAuthProviderFactory(SecretRefs secrets, SessionCookies sessions) {
        this.secrets = secrets;
        this.sessions = sessions;
    }

    @Override public String kind() { return "session"; }

    @Override public AuthProvider build(AuthSpec spec) {
        if (spec.owner() == null) throw new IllegalStateException("a session credential is built for a connector");
        Form form = new Form(spec.config(), secrets.lookup(spec.config().get("password_env")));
        return new Provider(form, form.whyNotUsable(), Urls.origin(form.loginUrl()), form.fingerprint(),
            spec.owner(), sessions);
    }

    /** The login form, as the manifest declares it; the defaults are Spring Security's field names. */
    private record Form(String loginUrl, String primeUrl, String username, String passwordEnv, String password,
                        String usernameField, String passwordField, String csrfField) {

        Form(Map<String, String> c, String password) {
            this(c.get("login_url"), given(c, "prime_url", c.get("login_url")), c.get("username"),
                c.get("password_env"), password, given(c, "username_field", "username"),
                given(c, "password_field", "password"), given(c, "csrf_field", null));
        }

        /** The value of {@code key}, or {@code otherwise}: a blank value is not given, as the validator reads it. */
        private static String given(Map<String, String> c, String key, String otherwise) {
            String v = c.get(key);
            return Text.present(v) ? v : otherwise;
        }

        Optional<String> whyNotUsable() {
            Optional<String> login = Urls.whyNotHttp(loginUrl);
            if (login.isPresent()) return Optional.of("session login_url " + login.get());
            Optional<String> prime = Urls.whyNotHttp(primeUrl);
            if (prime.isPresent()) return Optional.of("session prime_url " + prime.get());
            Optional<String> origin = Urls.origin(loginUrl);
            if (origin.isEmpty()) return Optional.of("session login_url has no host");
            if (!Urls.origin(primeUrl).equals(origin)) {
                return Optional.of("session prime_url must be on the login_url's origin (" + origin.get() + ")");
            }
            if (Text.blank(username)) return Optional.of("session username is required");
            if (Text.blank(password)) return Optional.of("session password unavailable (env=" + passwordEnv + ")");
            return Optional.empty();
        }

        String fingerprint() {
            return String.join("\u0000", loginUrl, primeUrl, username, String.valueOf(passwordEnv), usernameField,
                passwordField, String.valueOf(csrfField));
        }

        /** Primes, logs in, and returns the session the login set. */
        SessionCookies.Session open(AuthProvider.Sender sender) throws Exception {
            RestResponse primed = sender.send(RestRequest.get(primeUrl));
            if (primed.status() >= 400) throw new AuthConfigException("session prime_url answered HTTP " + primed.status());
            Map<String, String> jar = new LinkedHashMap<>(cookies(primed));
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(usernameField, username);
            fields.put(passwordField, password);
            if (Text.present(csrfField)) fields.put(csrfField, csrf(primed.body()));
            RestRequest login = RestRequest.post(loginUrl, RestHttpClient.formEncode(fields))
                .withHeader("Content-Type", "application/x-www-form-urlencoded");
            if (!jar.isEmpty()) login = login.withHeader("Cookie", SessionCookies.header(jar));
            RestResponse answer = sender.send(login);
            if (answer.status() >= 400) throw new AuthConfigException("session login answered HTTP " + answer.status());
            Map<String, String> set = cookies(answer);
            // A login that worked rotates the session (Spring Security, Tomcat both do): a cookie the primed session did
            // not hold, or a new value for one it did. The same value set again is the refused session echoed back.
            if (set.entrySet().stream().allMatch(c -> c.getValue().equals(jar.get(c.getKey())))) {
                throw new AuthConfigException("session login set no new cookie (HTTP " + answer.status()
                    + "): the credentials or the form's field names are refused");
            }
            jar.putAll(set);
            return new SessionCookies.Session(jar);
        }

        private String csrf(String page) {
            Matcher input = INPUT.matcher(page == null ? "" : page);
            while (input.find()) {
                String tag = input.group();
                if (csrfField.equals(attribute(tag, NAME))) {
                    String value = attribute(tag, VALUE);
                    if (value != null) return value;
                }
            }
            throw new AuthConfigException("session prime_url page has no input named '" + csrfField + "'");
        }

        private static String attribute(String tag, Pattern attribute) {
            Matcher m = attribute.matcher(tag);
            if (!m.find()) return null;
            return m.group(2) != null ? m.group(2) : m.group(3);
        }

        static Map<String, String> cookies(RestResponse resp) {
            Map<String, String> found = new LinkedHashMap<>();
            resp.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("Set-Cookie")) return;
                for (String value : values) {
                    try {
                        for (HttpCookie c : HttpCookie.parse(value)) found.put(c.getName(), c.getValue());
                    } catch (IllegalArgumentException unreadable) {
                        // a Set-Cookie this does not read is not a session
                    }
                }
            });
            return found;
        }

        @Override public String toString() {
            return "Form[login_url=" + loginUrl + ", username=" + username + "]";
        }
    }

    /** One chunk's view of a connector's login; the session itself lives in {@link SessionCookies}. */
    private record Provider(Form form, Optional<String> unusable, Optional<String> origin, String fingerprint,
                            AuthSpec.Owner owner,
                            SessionCookies sessions)
            implements AuthProvider {

        @Override public void probe() {
            unusable.ifPresent(why -> { throw new AuthConfigException(why); });
        }

        /** Not the path a request takes: {@link #send} opens the session this signs with. */
        @Override public RestRequest apply(RestRequest r) {
            throw new IllegalStateException("a session request goes through send");
        }

        @Override public RestResponse send(RestRequest unsigned, Sender sender) throws Exception {
            probe();
            // probe() passed, so the login has an origin; a request with none never matches it.
            if (!Urls.origin(unsigned.url()).equals(origin)) {
                throw new AuthConfigException("session cookie stays with the login's origin (" + origin.orElseThrow()
                    + "): a request to another origin is refused");
            }
            SessionCookies.Session used = current(sender);
            RestResponse resp = sender.send(unsigned.withHeader("Cookie", used.cookie()));
            if (!refused(resp, used)) return resp;
            sessions.invalidate(owner, used);
            used = current(sender);
            resp = sender.send(unsigned.withHeader("Cookie", used.cookie()));
            if (!refused(resp, used)) return resp;
            throw new AuthConfigException("session refused: the source still answers HTTP " + resp.status()
                + " after a fresh login");
        }

        /**
         * What an expired session gets — the login page, never data: a 401, a redirect (Spring Security), or a new
         * value for one of the session's cookies (the container's FORM login answers 200 with its login page and a
         * new session). The same value set again is a session kept alive, not a new one.
         */
        private static boolean refused(RestResponse resp, SessionCookies.Session used) {
            if (resp.status() == 401 || resp.status() / 100 == 3) return true;
            return Form.cookies(resp).entrySet().stream()
                .anyMatch(c -> used.jar().containsKey(c.getKey()) && !used.jar().get(c.getKey()).equals(c.getValue()));
        }

        private SessionCookies.Session current(Sender sender) throws Exception {
            try {
                return sessions.current(owner, fingerprint, () -> form.open(sender));
            } catch (RuntimeException refused) {
                throw refused;   // already named (AuthConfigException); only a checked failure is described below
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new ConnectorException("session login interrupted", cancelled);
            } catch (Exception call) {
                // The kind, never the message: a JDK message can quote the login URL (#1166).
                throw new AuthConfigException("session login failed: " + OutboundFailure.describe(call));
            }
        }

        @Override public String toString() {
            return "SessionProvider[" + form + "]";
        }
    }
}
