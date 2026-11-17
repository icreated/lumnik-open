package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Entity every tenant reads, as one applied from the hub's own tenant is (#2050). A test proving that rows stay with
 * their tenant reads one definition from two tenants: it applies the Entity as a tenant whose sources it can read, then
 * shares it here.
 */
public final class SharedEntities {

  /** An Entity manifest's top-level name. */
  private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*(\\S+)\\s*$");

  private SharedEntities() {
  }

  /** {@link #share} the Entity this manifest names; nothing when it names none (a refused manifest). */
  public static void shareApplied(String manifest) {
    Matcher m = NAME.matcher(manifest);
    if (m.find()) share(m.group(1));
  }

  /** Makes the stored Entity {@code name} shared, in the table and in this node's registry. */
  public static void share(String name) {
    AgroalDataSource admin = Arc.container().select(AgroalDataSource.class, new DataSource.DataSourceLiteral("admin")).get();
    Jdbc.update(admin, "SharedEntities.share", "UPDATE connector.entity_manifest SET owner_tenant_id = NULL WHERE name = ?",
        ps -> ps.setString(1, name.toLowerCase(Locale.ROOT)));
    EntityRegistry registry = Arc.container().instance(EntityRegistry.class).get();
    EntityManifest m = registry.findReloadingIfMissingHubwide(name);
    if (m != null) registry.put(m, null);
  }
}
