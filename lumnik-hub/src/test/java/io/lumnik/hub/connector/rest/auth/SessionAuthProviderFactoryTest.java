package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #1751: an old web application behind a form login — Spring Security, or the servlet container's j_security_check. */
class SessionAuthProviderFactoryTest {

    private static final String APP = "http://clinic.test/app";
    private static final String DATA = APP + "/vets.json";

    /**
     * The application: GET {@link #prime} hands out a session and, under Spring Security, a CSRF token in its page;
     * POST {@link #login} with the right fields rotates the session; data answers only the session it issued last,
     * else what {@link #expiredAnswer} says — a redirect to the login page by default.
     */
    static class App implements AuthProvider.Sender {
        final List<RestRequest> sent = new ArrayList<>();
        String prime = APP + "/login";
        String login = APP + "/login";
        String usernameField = "username";
        String passwordField = "password";
        String csrf = "c5rf-t0ken";
        String page = "<form><input type=\"hidden\" name=\"_csrf\" value=\"c5rf-t0ken\"/></form>";
        int sessions;
        String valid;
        RestResponse expiredAnswer = RestResponse.of(302, Map.of("Location", List.of(APP + "/login")), "");

        @Override public RestResponse send(RestRequest r) {
            sent.add(r);
            if (r.method().equals("GET") && r.url().equals(prime)) {
                return RestResponse.of(200, Map.of("Set-Cookie", List.of("JSESSIONID=anon" + sessions + "; Path=/app; HttpOnly")), page);
            }
            if (r.method().equals("POST") && r.url().equals(login)) {
                Map<String, String> form = form(r.body());
                boolean ok = "lumnik".equals(form.get(usernameField)) && "pw ok&=".equals(form.get(passwordField))
                    && (csrf == null || csrf.equals(form.get("_csrf")))
                    && String.valueOf(r.headers().get("Cookie")).contains("JSESSIONID=anon");
                if (!ok) return RestResponse.of(302, Map.of("Location", List.of(APP + "/login?error")), "");
                valid = "s" + ++sessions + "-ab12cd34";
                return RestResponse.of(302, Map.of("Set-Cookie", List.of("JSESSIONID=" + valid + "; Path=/app; HttpOnly"),
                    "Location", List.of(APP + "/")), "");
            }
            return ("JSESSIONID=" + valid).equals(r.headers().get("Cookie"))
                ? RestResponse.of(200, Map.of(), "{\"vetList\":[]}") : expiredAnswer;
        }

        List<RestRequest> data() {
            return sent.stream().filter(r -> r.url().equals(DATA)).toList();
        }

        long logins() {
            return sent.stream().filter(r -> r.method().equals("POST")).count();
        }

        private static Map<String, String> form(String body) {
            Map<String, String> fields = new HashMap<>();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                fields.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
            return fields;
        }

