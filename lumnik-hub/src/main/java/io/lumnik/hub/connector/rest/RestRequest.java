package io.lumnik.hub.connector.rest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * The request to a next URL the server wrote (a {@code Link} header, a body link): the server's URL is
     * authoritative, so a query param it already carries is not added again — Gitea and GitHub echo every param
     * into their next link, and appending ours too grew the URL by one copy per page (#1633). A param the URL
     * does not carry still rides, as with {@link #withUrl}.
     */
    public RestRequest following(String u) {
        Set<String> carried = queryKeys(u);
        Map<String,String> q = new HashMap<>(queryParams);
        q.keySet().removeIf(carried::contains);
        return new RestRequest(method, u, headers, q, body);
    }

    /** The names of the query parameters a URL already carries, decoded. */
    public static Set<String> queryKeys(String url) {
        int at = url.indexOf('?');
        if (at < 0) return Set.of();
        int hash = url.indexOf('#', at);
        String query = url.substring(at + 1, hash < 0 ? url.length() : hash);
        Set<String> keys = new HashSet<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            try {
                keys.add(URLDecoder.decode(key, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                keys.add(key);   // not valid percent-encoding: its raw spelling is the name we can compare
            }
        }
        return keys;
    }
}
