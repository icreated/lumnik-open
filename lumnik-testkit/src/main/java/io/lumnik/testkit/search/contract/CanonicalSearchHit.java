package io.lumnik.testkit.search.contract;

import java.util.List;
import java.util.Map;

public record CanonicalSearchHit(
    String entity,
    Map<String, String> key,
    Map<String, String> fields,
    Map<String, List<String>> highlights,
    String canonicalLink
) {}
