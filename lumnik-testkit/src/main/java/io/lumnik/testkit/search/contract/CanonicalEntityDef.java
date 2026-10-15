package io.lumnik.testkit.search.contract;

import java.util.List;

public record CanonicalEntityDef(String name, List<CanonicalFieldDef> columns) {}
