package io.lumnik.hub.connector.api.types;

public record FieldDef(String name, FieldType type, boolean nullable, String externalRef) {}
