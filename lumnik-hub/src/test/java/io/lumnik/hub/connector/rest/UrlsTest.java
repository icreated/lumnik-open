package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class UrlsTest {

    @Test
    void joins_with_exactly_one_slash() {
        assertEquals("http://api/items", Urls.join("http://api", "/items"));   // already clean
        assertEquals("http://api/items", Urls.join("http://api", "items"));    // missing leading slash
        assertEquals("http://api/items", Urls.join("http://api/", "/items"));  // trailing slash on base
        assertEquals("http://api/items", Urls.join("http://api/", "items"));   // both
    }

    @Test
    void blank_or_null_path_returns_base_without_trailing_slash() {
        assertEquals("http://api", Urls.join("http://api/", ""));
        assertEquals("http://api", Urls.join("http://api", null));
        assertEquals("http://api", Urls.join("http://api", "   "));
    }

    @Test
    void collapses_multiple_leading_slashes_and_trailing_base_slashes() {
        assertEquals("http://api/items", Urls.join("http://api", "//items"));
        assertEquals("http://api/items", Urls.join("http://api", "///items"));
        assertEquals("http://api/items", Urls.join("http://api//", "//items"));
    }

    @Test
    void trims_surrounding_whitespace_on_path() {
        assertEquals("http://api/items", Urls.join("http://api", "  /items  "));
    }

    @Test
    void fails_fast_on_null_base() {
        assertThrows(NullPointerException.class, () -> Urls.join(null, "/items"));
    }

    /**
     * What a base_url must be, and — the part that matters — what the refusal may say about it.
     *
     * <p>A {@code base_url} legally carries userinfo, and operators do paste tokens into one. The
     * refusal travels to a CLI, a 400 body and a log, so it must speak about STRUCTURE and never
     * echo the value: {@code URISyntaxException.getMessage()} appends the offending string in full
     * — password included — while {@code getReason()} and {@code getIndex()} carry the diagnosis
     * without it.
     */
    @Test
    void aRefusalNamesTheReason_neverTheUrl() {
        var why = Urls.whyNotAbsolute("https://user:s3cr3t@host/x y");

        assertThat(why).isPresent();
        assertThat(why.get()).doesNotContain("s3cr3t").doesNotContain("host");
        assertThat(why.get()).contains("Illegal character").contains("26");
    }

    @Test
    void anAbsoluteUrlIsAccepted_privateHostsIncluded() {
        assertThat(Urls.whyNotAbsolute("http://erp.invalid")).isEmpty();
        assertThat(Urls.whyNotAbsolute("http://127.0.0.1:8091")).isEmpty();
        assertThat(Urls.whyNotAbsolute("https://api.example.com/v1")).isEmpty();
    }

    @Test
    void somethingThatIsNotAnAbsoluteUrlIsRefused() {
        assertThat(Urls.whyNotAbsolute("erp.invalid")).contains("must be an absolute URL (scheme + host)");
        assertThat(Urls.whyNotAbsolute("")).contains("must not be blank");
        assertThat(Urls.whyNotAbsolute(null)).contains("must not be blank");
    }

    /**
     * A URL on its way into a message or a log loses everything that can carry a credential.
     *
     * <p>It must work on a string that does NOT parse, because the one caller that needs it is the
     * refusal for a URL that could not be read — so this is deliberately a string operation, not a
     * {@code URI} one.
     */
    @Test
    void aUrlShownToAnOperatorLosesItsUserinfoAndQuery() {
        assertThat(Urls.forDisplay("https://user:s3cr3t@host/spec.json"))
            .isEqualTo("https://…@host/spec.json");
        assertThat(Urls.forDisplay("https://host/spec.json?token=abc123"))
            .isEqualTo("https://host/spec.json");
        assertThat(Urls.forDisplay("https://user:pw@host/spec.json?token=abc123"))
            .isEqualTo("https://…@host/spec.json");
    }

    /**
     * A fragment carries credentials too — an OAuth implicit flow returns its {@code access_token}
     * there — and it is as visible in a log line as a query string.
     */
    @Test
    void forDisplayDropsTheFragmentAsWellAsTheQuery() {
        assertThat(Urls.forDisplay("https://host/spec.json#access_token=abc123"))
            .isEqualTo("https://host/spec.json");
        assertThat(Urls.forDisplay("https://user:pw@host/spec.json?a=1#access_token=abc123"))
            .isEqualTo("https://…@host/spec.json");
    }

    /**
     * An unescaped {@code @} inside the userinfo (a password holding one): everything up to the LAST
     * {@code @} of the authority goes — stopping at the first left the rest of the password in the
     * message (#1167 review). An {@code @} in the path is not userinfo and stays.
     */
    @Test
    void forDisplayStripsTheWholeUserinfoUpToItsLastAt() {
        assertThat(Urls.forDisplay("https://user:p@ss@host/x")).isEqualTo("https://…@host/x");
        assertThat(Urls.forDisplay("https://a@b@c@host:8443/p?q=1")).isEqualTo("https://…@host:8443/p");
        assertThat(Urls.forDisplay("https://host/users/a@b")).isEqualTo("https://host/users/a@b");
        assertThat(Urls.forDisplay("/users/me@example.com")).isEqualTo("/users/me@example.com");
    }

    /**
     * A protocol-relative (network-path) reference, {@code //host/path}, has an authority too: its
     * userinfo goes like an absolute URL's — the rule used to look only for {@code ://} (#1167 review).
     * A bare path whose segments hold {@code //} later on is not a network reference and stays.
     */
    @Test
    void forDisplayStripsTheUserinfoOfAProtocolRelativeReference() {
        assertThat(Urls.forDisplay("//user:pw@evil.example/p/SECRET?k=v")).isEqualTo("//…@evil.example/p/SECRET");
        assertThat(Urls.forDisplay("//a@b@evil.example/p")).isEqualTo("//…@evil.example/p");
        assertThat(Urls.forDisplay("//evil.example/p")).isEqualTo("//evil.example/p");
        assertThat(Urls.forDisplay("/a//b@c")).isEqualTo("/a//b@c");
    }

    /**
     * Any {@code ://} still opens an authority, wherever it sits — a compound scheme
     * ({@code jdbc:postgresql://}) or text before the URL. Anchoring the rule on a single-word scheme
     * at the start dropped these back to clear text (#1167 review, a regression of the previous fix).
     */
    @Test
    void forDisplayStillStripsTheUserinfoAfterAnyColonSlashSlash() {
        assertThat(Urls.forDisplay("jdbc:postgresql://user:pw@db.example/erp")).isEqualTo("jdbc:postgresql://…@db.example/erp");
        assertThat(Urls.forDisplay("source https://user:p@ss@host/x")).isEqualTo("source https://…@host/x");
        assertThat(Urls.forDisplay("HTTPS://user:pw@host/x")).isEqualTo("HTTPS://…@host/x");
    }

    /**
     * The same treatment for a bare endpoint path, which is caller input and legitimately carries a
     * query. The userinfo rule is anchored on {@code ://}, so a path without a scheme is untouched
     * by it — and a full URL handed in where a path was expected is still redacted.
     */
    @Test
    void forDisplayAlsoCleansABareEndpointPath() {
        assertThat(Urls.forDisplay("/partners?api_key=s3cr3t")).isEqualTo("/partners");
        assertThat(Urls.forDisplay("/partners")).isEqualTo("/partners");
        assertThat(Urls.forDisplay("https://user:pw@host/partners")).isEqualTo("https://…@host/partners");
    }

    @Test
    void forDisplayLeavesAnOrdinaryUrlAlone_andSurvivesOneThatIsNotAUrl() {
        assertThat(Urls.forDisplay("http://erp.invalid/v1/spec")).isEqualTo("http://erp.invalid/v1/spec");
        assertThat(Urls.forDisplay("not a url")).isEqualTo("not a url");
        assertThat(Urls.forDisplay(null)).isNull();
    }

    @Test
    void whyNotHttp_accepts_http_and_https_and_names_any_other_scheme() {
        assertThat(Urls.whyNotHttp("https://auth.example/oauth/token")).isEmpty();
        assertThat(Urls.whyNotHttp("HTTP://auth.example:8098/oauth/token")).isEmpty();
        assertThat(Urls.whyNotHttp("ftp://auth.example/token")).contains("must be an http or https URL");
        assertThat(Urls.whyNotHttp("/oauth/token")).contains("must be an absolute URL (scheme + host)");
        assertThat(Urls.whyNotHttp(" ")).contains("must not be blank");
    }
}
