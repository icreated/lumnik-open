package io.lumnik.hub.connector.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.text.Text;
import java.util.*;

public record RestResponse(int status, Map<String,List<String>> headers, String body, JsonNode json) {

    private static final ObjectMapper M = new ObjectMapper();

    public static RestResponse of(int status, Map<String,List<String>> headers, String body) {
        JsonNode n;
        try { n = Text.present(body) ? M.readTree(body) : M.createObjectNode(); }
        catch (Exception e) { n = M.createObjectNode(); }
        return new RestResponse(status, headers == null ? Map.of() : headers, body, n);
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .findFirst()
            .map(e -> e.getValue().isEmpty() ? null : e.getValue().get(0));
    }

    /** {@code Content-Type …} as a refusal names it — the type, never the body. */
    public String contentTypeLabel() {
        return header("Content-Type").map(t -> "Content-Type " + t).orElse("no Content-Type");
    }
}
