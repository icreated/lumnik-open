package io.lumnik.hub.connector.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.sql.SqlIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link MappingSpec} from a {@link TableDescriptor} and a chunk template,
 * then can serialise it to YAML.
 *
 * <p>Convention:
 * <ul>
 *   <li>Mapping id = {@code sourceName + "--" + tableName}</li>
 *   <li>RAG corpus   = {@code sourceName + "-" + tableName}</li>
 *   <li>Table target uses {@code autoSchema = true} so the connector creates
 *       the staging table when absent.</li>
 * </ul>
 */
@ApplicationScoped
public class MappingYamlBuilder {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /**
   * Builds a {@link MappingSpec} with both a RAG target and a Table target.
   *
   * @param sourceName    logical source name (e.g. "legacy-erp-prod")
   * @param td            table descriptor from schema introspection
   * @param chunkTemplate heuristic or LLM-generated template string
   */
  public MappingSpec build(String sourceName, TableDescriptor td, String chunkTemplate) {
    // DDL-injection boundary (layer 2, alongside the escaping fix in RlsDdl): this builds a
    // MappingTarget.Table straight from schema-introspection input, bypassing
    // MappingLoader.parseTable — refuse a non-identifier schema/name here too, since
    // JdbcSourceResource.addTable echoes an API-request-supplied table name into TableDescriptor
    // unverified (SchemaIntrospector.describeOne does not check it against real DB metadata).
    if (!SqlIdentifiers.isPlainIdentifier(td.schema()) || !SqlIdentifiers.isPlainIdentifier(td.name())) {
      throw new ConfigValidationException(
          "table schema/name must be a plain identifier (letters, digits, underscore; not starting with a digit): "
              + td.schema() + "." + td.name());
    }
    String id = sourceName + "--" + td.name();
    String corpus = sourceName + "-" + td.name();

    MappingTarget.Rag rag = new MappingTarget.Rag(
        corpus,
        chunkTemplate,
        Map.of("source", sourceName, "table", td.name()),
        null   // use default embedding model
    );

    MappingTarget.Table table = new MappingTarget.Table(
        td.schema(),
        td.name(),
        true,                            // autoSchema
        MappingTarget.OnConflict.UPDATE, // prefer UPDATE for re-ingestion
        List.of()
    );

    MappingTarget targets = new MappingTarget(rag, table, null, null);

    return new MappingSpec(
        id,
        1,
        targets,
        MappingSpec.StreamMode.STREAM,
        "Auto-generated mapping for " + td.schema() + "." + td.name(),
        List.of(),   // dataset hooks
        List.of(),   // row hooks
        List.of(),   // cell hooks
        ErrorPolicy.soft()
    );
  }

  /**
   * Serialises a {@link MappingSpec} to a YAML string.
   */
  public String dumpYaml(MappingSpec spec) {
    try {
      return YAML.writeValueAsString(spec);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialise MappingSpec to YAML: " + e.getMessage(), e);
    }
  }
}
