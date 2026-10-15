package io.lumnik.hub.manifest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one description of an external link template (#1050), shared by every manifest kind that links out
 * (kind:View today, kind:SearchIndex next, #1051). Only the set of names a placeholder may use differs.
 */
class LinkTemplateTest {

    private static final List<String> NAMES = List.of("category_id", "name");
    private static final String PATH = "spec.links[0].url";

    private static String check(String url) {
        return LinkTemplate.externalUrl(url, PATH, NAMES, "spec.columns");
    }

    @Test void placeholders_are_the_plain_identifiers_between_braces_in_order() {
        assertThat(LinkTemplate.placeholders("https://x.example.com/{name}/{category_id}?q={name}"))
            .containsExactly("name", "category_id", "name");
        assertThat(LinkTemplate.placeholders("https://x.example.com/a b/{1x}/{}/{ n }")).isEmpty();
    }

    @Test void a_valid_template_is_returned_stripped_and_unchanged() {
        assertThat(check("  https://erp.example.com:8443/c/{category_id}?tab=1#{name}  "))
            .isEqualTo("https://erp.example.com:8443/c/{category_id}?tab=1#{name}");
    }

    @Test void a_placeholder_may_sit_in_path_query_or_fragment() {
        for (String url : List.of("https://erp.example.com/c/{category_id}",
                                  "https://erp.example.com:8443/c?id={category_id}",
                                  "https://erp.example.com/c#{category_id}")) {
            assertThat(check(url)).isEqualTo(url);
        }
    }

    @Test void a_placeholder_must_name_an_allowed_name_and_says_where_to_look() {
        assertThatThrownBy(() -> check("https://erp.example.com/c/{ghost}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(PATH, "{ghost}", "spec.columns");
    }

    @Test void a_template_without_a_placeholder_is_a_valid_constant_link() {
        assertThat(check("https://erp.example.com/help")).isEqualTo("https://erp.example.com/help");
    }

    @Test void the_length_is_bounded() {
        assertThatThrownBy(() -> check("https://erp.example.com/" + "a".repeat(LinkTemplate.MAX_URL_LENGTH)))
            .hasMessageContaining(PATH).hasMessageContaining("at most");
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
        "http://erp.example.com/c/{category_id}",                     // https only
        "javascript:alert({category_id})",
        "//erp.example.com/c/{category_id}",
        "/relative/{category_id}",
        "https://user:pw@erp.example.com/c/{category_id}",            // no credentials in a manifest URL
        "https://erp.example.com@evil.example/c/{category_id}",       // userinfo without a password
        "https://erp.example.com\\@evil.example/c/{category_id}",     // a backslash is a slash to a browser
        "https://erp%2eexample.com/c/{category_id}",                  // percent in the host
        "https://érp.example.com/c/{category_id}",                    // the host is ASCII, spelled out
        "https://{category_id}.example.com/",                         // the host is literal
        "https://erp.example.com{category_id}/",                      // so is the whole authority
        "https://erp.example.com:{category_id}/",
        "https:///c/{category_id}",
        "https://erp.example.com/c/{category_id} x",                  // whitespace
        "https://erp.example.com\r\n/c/{category_id}",                // CRLF
        "https://erp.example.com/c/{category_id",                     // unbalanced brace
        "https://erp.example.com/c/}{category_id}",
    })
    void a_malformed_or_unsafe_template_is_refused(String url) {
        assertThatThrownBy(() -> check(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(PATH);
    }
}
