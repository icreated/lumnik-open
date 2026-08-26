package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

public interface PaginationStrategy {
    /** First request; {@code incremental} is the opaque incremental hint (e.g. a watermark), or null. */
    RestRequest first(EndpointConfig cfg, Object incremental);

    /** Returns next request, or null when there are no more pages. */
    RestRequest next(RestResponse resp, RestRequest current);

    /** Extracts the data rows from the response body. */
    List<Map<String, Object>> items(RestResponse resp);
}
