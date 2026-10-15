package io.lumnik.testkit.search.corpus;

import java.util.Map;

public record CorpusRow(String id, long tenantId, Map<String, String> fields) {
}
