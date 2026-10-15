package io.lumnik.hub.proposal;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void keys_are_sorted_by_code_point_and_the_form_is_compact() throws Exception {
        assertThat(CanonicalJson.of(JSON.readTree("{\"b\":[1,{\"z\":null,\"a\":true}],\"a\":\"x\"}")))
            .isEqualTo("{\"a\":\"x\",\"b\":[1,{\"a\":true,\"z\":null}]}");
    }

    @Test
    void code_point_order_differs_from_utf16_order_for_supplementary_characters() throws Exception {
        String wide = new String(Character.toChars(0x1F600));
        String bmp = new String(Character.toChars(0xFF5E));
        // U+FF5E < U+1F600 as code points, but its UTF-16 unit (FF5E) sorts after the surrogate D83D
        assertThat(CanonicalJson.of(JSON.readTree("{\"" + wide + "\":1,\"" + bmp + "\":2}")))
            .isEqualTo("{\"" + bmp + "\":2,\"" + wide + "\":1}");
    }

    @Test
    void a_floating_point_number_has_no_canonical_form_here() {
        assertThatThrownBy(() -> CanonicalJson.of(JSON.readTree("{\"a\":1.5}")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Only the integers every reader keeps exactly: the site's JSON.parse rounds the rest (#822). */
    @Test
    void an_integer_outside_the_safe_range_has_no_canonical_form_here() throws Exception {
        assertThat(CanonicalJson.of(JSON.readTree("[9007199254740991,-9007199254740991,0]")))
            .isEqualTo("[9007199254740991,-9007199254740991,0]");
        for (String outside : new String[] {"9007199254740992", "-9007199254740992", "12345678901234567890"}) {
            assertThatThrownBy(() -> CanonicalJson.of(JSON.readTree("{\"a\":" + outside + "}")))
                .as(outside).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** The lock is written by the Python guard; this is the proof that both languages digest alike. */
    @Test
    void the_held_catalogue_and_rule_digest_to_what_the_lock_says() throws Exception {
        TrustedCatalogue trusted = TrustedCatalogue.load();
        assertThat(trusted.held("2026.10.0")).isPresent();
        assertThat(trusted.rule("stalled-documents@1")).isPresent();
        assertThat(Files.readString(Repo.root().resolve("lumnik-hub/src/main/resources/proposal/catalogue.lock.json")))
            .contains(CanonicalJson.digest(JSON.readTree(
                Files.readString(Repo.root().resolve("lumnik-hub/src/main/resources/proposal/catalogue.json")))));
    }
}