        private static String decode(String s) {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final App app = new App();
    private final SessionCookies sessions = new SessionCookies();
    private final UUID connector = UUID.randomUUID();

    private static Map<String, String> springSecurity() {
        return Map.of("login_url", APP + "/login", "username", "lumnik", "password_env", "APP_PASSWORD", "csrf_field", "_csrf");
    }

    private AuthProvider provider(Map<String, String> cfg) {
        return provider(cfg, "pw ok&=");
    }

    private AuthProvider provider(Map<String, String> cfg, String password) {
        SecretRefs secrets = SecretRefs.environmentOnly(n -> "APP_PASSWORD".equals(n) ? password : null);
        return new SessionAuthProviderFactory(secrets, sessions)
            .build(new AuthSpec("session", cfg, List.of(), new AuthSpec.Owner(7L, connector)));
    }

    @Test
    void spring_security_primes_the_session_reads_the_csrf_token_and_logs_in_once() throws Exception {
        RestResponse resp = provider(springSecurity()).send(RestRequest.get(DATA), app);

        assertThat(resp.status()).isEqualTo(200);
        RestRequest login = app.sent.get(1);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded")
            .containsEntry("Cookie", "JSESSIONID=anon0");
        assertThat(login.body()).isEqualTo("username=lumnik&password=pw+ok%26%3D&_csrf=c5rf-t0ken");
        assertThat(app.data()).extracting(r -> r.headers().get("Cookie")).containsExactly("JSESSIONID=s1-ab12cd34");
    }

    @Test
    void the_containers_form_login_needs_its_field_names_and_a_protected_page_to_prime() throws Exception {
        app.prime = APP + "/vets.html";
        app.login = APP + "/j_security_check";
        app.usernameField = "j_username";
        app.passwordField = "j_password";
        app.csrf = null;
        app.page = "<form action=\"j_security_check\" method=\"post\"></form>";

        provider(Map.of("login_url", APP + "/j_security_check", "prime_url", APP + "/vets.html", "username", "lumnik",
            "password_env", "APP_PASSWORD", "username_field", "j_username", "password_field", "j_password"))
            .send(RestRequest.get(DATA), app);

        assertThat(app.sent.get(1).body()).isEqualTo("j_username=lumnik&j_password=pw+ok%26%3D");
        assertThat(app.data()).extracting(r -> r.headers().get("Cookie")).containsExactly("JSESSIONID=s1-ab12cd34");
    }

    @Test
    void the_session_is_held_for_the_connector_one_login_whatever_the_number_of_chunks() throws Exception {
        for (int chunk = 0; chunk < 3; chunk++) provider(springSecurity()).send(RestRequest.get(DATA), app);
        assertThat(app.logins()).isEqualTo(1);
        assertThat(app.data()).hasSize(3);
    }

    @Test
    void an_expired_session_sent_to_the_login_page_is_opened_again_and_the_same_request_replayed_once() throws Exception {
        AuthProvider auth = provider(springSecurity());
        auth.send(RestRequest.get(DATA), app);
        app.valid = "expired-by-the-server";

        RestResponse resp = auth.send(RestRequest.get(DATA).withQuery("page", "3"), app);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(app.logins()).isEqualTo(2);
        List<RestRequest> data = app.data();
        assertThat(data.get(1).headers().get("Cookie")).isEqualTo("JSESSIONID=s1-ab12cd34");
        assertThat(data.get(2).headers().get("Cookie")).isEqualTo("JSESSIONID=s2-ab12cd34");
        assertThat(data.get(2).queryParams()).isEqualTo(data.get(1).queryParams());
    }

    @Test
    void a_401_drops_the_session_too() throws Exception {
        app.expiredAnswer = RestResponse.of(401, Map.of(), "");
        AuthProvider auth = provider(springSecurity());
        auth.send(RestRequest.get(DATA), app);
        app.valid = "expired-by-the-server";

        assertThat(auth.send(RestRequest.get(DATA), app).status()).isEqualTo(200);
        assertThat(app.logins()).isEqualTo(2);
    }

    @Test
    void a_session_still_refused_after_a_fresh_login_fails_by_name_never_read_as_data() {
        AuthProvider auth = provider(springSecurity());
        app.valid = null;
        App neverValid = new App() {
            @Override public RestResponse send(RestRequest r) {
                RestResponse resp = super.send(r);
                if (r.url().equals(DATA)) return expiredAnswer;
                return resp;
            }
        };

        assertThatThrownBy(() -> auth.send(RestRequest.get(DATA), neverValid))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session refused: the source still answers HTTP 302 after a fresh login");
        assertThat(neverValid.logins()).isEqualTo(2);
    }

    @Test
    void a_login_that_sets_no_session_cookie_is_refused_and_no_data_is_asked() {
        assertThatThrownBy(() -> provider(springSecurity(), "wrong").send(RestRequest.get(DATA), app))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session login set no new cookie (HTTP 302): the credentials or the form's field names are refused");
        assertThat(app.data()).isEmpty();
        assertThat(app.logins()).isEqualTo(1);
    }

    @Test
    void a_page_without_the_declared_csrf_field_is_refused_by_name() {
        app.page = "<form></form>";
        assertThatThrownBy(() -> provider(springSecurity()).send(RestRequest.get(DATA), app))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session prime_url page has no input named '_csrf'");
        assertThat(app.logins()).isZero();
    }

    @Test
    void the_csrf_input_is_read_whatever_the_order_of_its_attributes() throws Exception {
        app.page = "<input value='c5rf-t0ken' type='hidden' name='_csrf'>";
        provider(springSecurity()).send(RestRequest.get(DATA), app);
        assertThat(app.sent.get(1).body()).endsWith("&_csrf=c5rf-t0ken");
    }

    @Test
    void a_login_answering_an_error_status_is_refused_with_the_status_only() {
        App down = new App() {
            @Override public RestResponse send(RestRequest r) {
                return r.method().equals("POST") ? RestResponse.of(500, Map.of(), "stack trace with pw ok&=") : super.send(r);
            }
        };
        assertThatThrownBy(() -> provider(springSecurity()).send(RestRequest.get(DATA), down))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session login answered HTTP 500");
    }

    @Test
    void an_unavailable_password_is_refused_before_any_request() {
        AuthProvider auth = provider(springSecurity(), null);
        assertThatThrownBy(auth::probe)
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session password unavailable (env=APP_PASSWORD)");
        assertThat(app.sent).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "http:///login", "http:/login"})
    void a_login_url_with_no_http_origin_is_refused_and_nothing_is_sent(String loginUrl) {
        Map<String, String> cfg = new HashMap<>(springSecurity());
        cfg.put("login_url", loginUrl);
        cfg.put("prime_url", loginUrl);
        AuthProvider auth = provider(cfg);
        assertThatThrownBy(auth::probe)
            .isInstanceOf(AuthConfigException.class)
            .hasMessageStartingWith("session login_url ");
        assertThatThrownBy(() -> auth.send(RestRequest.get("not a url"), app)).isInstanceOf(AuthConfigException.class);
        assertThat(app.sent).isEmpty();
    }

