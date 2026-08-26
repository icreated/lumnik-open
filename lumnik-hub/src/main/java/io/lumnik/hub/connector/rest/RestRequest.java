package io.lumnik.hub.connector.rest;

import java.util.*;

public record RestRequest(
    String method,
    String url,
    Map<String,String> headers,
    Map<String,String> queryParams,
    String body
) {
    public RestRequest {
        Objects.requireNonNull(method); Objects.requireNonNull(url);
        headers     = headers     == null ? Map.of() : Map.copyOf(headers);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    }

    public static RestRequest get(String url) { return new RestRequest("GET", url, Map.of(), Map.of(), null); }
    public static RestRequest post(String url, String body) { return new RestRequest("POST", url, Map.of(), Map.of(), body); }

    public RestRequest withHeader(String name, String value) {
        Map<String,String> h = new HashMap<>(headers); h.put(name, value);
        return new RestRequest(method, url, h, queryParams, body);
    }
    public RestRequest withQuery(String name, String value) {
        Map<String,String> q = new HashMap<>(queryParams); q.put(name, value);
        return new RestRequest(method, url, headers, q, body);
    }
    public RestRequest withBody(String b) { return new RestRequest(method, url, headers, queryParams, b); }
    public RestRequest withUrl(String u)  { return new RestRequest(method, u,   headers, queryParams, body); }
}
