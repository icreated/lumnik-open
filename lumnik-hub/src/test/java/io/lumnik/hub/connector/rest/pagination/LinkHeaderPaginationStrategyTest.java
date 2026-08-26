package io.lumnik.hub.connector.rest.pagination;
import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.util.List; import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LinkHeaderPaginationStrategyTest {
    LinkHeaderPaginationStrategy s = new LinkHeaderPaginationStrategy(Map.of("rel", "next", "response_path", "$"));
    @Test void follows_next_link() {
        Map<String,List<String>> h = Map.of("Link", List.of("<https://api/x?page=2>; rel=\"next\", <https://api/x?page=10>; rel=\"last\""));
        RestResponse r = RestResponse.of(200, h, "[{}]");
        RestRequest next = s.next(r, RestRequest.get("https://api/x"));
        assertEquals("https://api/x?page=2", next.url());
    }
    @Test void stops_without_next() {
        RestResponse r = RestResponse.of(200, Map.of(), "[{}]");
        assertNull(s.next(r, RestRequest.get("https://api/x")));
    }
    @Test void refuses_cloud_metadata_address_from_link_header() {
        // SSRF: the RFC-5988 Link header is just as upstream-controlled as the body — today next()
        // follows it unconditionally.
        Map<String,List<String>> h = Map.of("Link", List.of("<http://169.254.169.254/latest/meta-data/>; rel=\"next\""));
        RestResponse r = RestResponse.of(200, h, "[{}]");
        assertThrows(RuntimeException.class, () -> s.next(r, RestRequest.get("https://api/x")));
    }
    @Test void refuses_loopback_address_from_link_header() {
        Map<String,List<String>> h = Map.of("Link", List.of("<http://127.0.0.1/admin>; rel=\"next\""));
        RestResponse r = RestResponse.of(200, h, "[{}]");
        assertThrows(RuntimeException.class, () -> s.next(r, RestRequest.get("https://api/x")));
    }
    @Test void refuses_a_different_host_from_link_header() {
        Map<String,List<String>> h = Map.of("Link", List.of("<https://evil.example/steal>; rel=\"next\""));
        RestResponse r = RestResponse.of(200, h, "[{}]");
        assertThrows(RuntimeException.class, () -> s.next(r, RestRequest.get("https://api/x")));
    }
}