    @Test
    void a_reapplied_login_drops_the_session_opened_for_the_old_one() throws Exception {
        provider(springSecurity()).send(RestRequest.get(DATA), app);
        Map<String, String> other = new HashMap<>(springSecurity());
        other.put("username_field", "username");   // the same form, written out: still the same login
        provider(other).send(RestRequest.get(DATA), app);
        assertThat(app.logins()).isEqualTo(1);

        app.usernameField = "user";
        other.put("username_field", "user");
        provider(other).send(RestRequest.get(DATA), app);
        assertThat(app.logins()).isEqualTo(2);
    }

    @Test
    void a_session_the_server_replaced_with_its_login_page_is_opened_again() throws Exception {
        // Tomcat's FORM login does not redirect: an unknown session gets 200, the login page, and a new cookie.
        app.expiredAnswer = RestResponse.of(200, Map.of("Set-Cookie", List.of("JSESSIONID=anon9; Path=/app; HttpOnly"),
            "Content-Type", List.of("text/html")), "<form action=\"j_security_check\"></form>");
        AuthProvider auth = provider(springSecurity());
        auth.send(RestRequest.get(DATA), app);
        app.valid = "expired-by-the-server";

        RestResponse resp = auth.send(RestRequest.get(DATA), app);

        assertThat(resp.body()).isEqualTo("{\"vetList\":[]}");
        assertThat(app.logins()).isEqualTo(2);
    }

    @Test
    void a_cookie_the_server_sets_again_with_the_same_value_keeps_the_session() throws Exception {
        App rolling = new App() {
            @Override public RestResponse send(RestRequest r) {
                RestResponse resp = super.send(r);
                if (!r.url().equals(DATA) || resp.status() != 200) return resp;
                return RestResponse.of(200, Map.of("Set-Cookie", List.of("JSESSIONID=" + valid + "; Max-Age=1800")), resp.body());
            }
        };
        AuthProvider auth = provider(springSecurity());
        auth.send(RestRequest.get(DATA), rolling);
        auth.send(RestRequest.get(DATA), rolling);
        assertThat(rolling.logins()).isEqualTo(1);
    }

