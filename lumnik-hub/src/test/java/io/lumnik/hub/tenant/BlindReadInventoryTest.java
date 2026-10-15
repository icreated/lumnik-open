package io.lumnik.hub.tenant;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guardian for the nineteenth one.
 *
 * <p>{@code TenantConnectionInterceptor} binds the tenant with {@code SET LOCAL
 * app.current_tenant}, and {@code SET LOCAL} is transaction-scoped: outside a transaction it is
 * discarded before the query runs, the policy predicate goes NULL, and an RLS-protected read
 * returns <b>zero rows without an error</b>. Writes carry {@code @Transactional} because they
 * need it to commit; reads do not need it to RUN, only to be right — which is why this defect
 * keeps arriving one door at a time. Production found it on the secret store; a shortlist found
 * eight more in the hub; widening this sweep past the hub found nine more across the identity
 * CRUD surface, whose policies had never been read under a transaction at all.
 *
 * <p>{@link BlindReadSweepRlsIT} proves the behaviour on the hub's doors. This test catches the
 * door written next week, and it reads PRODUCTION SOURCE to do it.
 *
 * <p><b>Two modules, because the rule is not one module's.</b> The first version scanned
 * {@code lumnik-hub} only and reported a clean sweep while nine identity resources were reading
 * blind. A guard whose scope is narrower than its rule reports the shape of its own blind spot.
 *
 * <p><b>No file is skipped for mentioning the admin datasource.</b> The first version skipped any
 * file containing {@code @DataSource("admin")}, which dropped {@code SecretResource} — the site
 * of the original defect — and {@code EntityReadResource}, both of which also inject a Panache
 * repository. The guard could not prove anything about the very case it was written for.
 *
 * <p>It fails in both directions: a new unannotated read fails it, and so does an entry in
 * {@link #DELIBERATE} that matches nothing, so an exception cannot outlive its reason.
 */
class BlindReadInventoryTest {

  /**
   * Reads allowed to run without a transaction, each with the reason a reviewer can check.
   *
   * <p>{@code identity.tenant} carries no RLS policy — {@code Tenant} extends {@code AuditedEntity},
   * not {@code TenantedEntity}, and no migration ever enabled row-level security on that table. A
   * tenant list is the one identity read that is not tenant-scoped by nature, so there is no GUC
   * for it to lose. (The hub's own cross-tenant list is a different matter: its COUNTS read tables
   * that do carry policies, which is why {@code TenantListResource} moved to the admin datasource
   * instead of gaining an annotation.)
   */
  private static final Map<String, String> DELIBERATE = Map.ofEntries(
      Map.entry("TenantResource.listAll", "identity.tenant has no RLS policy"),
      Map.entry("TenantResource.findById", "identity.tenant has no RLS policy"),
      Map.entry("ConnectorAdminResource.runNow",
          "opens its own transaction for the read; the run rows must commit on their own"),
      Map.entry("JdbcSourceResource.test", "reads the customer's database, not the hub's"),
      Map.entry("JdbcSourceResource.discover", "reads the customer's database, not the hub's"),
      Map.entry("JdbcSourceResource.add",
          "opens its own transactions for the existence check and the writes; the introspection of "
              + "the customer's database stays outside one"),
      Map.entry("EndpointResource.test",
          "opens its own transaction for the read; the pre-flight's HTTP call stays outside one"),
      Map.entry("IntegrationResource.test",
          "opens its own transaction for the read; the delivery's HTTP call stays outside one"),
      Map.entry("IntegrationResource.replay",
          "opens its own transaction for the read; the replay's HTTP call stays outside one and "
              + "WebhookReplayService records it in another"),
      Map.entry("PlatformDeliveryResource.replay",
          "opens its own transaction for the read; the replay's HTTP call stays outside one and "
              + "WebhookReplayService records it in another"),
      Map.entry("EntityConflictsResource.list",
          "opens its own transaction for each entity's read; one shared transaction let one entity's "
              + "failed statement abort every later entity's read (#929)"),
      Map.entry("HookOverrideResource.upsert", "HookOverrideStore.setScript carries the transaction"),
      Map.entry("HookOverrideResource.delete", "HookOverrideStore.deleteOverride carries the transaction"),
      Map.entry("OrgResource.create",
          "opens its own transaction for the checks; the geocoder's HTTP call stays outside one"),
      Map.entry("OrgResource.update",
          "opens its own transaction for the checks; the geocoder's HTTP call stays outside one"),
      Map.entry("RestDiscoveryResource.probe",
          "opens its own transaction for the read; the probe's HTTP call stays outside one"),
      Map.entry("RestDiscoveryResource.fromOpenApi",
          "reads a remote OpenAPI spec, not the hub's database"),
      Map.entry("EntityResolutionReadResource.ambiguous",
          "EntityIdentityRepository is the admin datasource, BYPASSRLS, tenant bound explicitly in "
              + "every statement — no RLS policy to lose"),
      Map.entry("EntityResolutionReadResource.history",
          "EntityIdentityRepository is the admin datasource, BYPASSRLS, tenant bound explicitly in "
              + "every statement — no RLS policy to lose"));
      // EntityAssertionResource no longer injects EntityIdentityRepository (the /simplify pass
      // moved its ref-existence check to a caught foreign-key violation instead of a pre-check
      // query) — its own text no longer matches this sweep's *Repository heuristic at all, so it
      // needs no entry here any more.

  private static final List<SourceTree> MODULES = List.of(
      SourceTree.of("lumnik-hub", "io/lumnik/hub"),
      SourceTree.of("lumnik-identity", "io/lumnik/identity"));

  /**
   * Every JAX-RS verb, not only {@code @GET}. The first version scanned {@code @GET} alone and
   * reported a clean sweep while {@code IntegrationResource.test} — a {@code @POST} that reads the
   * endpoint it is about to fire at — answered 404 on every real hub. A read does not become
   * transactional because the door it sits behind is spelled POST.
   */
  private static final Pattern VERB =
      Pattern.compile("^\\s*@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b");
  /** Endpoints are public; a private helper matching here would mean the scan lost its place. */
  private static final Pattern DECLARATION = Pattern.compile("^\\s*public\\s+.*?\\b(\\w+)\\s*\\(");
  /** {@code PanacheRepository} is covered by the first alternative; it is not a third case. */
  private static final Pattern PERSISTENCE = Pattern.compile("\\b\\w*Repository\\b|EntityManager");

  @Test
  void no_endpoint_reads_the_rls_datasource_outside_a_transaction() throws IOException {
    Map<String, String> found = new LinkedHashMap<>();
    for (SourceTree module : MODULES) {
      for (Path p : module.javaFiles()) {
        // Comments stripped: a sentence naming @Transactional is not the annotation, which is
        // the same rule SourceTree.code() exists to state. Line numbers do not survive that, so
        // a finding names the method rather than a line — which is what you would grep anyway.
        String src = SourceTree.code(Files.readString(p));
        if (!src.contains("@Path(")) continue;
        if (!PERSISTENCE.matcher(src).find()) continue;

        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if (!VERB.matcher(lines[i]).find()) continue;
          // Forward to the FIRST public declaration, with no line budget. An earlier version
          // capped the walk at twelve lines and SKIPPED the endpoint when the declaration sat
          // past it — silently, which is the failure mode this whole test exists to end. It
          // skipped SecretResource.list, the very method #385 was found on, whose @GET is
          // thirteen lines above its signature.
          boolean transactional = false;
          String method = null;
          for (int j = i; j < lines.length && method == null; j++) {
            if (j > i && VERB.matcher(lines[j]).find()) break;  // ran into the next endpoint
            if (lines[j].contains("@Transactional")) transactional = true;
            Matcher m = DECLARATION.matcher(lines[j]);
            if (m.find()) method = m.group(1);
          }
          assertThat(method)
              .as("%s: an endpoint with no public declaration after it — the scanner lost its place, "
                  + "and a scanner that loses its place reports a clean sweep", p)
              .isNotNull();
          if (!transactional) {
            String file = p.getFileName().toString().replace(".java", "");
            found.put(file + "." + method, p.toString());
          }
        }
      }
    }

    List<String> unexpected = found.keySet().stream()
        .filter(k -> !DELIBERATE.containsKey(k)).toList();
    assertThat(unexpected)
        .as("an endpoint that can reach the RLS-enforced datasource without a transaction reads zero "
            + "rows and says nothing — add @Transactional, or move the read to the admin "
            + "datasource if it is deliberately cross-tenant")
        .isEmpty();

    List<String> stale = DELIBERATE.keySet().stream().filter(k -> !found.containsKey(k)).toList();
    assertThat(stale)
        .as("an exception that matches nothing: the code moved and the reason did not follow it")
        .isEmpty();
  }
}
