package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Resolves a mapping by merging its classpath template (from {@link MappingTemplateRegistry})
 * with the per-tenant YAML override stored in {@code connector.connector_mapping}.
 *
 * <p>Uses the <em>admin</em> datasource (BYPASSRLS) so that the resolver can serve admin
 * endpoints spanning multiple tenants without needing an active tenant context.
 *
 * <p>Merge semantics: override sections ({@code dataset}, {@code row}, {@code cell}, etc.)
 * <strong>replace</strong> the template's corresponding sections entirely. Deep merging is
 * deferred — if an override wants to append, it must include the full list.
 */
@ApplicationScoped
public class MappingResolverImpl {

  private static final Logger LOG = Logger.getLogger(MappingResolverImpl.class);
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @Inject
  MappingTemplateRegistry templates;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /**
   * Returns the resolved {@link MappingSpec} for the given template and tenant.
   *
   * @param templateId the template identifier (matches the YAML {@code id} field)
   * @param tenantId   the tenant whose DB override (if any) is applied on top
   * @throws MappingValidationException if the template is unknown or YAML is malformed
   */
  public MappingSpec resolve(String templateId, Long tenantId) {
    String templateYaml = templates.rawYaml(templateId);
    if (templateYaml == null) {
      throw new MappingValidationException("unknown template: " + templateId);
    }
    String overrideYaml = findOverride(templateId, tenantId);
    if (overrideYaml == null) {
      return MappingLoader.load(templateYaml);
    }
    String merged = mergeYaml(templateYaml, overrideYaml);
    return MappingLoader.load(merged);
  }

  /**
   * Persists (or upserts) a per-tenant YAML override for the given template.
   *
   * <p>Uses {@code ON CONFLICT (tenant_id, template_id)} to update in place, incrementing
   * the version counter on each update.
   *
   * @param tenantId   the tenant that owns this override
   * @param templateId the template being overridden
   * @param yaml       the override YAML (need not repeat every section — only sections
   *                   present will replace the template's sections)
   */
  public void saveOverride(Long tenantId, String templateId, String yaml) {
    String jdbcSql = """
        INSERT INTO connector.connector_mapping
          (id, tenant_id, org_id, name, target_entity, spec_format, spec_content,
           template_id, yaml, version, created_at, updated_at)
        VALUES (?, ?, 0, ?, '-', 'yaml-override', '', ?, ?, 1, now(), now())
        ON CONFLICT (tenant_id, template_id)
          DO UPDATE SET
            yaml       = EXCLUDED.yaml,
            updated_at = now(),
            version    = connector.connector_mapping.version + 1
        """;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(jdbcSql)) {
      ps.setObject(1, UUID.randomUUID());
      ps.setLong(2, tenantId);
      ps.setString(3, "override-" + templateId);
      ps.setString(4, templateId);
      ps.setString(5, yaml);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new MappingValidationException("saveOverride failed: " + e.getMessage(), e);
    }
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private String findOverride(String templateId, Long tenantId) {
    String sql = """
        SELECT yaml FROM connector.connector_mapping
        WHERE template_id = ? AND tenant_id = ? AND deleted_at IS NULL
        LIMIT 1
        """;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, templateId);
      ps.setLong(2, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    } catch (Exception e) {
      LOG.warnf(e, "findOverride query failed for template=%s tenant=%d", templateId, tenantId);
    }
    return null;
  }

  /**
   * Section-level merge: each top-level key present in the override replaces the
   * corresponding key in the template. Keys absent from the override keep the template value.
   */
  private String mergeYaml(String templateYaml, String overrideYaml) {
    try {
      JsonNode t = YAML.readTree(templateYaml);
      JsonNode o = YAML.readTree(overrideYaml);
      ObjectNode merged = (ObjectNode) t.deepCopy();
      o.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
      return YAML.writeValueAsString(merged);
    } catch (Exception e) {
      throw new MappingValidationException("YAML merge failed: " + e.getMessage(), e);
    }
  }
}