    @Test
    void the_session_cookie_never_leaves_the_login_origin() {
        AuthProvider auth = provider(springSecurity());
        for (String elsewhere : List.of("http://other.test/app/vets.json", "https://clinic.test/app/vets.json",
                "http://clinic.test:8443/app/vets.json", "http://clinic.test/a b", "/app/vets.json")) {
            assertThatThrownBy(() -> auth.send(RestRequest.get(elsewhere), app))
                .isInstanceOf(AuthConfigException.class)
                .hasMessage("session cookie stays with the login's origin (http://clinic.test:80): a request to another origin is refused");
        }
        assertThat(app.sent).isEmpty();
    }

    @Test
    void a_prime_url_on_another_origin_than_the_login_is_refused() {
        Map<String, String> cfg = new HashMap<>(springSecurity());
        cfg.put("prime_url", "http://other.test/login");
        assertThatThrownBy(provider(cfg)::probe)
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("session prime_url must be on the login_url's origin (http://clinic.test:80)");
    }

    @Test
    void a_blank_optional_key_is_absent_its_default_applies() throws Exception {
        // Review of #1752: the validator reads a blank value as not given; the runtime must too.
        Map<String, String> cfg = new HashMap<>(springSecurity());
        cfg.put("prime_url", " ");
        cfg.put("username_field", "");
        cfg.put("password_field", " ");
        provider(cfg).send(RestRequest.get(DATA), app);
        assertThat(app.sent.get(0).url()).isEqualTo(APP + "/login");
        assertThat(app.sent.get(1).body()).isEqualTo("username=lumnik&password=pw+ok%26%3D&_csrf=c5rf-t0ken");
    }

    @Test
    void a_config_missing_every_key_is_refused_by_name_not_by_a_crash() {
        // Review of #1752: the fingerprint is built with the provider, before anything is checked.
        assertThatThrownBy(provider(Map.of(), null)::probe)
            .isInstanceOf(AuthConfigException.class)
            .hasMessageStartingWith("session login_url ");
    }

    @Test
    void a_login_that_only_sets_the_primed_cookie_again_is_refused() {
        // A refused login may echo the session it was given; only a new value is a login that worked.
        App echoing = new App() {
            @Override public RestResponse send(RestRequest r) {
                RestResponse resp = super.send(r);
                if (!r.method().equals("POST") || resp.headers().containsKey("Set-Cookie")) return resp;
                return RestResponse.of(302, Map.of("Set-Cookie", List.of("JSESSIONID=anon0; Path=/app")), "");
            }
        };
        assertThatThrownBy(() -> provider(springSecurity(), "wrong").send(RestRequest.get(DATA), echoing))
            .isInstanceOf(AuthConfigException.class)
            .hasMessageStartingWith("session login set no new cookie (HTTP 302)");
        assertThat(echoing.data()).isEmpty();
    }

    @Test
    void the_session_cookie_is_named_a_credential_in_what_the_hub_logs() {
        assertThat(SecretRedactor.redactHeader("Cookie", "JSESSIONID=s1-ab12cd34")).isEqualTo("<redacted>");
    }

    @Test
    void a_refusal_echoing_the_session_id_loses_it() {
        RestRequest signed = RestRequest.get(DATA).withHeader("Cookie", "JSESSIONID=s1-ab12cd34; theme=dark");
        RestResponse echoed = RestResponse.of(401, Map.of(), "{\"error\":\"unknown session s1-ab12cd34\"}");
        assertThat(SecretRedactor.withoutEchoedCredentials(echoed, signed, false).body())
            .isEqualTo("{\"error\":\"unknown session <redacted>\"}");
    }
}
