package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PaginationUrlGuard} in isolation from the two strategies that call it
 * ({@link BodyLinkPaginationStrategyTest}, {@link LinkHeaderPaginationStrategyTest} cover the wiring).
 * No network access: every host comparison below is a plain string comparison, never resolved — see the
 * class javadoc for why there is no address-range check to test here.
 */
class PaginationUrlGuardTest {

    private static final String CURRENT = "http://api.example.com/v1/items";

    @Test
    void refuses_cloud_metadata_address() {
        // Refused because the host differs from CURRENT's — not by IP classification (see class javadoc).
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://169.254.169.254/latest/meta-data/", CURRENT));
    }

    @Test
    void refuses_loopback_address_on_a_different_host() {
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://127.0.0.1/admin", CURRENT));
    }

    @Test
    void refuses_a_different_public_host() {
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://evil.example/steal", CURRENT));
    }

    /**
     * A next link is read out of the source's body, and a signed one carries its token in the query:
     * the refusal reaches the run's failure text, so it shows the link the way every URL in a message
     * is shown — without userinfo, query or fragment (#1166).
     */
    @Test
    void the_refusal_never_quotes_the_links_credential() {
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://user:pw@evil.example/p/next?k=v", CURRENT));

        assertEquals("Refusing to follow pagination URL 'http://…@evil.example/p/next': host 'evil.example'"
            + " differs from the connector's host 'api.example.com'", e.getMessage());
    }

    /**
     * A link whose userinfo holds an unescaped {@code @}: {@code URI} reads no host and the guard
     * refuses, and the refusal must not show the rest of the password either (#1167 review).
     */
    @Test
    void the_refusal_shows_no_piece_of_a_userinfo_holding_an_at() {
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://user:p@ss@evil.example/p/next?sig=SECRET", CURRENT));

        for (String leak : new String[] {"user", "ss@", "SECRET"}) {
            assertFalse(e.getMessage().contains(leak), "shows " + leak + ": " + e.getMessage());
        }
    }

    /** A protocol-relative link is refused for its scheme, and the refusal shows no userinfo either (#1167 review). */
    @Test
    void the_refusal_of_a_protocol_relative_link_shows_no_userinfo() {
        for (String link : new String[] {"//user:pw@evil.example/p/next?sig=SECRET", "//user:p@ss@evil.example/p"}) {
            ConnectorException e = assertThrows(ConnectorException.class,
                () -> PaginationUrlGuard.vet(link, CURRENT));
            for (String leak : new String[] {"user", "pw", "ss@", "SECRET"}) {   // the path is shown on purpose (forDisplay)
                assertFalse(e.getMessage().contains(leak), "shows " + leak + ": " + e.getMessage());
            }
        }
    }

    @Test
    void follows_a_legitimate_same_host_next_page() {
        String url = PaginationUrlGuard.vet("http://api.example.com/v1/items?page=2", CURRENT);
        assertEquals("http://api.example.com/v1/items?page=2", url);
    }

    @Test
    void follows_a_same_host_next_page_when_the_base_url_is_itself_a_private_range_ip() {
        // Regression guard: lumnik ingests legacy ERPs, which routinely sit on RFC-1918 networks with no
        // internal DNS. The connector's base_url being a private/loopback literal IP is a normal on-prem
        // topology, not an attack — pagination staying on that SAME host must keep working. (An earlier
        // version of this guard also range-checked the host and broke exactly this case — see the class
        // javadoc's "Why there is no loopback/private-range check".)
        String current = "http://10.0.0.5/v1/items";
        String url = PaginationUrlGuard.vet("http://10.0.0.5/v1/items?page=2", current);
        assertEquals("http://10.0.0.5/v1/items?page=2", url);
    }

    @Test
    void refuses_non_http_scheme() {
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("file:///etc/passwd", CURRENT));
    }

    @Test
    void refuses_a_scheme_downgrade_from_https_to_http_on_the_same_host() {
        // A hostile upstream must not be able to downgrade an https connector to http on the same host —
        // the (re-signed) Authorization header would then go out in plaintext on the next request.
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://api.example.com/v1/items?page=2", "https://api.example.com/v1/items"));
    }

    @Test
    void refuses_a_scheme_change_from_http_to_https_on_the_same_host() {
        // vet() pins the scheme exactly (not just "no downgrade") — same reasoning as host pinning: the
        // next page must stay on the connector's own configured protocol, not a scheme the response chose.
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("https://api.example.com/v1/items?page=2", "http://api.example.com/v1/items"));
    }

    @Test
    void host_comparison_ignores_a_trailing_dot() {
        String url = PaginationUrlGuard.vet("http://api.example.com./v1/items?page=2",
            "http://api.example.com/v1/items");
        assertEquals("http://api.example.com./v1/items?page=2", url);
    }

    @Test
    void host_comparison_is_case_insensitive() {
        String url = PaginationUrlGuard.vet("http://API.EXAMPLE.COM/v1/items?page=2", CURRENT);
        assertEquals("http://API.EXAMPLE.COM/v1/items?page=2", url);
    }

    @Test
    void userinfo_does_not_disguise_the_real_host() {
        // java.net.URI (the same parser RestHttpClient uses) resolves the host to "evil.example",
        // never to the userinfo "metadata" — this must be refused as a different host, not silently
        // accepted because a naive parser stopped at the '@'.
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("http://metadata@evil.example/", CURRENT));
    }

    @Test
    void refuses_a_same_host_port_switch() {
        // Same host, different explicit port. Port 2375 is the Docker daemon API — a hostile upstream
        // steering pagination there while staying "on the same host" is exactly the escalation this rule
        // closes.
        assertThrows(ConnectorException.class,
            () -> PaginationUrlGuard.vet("https://api.example.com:2375/v1/items?page=2",
                "https://api.example.com/v1/items"));
    }

    @Test
    void follows_when_the_next_url_spells_out_the_https_default_port() {
        // https default port 443, spelled out explicitly on the next hop only — must NOT be treated as a
        // port mismatch.
        String url = PaginationUrlGuard.vet("https://h:443/b", "https://h/a");
        assertEquals("https://h:443/b", url);
    }

    @Test
    void follows_when_the_current_url_spells_out_the_http_default_port() {
        // http default port 80, spelled out explicitly on the current hop only.
        String url = PaginationUrlGuard.vet("http://h/b", "http://h:80/a");
        assertEquals("http://h/b", url);
    }

    @Test
    void follows_a_same_explicit_port_next_page() {
        String url = PaginationUrlGuard.vet("http://h:8080/b", "http://h:8080/a");
        assertEquals("http://h:8080/b", url);
    }
}
