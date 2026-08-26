package io.lumnik.extraction.spi.ask;

/**
 * Discovery-time strategy: build a SchemaCard for a (tenant, scope) by reading
 * source-specific metadata. The JDBC provider (reading the source's own Application
 * Dictionary-style label tables, where present) ships first; REST (OpenAPI) and
 * CSV (headers) are future impls.
 */
@FunctionalInterface
public interface SchemaLabelProvider {

    /** Build the SchemaCard for this tenant + business scope. */
    SchemaCard discover(long tenantId, String scope);

    /** Source family this provider handles; used to select among providers. */
    default String sourceType() { return "generic"; }
}
