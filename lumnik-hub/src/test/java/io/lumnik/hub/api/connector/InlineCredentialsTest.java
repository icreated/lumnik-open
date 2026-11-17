package io.lumnik.hub.api.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2003: a connector's inline credentials never leave the hub through a read door, and a config read back with them
 * redacted can be applied again without losing them — only where it was, and only toward where it was going.
 */
class InlineCredentialsTest {

    private static final String R = InlineCredentials.MARKER;
    private static final String U = InlineCredentials.URL_MARKER;

    private static Map<String, Object> rest() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("base_url", "https://api-user:s3cret@api.example/v1");
        config.put("auth", new LinkedHashMap<>(Map.of("kind", "bearer", "token_value", "tok-123")));
        config.put("headers", List.of(
            new LinkedHashMap<>(Map.of("name", "Accept", "value", "application/json")),
            new LinkedHashMap<>(Map.of("name", "X-Api-Key", "value", "k-456")),
            new LinkedHashMap<>(Map.of("name", "X-Trace", "value_env", "TRACE"))));
        return config;
    }

    @Test
    void every_inline_credential_is_redacted_and_nothing_else() {
        Map<String, Object> redacted = InlineCredentials.redacted(rest());

        assertThat(redacted.get("base_url")).isEqualTo("https://api-user:" + U + "@api.example/v1");
        assertThat(redacted.get("auth")).isEqualTo(Map.of("kind", "bearer", "token_value", R));
        assertThat(redacted.get("headers")).isEqualTo(List.of(
            Map.of("name", "Accept", "value", "application/json"),
            Map.of("name", "X-Api-Key", "value", R),
            Map.of("name", "X-Trace", "value_env", "TRACE")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "jdbc:postgresql://db:5432/erp?user=erp&password=pw1&ssl=true | jdbc:postgresql://db:5432/erp?user=erp&password=%3Credacted%3E&ssl=true",
        "jdbc:postgresql://db/erp?sslpassword=k1 | jdbc:postgresql://db/erp?sslpassword=%3Credacted%3E",
        "jdbc:sqlserver://db;user=erp;password=pw1;encrypt=true | jdbc:sqlserver://db;user=erp;password=%3Credacted%3E;encrypt=true",
        "jdbc:sqlserver://db;user=erp;password={a;b};encrypt=true | jdbc:sqlserver://db;user=erp;password=%3Credacted%3E;encrypt=true",
        "jdbc:mysql://address=(host=db)(user=erp)(password=pw1)/erp | jdbc:mysql://address=(host=db)(user=erp)(password=%3Credacted%3E)/erp",
        "jdbc:oracle:thin:erp/pw1@db:1521/ORCL | jdbc:oracle:thin:erp/%3Credacted%3E@db:1521/ORCL",
        "jdbc:oracle:thin:erp/p@w@db:1521/ORCL | jdbc:oracle:thin:erp/%3Credacted%3E@db:1521/ORCL",
        "jdbc:oracle:thin:erp/pw1@//db:1521/svc | jdbc:oracle:thin:erp/%3Credacted%3E@//db:1521/svc",
        "jdbc:oracle:thin:@//db:1521/svc | jdbc:oracle:thin:@//db:1521/svc",
        "jdbc:mysql://db/erp?user=erp&pwd=pw1&passwd=pw2 | jdbc:mysql://db/erp?user=erp&pwd=%3Credacted%3E&passwd=%3Credacted%3E",
        "jdbc:postgresql://erp:pw1@db:5432/erp | jdbc:postgresql://erp:%3Credacted%3E@db:5432/erp",
        "jdbc:postgresql://db:5432/erp | jdbc:postgresql://db:5432/erp",
    })
    void a_jdbc_url_loses_its_password_only(String url, String expected) {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("url", url, "password", "pw2", "password_env", "ERP_PW"));

        Map<String, Object> redacted = InlineCredentials.redacted(config);

        assertThat(redacted).containsEntry("url", expected).containsEntry("password", R).containsEntry("password_env", "ERP_PW");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "https://api-user:p@ss/w@rd@api.example/v1 | https://api-user:%3Credacted%3E@api.example/v1",
        "https://api.example/v1?api_key=K&page=1 | https://api.example/v1?api_key=%3Credacted%3E&page=1",
        "http://api.example:8080?contact=ops@x.example | http://api.example:8080?contact=ops@x.example",
        "https://ghp_TOKEN@api.example/v1 | https://%3Credacted%3E@api.example/v1",
        "https://graph.example/v1.0/users/ops@corp.example | https://graph.example/v1.0/users/ops@corp.example",
        "https://api.example:8443/v1/@me | https://api.example:8443/v1/@me",
        "https://u:p/ss@api.example/v1 | https://u:%3Credacted%3E@api.example/v1",
        "https://api.example/search?keyword=shoes&page_token=abc | https://api.example/search?keyword=shoes&page_token=abc",
        "https://svc:s3cret@api.example/v1?profile=x:thin:y | https://svc:%3Credacted%3E@api.example/v1?profile=x:thin:y",
        "https://api.example/v1?api_key=ab;cd)ef#g&page=1 | https://api.example/v1?api_key=%3Credacted%3E&page=1",
    })
    void a_base_url_loses_its_credentials_only(String url, String expected) {
        assertThat(InlineCredentials.redacted(new LinkedHashMap<>(Map.of("base_url", url)))).containsEntry("base_url", expected);
    }

    @Test
    void a_credential_written_as_a_number_is_redacted_too() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("password", 123456,
            "headers", List.of(new LinkedHashMap<>(Map.of("name", "X-Api-Key", "value", 4815162342L)))));

        Map<String, Object> redacted = InlineCredentials.redacted(config);

        assertThat(redacted).containsEntry("password", R);
        assertThat(redacted.get("headers")).isEqualTo(List.of(Map.of("name", "X-Api-Key", "value", R)));
    }

    /** By position, never by a key's name alone: a source column may be called password. */
    @Test
    void a_column_called_password_is_not_a_credential() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("rename", Map.of("password", "pwd_hash")));

        assertThat(InlineCredentials.redacted(config)).isEqualTo(config);
        assertThat(InlineCredentials.restored(config, null)).isEqualTo(config);
    }

    @Test
    void the_marker_text_elsewhere_is_a_value_like_any_other() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("hooks", Map.of("default", R)));

        assertThat(InlineCredentials.restored(config, null)).isEqualTo(config);
    }

    @Test
    void an_endpoint_path_loses_its_credential_parameters_and_gets_them_back() {
        String stored = "/items?api_key=K&page=1";

        String redacted = InlineCredentials.redactedUrl(stored);

        assertThat(redacted).isEqualTo("/items?api_key=" + U + "&page=1");
        assertThat(InlineCredentials.restoredPath(redacted, stored, false)).isEqualTo(stored);
        assertThatThrownBy(() -> InlineCredentials.restoredPath("/other?api_key=" + U, stored, false)).hasMessageContaining("path");
    }

    /** A connector runs from its connection block too: the same positions hold under it. */
    @Test
    void the_connection_block_is_read_as_the_root() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("connection", new LinkedHashMap<>(rest())));

        Map<String, Object> connection = (Map<String, Object>) InlineCredentials.redacted(config).get("connection");

        assertThat(connection).isEqualTo(InlineCredentials.redacted(rest()));
    }

    @Test
    void a_secret_header_read_back_is_kept_wherever_it_moved() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        List<Object> headers = new ArrayList<>((List<Object>) incoming.get("headers"));
        headers.add(0, Map.of("name", "X-New", "value", "n"));
        incoming.put("headers", headers);

        List<?> restored = (List<?>) InlineCredentials.restored(incoming, rest()).get("headers");

        assertThat(restored.get(2)).isEqualTo(Map.of("name", "X-Api-Key", "value", "k-456"));
    }

    @Test
    void the_marker_text_in_an_ordinary_header_is_a_value_like_any_other() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("headers", List.of(Map.of("name", "X-Note", "value", R))));

        assertThat(InlineCredentials.restored(config, null)).isEqualTo(config);
    }

    @Test
    void no_endpoint_credential_follows_its_connectors_new_address() {
        String stored = "/items?api_key=K";

        assertThatThrownBy(() -> InlineCredentials.restoredPath(InlineCredentials.redactedUrl(stored), stored, true))
            .hasMessageContaining("address");
        assertThatThrownBy(() -> InlineCredentials.restored(new LinkedHashMap<>(Map.of("password", R)),
            Map.of("password", "pw"), true)).hasMessageContaining("address");
    }

    /** An apply seeds its config from the stored one: an untouched destination reads as unchanged, raw or redacted. */
    @Test
    void a_destination_seeded_raw_from_the_stored_config_is_unchanged() {
        Map<String, Object> stored = new LinkedHashMap<>(Map.of("url", "jdbc:postgresql://db/erp?password=pw", "password", "pw2"));
        Map<String, Object> incoming = new LinkedHashMap<>(Map.of("url", "jdbc:postgresql://db/erp?password=pw", "password", R));

        assertThat(InlineCredentials.restored(incoming, stored)).isEqualTo(stored);
    }

    @Test
    void the_stored_config_is_left_as_it_is() {
        Map<String, Object> stored = rest();
        InlineCredentials.redacted(stored);
        assertThat(stored).isEqualTo(rest());
    }

    @Test
    void a_redacted_config_applied_again_keeps_the_stored_credentials() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());

        assertThat(InlineCredentials.restored(incoming, rest())).isEqualTo(rest());
    }

    @Test
    void an_edit_beside_the_credentials_keeps_them() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        incoming.put("page_size", 50);

        Map<String, Object> expected = rest();
        expected.put("page_size", 50);
        assertThat(InlineCredentials.restored(incoming, rest())).isEqualTo(expected);
    }

    @Test
    void a_new_value_replaces_the_stored_one() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        incoming.put("auth", new LinkedHashMap<>(Map.of("kind", "bearer", "token_value", "tok-new")));

        assertThat(((Map<?, ?>) InlineCredentials.restored(incoming, rest()).get("auth")).get("token_value")).isEqualTo("tok-new");
    }

    @Test
    void a_marker_with_nothing_stored_under_it_is_refused() {
        Map<String, Object> incoming = new LinkedHashMap<>(Map.of("password", R));

        assertThatThrownBy(() -> InlineCredentials.restored(incoming, Map.of()))
            .hasMessageContaining("password")
            .hasMessageContaining(R);
    }

    /** #2003: a credential is kept only where it was: its whole block, and every destination, unchanged. */
    @Test
    void a_marker_under_a_renamed_header_is_refused() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        ((List<Map<String, Object>>) incoming.get("headers")).get(1).put("name", "X-Other-Key");

        assertThatThrownBy(() -> InlineCredentials.restored(incoming, rest())).hasMessageContaining("headers[1]");
    }

    @Test
    void a_marker_under_an_auth_of_another_kind_is_refused() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        ((Map<String, Object>) incoming.get("auth")).put("kind", "api_key");

        assertThatThrownBy(() -> InlineCredentials.restored(incoming, rest())).hasMessageContaining("auth");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "base_url | https://api-user:%3Credacted%3E@attacker.example/v1",
        "token_url | https://attacker.example/token",
    })
    void no_credential_follows_a_changed_destination(String key, String value) {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        incoming.put(key, value);

        assertThatThrownBy(() -> InlineCredentials.restored(incoming, rest())).hasMessageContaining(key);
    }

    @Test
    void a_new_destination_with_its_credentials_written_again_is_taken() {
        Map<String, Object> incoming = InlineCredentials.redacted(rest());
        incoming.put("base_url", "https://api-user:new@elsewhere.example/v1");
        incoming.put("auth", Map.of("kind", "bearer", "token_value", "tok-new"));
        ((List<Map<String, Object>>) incoming.get("headers")).get(1).put("value", "k-new");

        assertThat(InlineCredentials.restored(incoming, rest()).get("base_url"))
            .isEqualTo("https://api-user:new@elsewhere.example/v1");
    }
}
