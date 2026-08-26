package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

public class PagePaginationStrategy implements PaginationStrategy {

    private final String pageParam;
    private final String sizeParam;
    private final String responsePath;
    private final int size;
    private final int startPage;
    private final String totalPagesPath;
    private final String hasMorePath;
    private final String lastPath;
    private final boolean emptyCheck;

    public PagePaginationStrategy(Map<String, String> c) {
        this.pageParam      = c.get("page_param");
        if (pageParam == null || pageParam.isBlank()) {
            throw new IllegalArgumentException("page pagination requires 'page_param'");
        }
        // size_param is optional; treat a blank value as absent so first() never emits an empty-name param.
        String sp = c.get("size_param");
        this.sizeParam      = (sp == null || sp.isBlank()) ? null : sp;
        this.size           = Integer.parseInt(c.getOrDefault("size", "50"));
        this.startPage      = Integer.parseInt(c.getOrDefault("start_page", "1"));
        this.totalPagesPath = c.get("total_pages_path");
        this.hasMorePath    = c.get("has_more_path");
        this.lastPath       = c.get("last_path");
        this.emptyCheck     = Boolean.parseBoolean(c.getOrDefault("empty_check", "true"));
        this.responsePath   = c.get("response_path");
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        RestRequest r = PaginationSupport.baseGet(cfg);
        r = r.withQuery(pageParam, String.valueOf(startPage));   // page_param is required (checked in ctor)
        // size_param is optional (some APIs have a fixed page size); skip it when absent —
        // withQuery(null, …) would NPE in RestRequest's Map.copyOf (null keys rejected).
        if (sizeParam != null) r = r.withQuery(sizeParam, String.valueOf(size));
        return r;
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        int currentPage = Integer.parseInt(current.queryParams().getOrDefault(pageParam, String.valueOf(startPage)));
        if (totalPagesPath != null) {
            Number tp = JsonPathExtractor.read(resp.json(), totalPagesPath);
            // Stop once every page is fetched. pages-fetched = currentPage - startPage + 1, which is
            // correct for both 0-indexed (start_page=0) and 1-indexed APIs — no over-fetch past the last.
            if (tp != null && currentPage - startPage + 1 >= tp.intValue()) return null;
        }
        if (hasMorePath != null) {
            Boolean hm = JsonPathExtractor.read(resp.json(), hasMorePath);
            if (!Boolean.TRUE.equals(hm)) return null;
        }
        if (lastPath != null) {
            Boolean last = JsonPathExtractor.read(resp.json(), lastPath);
            if (Boolean.TRUE.equals(last)) return null;
        }
        if (emptyCheck && items(resp).isEmpty()) return null;
        return current.withQuery(pageParam, String.valueOf(currentPage + 1));
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
