package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #1753, found on a real PrestaShop: a source that ignores the paging parameter answers page one again and again.
 * The read followed it to the page cap and the run reported Completed — 50 000 rows in, 5 distinct. A page repeated
 * is refused, across chunks too; the page cap reached is refused, never a complete read.
 */
class RestEndpointReaderRepeatedPageTest {

    private final ConnectorEndpoint endpoint = new ConnectorEndpoint();
    private final RestEndpointReaderChunkingTest.StatelessPagePagination pagination =
        new RestEndpointReaderChunkingTest.StatelessPagePagination();
    private final RestEndpointReaderChunkingTest.PagedHttp http = new RestEndpointReaderChunkingTest.PagedHttp();

    {
        endpoint.setName("products");
    }

    private RestEndpointReader reader(PaginationFactory pag) {
        return new RestEndpointReader(endpoint, new RestEndpointReaderChunkingTest.PassThroughAuth(), pag,
            new RateLimiterRegistry(), http, new ObjectMapper(), SecretRefs.environmentOnly(name -> null));
    }

    private static ConnectorContext ctx() {
        return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "shop", "rest-generic",
            Map.of("base_url", "https://shop.test", "default_rate_limit", "100000/s"), new SyncMode.FullRefresh(), null, null, null, null,
            new OwnedRunScope("test"));
    }

    /** Every id read, chunk after chunk, a fresh reader per chunk as the engine builds one. */
    private List<Object> readAll(PaginationFactory pag, int chunkSize) {
        ConnectorContext ctx = ctx();
        List<Object> ids = new ArrayList<>();
        ConnectorCursor cursor = null;
        for (int chunk = 0; ; chunk++) {
            assertThat(chunk).as("a read that never ends").isLessThan(20_000);
            switch (reader(pag).read(chunkSize, cursor, ctx)) {
                case ReadResult.HasMore<Map<String, Object>> more -> {
                    more.records().forEach(m -> ids.add(m.get("id")));
                    cursor = more.nextCursor();
                }
                case ReadResult.EndOfStream<Map<String, Object>> end -> {
                    end.records().forEach(m -> ids.add(m.get("id")));
                    return ids;
                }
                case ReadResult.EmptyButOpen<Map<String, Object>> open -> cursor = open.position();
            }
        }
    }

    private static List<Map<String, Object>> page(int... ids) {
        List<Map<String, Object>> page = new ArrayList<>();
        for (int id : ids) page.add(Map.of("id", id));
        return page;
    }

    @Test
    void a_page_answered_twice_in_a_row_fails_the_read() {
        pagination.pages = List.of(page(1, 2, 3), page(1, 2, 3), page(4, 5, 6));
        assertThatThrownBy(() -> readAll(pagination, 1000))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("Endpoint 'products': the source answered the same page twice in a row — the paging parameter"
                + " is not reaching it (check the pagination's parameter names, or use the source's preset)");
    }

    @Test
    void a_page_answered_again_in_the_next_chunk_fails_the_read_too() {
        // One page per chunk: the page before lives in the cursor, not in the reader.
        pagination.pages = List.of(page(1, 2, 3), page(1, 2, 3), page(4, 5, 6));
        assertThatThrownBy(() -> readAll(pagination, 2))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("the source answered the same page twice in a row");
    }

    @Test
    void pages_that_only_share_some_rows_are_read() {
        pagination.pages = List.of(page(1, 2, 3), page(3, 4, 5), page(6));
        assertThat(readAll(pagination, 2)).containsExactly(1, 2, 3, 3, 4, 5, 6);
    }

    /** Never ends: page p holds the one row p — every page different, as a source that never says it is done. */
    private static PaginationFactory endless() {
        return new PaginationFactory() {
            @Override public PaginationStrategy build(Map<String, Object> cfg) {
                return new PaginationStrategy() {
                    @Override public RestRequest first(EndpointConfig c, Object incremental) {
                        return RestRequest.get("https://shop.test/products").withQuery("p", "0");
                    }
                    @Override public List<Map<String, Object>> items(RestResponse resp) {
                        return List.of(Map.of("id", resp.json().get("page").asInt()));
                    }
                    @Override public RestRequest next(RestResponse resp, RestRequest current) {
                        return current.withQuery("p", String.valueOf(resp.json().get("page").asInt() + 1));
                    }
                };
            }
        };
    }

    @Test
    void reaching_the_page_cap_before_the_last_page_fails_the_read() {
        assertThatThrownBy(() -> readAll(endless(), 1_000_000))
            .isInstanceOf(ConnectorException.class)
            .hasMessage("Endpoint 'products': reached the page cap (10000 pages) before the source's last page"
                + " — refusing to report a partial extract as a complete one");
        assertThat(http.calls).hasSize(10_000);
    }

    @Test
    void the_page_cap_holds_across_chunks() {
        assertThatThrownBy(() -> readAll(endless(), 500))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("reached the page cap (10000 pages)");
        assertThat(http.calls).hasSize(10_000);
    }
}
