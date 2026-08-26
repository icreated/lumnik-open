package io.lumnik.hub.connector.api.types;

import java.util.List;
import java.util.Set;

public record EntityDef(String name, List<FieldDef> fields, Set<String> primaryKey, Set<String> uniqueKeys) {}
