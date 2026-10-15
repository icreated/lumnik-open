package io.lumnik.hub.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A stored links value that is odd must never cost a search its titles (#1051). */
class SearchIndexStoreLinksTest {

    @Test void a_missing_or_json_null_value_is_no_links() {
        assertThat(SearchIndexStore.links(null)).isEmpty();
        assertThat(SearchIndexStore.links("null")).isEmpty();
    }

    @Test void an_unreadable_value_is_no_links_not_a_failure() {
        assertThat(SearchIndexStore.links("{\"not\":\"a list\"}")).isEmpty();
        assertThat(SearchIndexStore.links("[1, 2")).isEmpty();
    }

    @Test void a_stored_link_is_read_and_an_extra_key_is_ignored() {
        assertThat(SearchIndexStore.links("[{\"field\":\"code\",\"url\":\"https://e.example.com/{code}\",\"later\":1}]"))
            .containsExactly(new SearchIndexManifest.Link("code", "https://e.example.com/{code}"));
    }
}
