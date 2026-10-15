package io.lumnik.testkit.search.contract;

import java.util.Map;

public record CanonicalRow(String rowKey, Map<String, String> values) {}
