package io.lumnik.testkit.search.contract;

import java.util.List;
import java.util.Map;

public record CanonicalSearchResult(
    List<CanonicalSearchHit> hits,
    Map<String, List<String>> facets,
    boolean degraded,
    String degradedReason
) {}
