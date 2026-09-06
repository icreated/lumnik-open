package io.lumnik.identity;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource whose own URL names a tenant must reconcile it with the caller's.
 *
 * <p>The rule held nowhere and nobody noticed, because nothing went wrong loudly: RLS scopes every
 * tenanted table, so a foreign {@code {tenantId}} leaked nothing. It simply made the path parameter
 * a fiction — {@code UserResource.listAll} checked only that the tenant EXISTS and then ran a query
 * with no tenant predicate, so a caller received their own rows under someone else's URL, with a
 * 200. Confinement without reconciliation is a surface that answers questions nobody asked.
 *
 * <p>Anchored on the production source: the axis is the {@code @Path}, not a list of class names
 * kept here. A resource added tomorrow under {@code /api/tenants/&#123;tenantId&#125;/…} is policed
 * without editing this file — which is the only reason the rule will still hold in a year.
 */
class UrlTenantReconciliationInventoryTest {

  private static final SourceTree TREE = SourceTree.of("lumnik-identity", "io/lumnik/identity");

  /** The call every such resource must make, on every path that reaches a tenanted row. */
  private static final String RECONCILIATION = "Require.callerOwnsTenant(";

  /**
   * Resources that name a tenant in their path and deliberately do NOT reconcile it. Each needs a
   * reason a reader can check.
   */
  private static final Map<String, String> EXEMPT = Map.of();

  @Test
  void everyResourceWhoseUrlNamesATenantReconcilesItWithTheCallers() throws IOException {
    Map<String, String> unreconciled = new LinkedHashMap<>();
    List<String> seen = new ArrayList<>();

    for (Path p : resources()) {
      String code = SourceTree.code(Files.readString(p));
      String path = pathOf(code);
      if (!path.contains("{tenantId}")) continue;   // not on the axis

      String name = p.getFileName().toString();
      seen.add(name);
      if (EXEMPT.containsKey(name)) continue;
      if (!code.contains(RECONCILIATION)) unreconciled.put(name, path);
    }

    assertFalse(seen.isEmpty(), "the sweep must find the resources it is meant to police");
    assertEquals(Map.of(), unreconciled, """
        These resources name a tenant in their own URL and never ask whether it is the caller's. \
        RLS still confines the rows, so nothing leaks — but the path parameter becomes a fiction \
        and the caller is served their own data under someone else's URL. Call \
        Require.callerOwnsTenant(tenantId) before any lookup, or add the file to EXEMPT with a \
        reason a reader can check.""");
  }

  @Test
  void everyExemptionStillNamesAResourceOnTheAxis() throws IOException {
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, String> e : EXEMPT.entrySet()) {
      Path p = find(e.getKey());
      if (p == null) { stale.add(e.getKey() + " — no such resource any more"); continue; }
      if (!pathOf(SourceTree.code(Files.readString(p))).contains("{tenantId}")) {
        stale.add(e.getKey() + " — its URL no longer names a tenant, so the exemption says nothing");
      }
      if (e.getValue().isBlank()) stale.add(e.getKey() + " — exempted without a reason");
    }
    assertEquals(List.of(), stale,
        "a stale exemption is a rule quietly widening — delete it or restate why it holds");
  }

  // ---- the guardian, guarded ----

  @Test
  void aMentionInACommentOrAnImportIsNotTheCall() {
    assertFalse(SourceTree.code("import io.lumnik.identity.Require;\nclass R {}").contains(RECONCILIATION),
        "an import must not satisfy the rule");
    assertFalse(SourceTree.code("class R { /* Require.callerOwnsTenant( is not needed here */ }")
        .contains(RECONCILIATION), "a comment explaining the absence is not the call");
    assertTrue(SourceTree.code("class R { void f(Long t) { Require.callerOwnsTenant(t); } }")
        .contains(RECONCILIATION), "a real call is");
  }

  @Test
  void theAxisIsTheUrlNotTheClassName() {
    assertEquals("/api/tenants/{tenantId}/users",
        pathOf("@Path(\"/api/tenants/{tenantId}/users\") class R {}"));
    assertFalse(pathOf("@Path(\"/api/tenants\") class R {}").contains("{tenantId}"));
    assertEquals("(no @Path)", pathOf("class R {}"));
  }

  @Test
  void aTenantNamedOnAMethodPathIsStillOnTheAxis() {
    // Every resource today declares {tenantId} at class level, so reading only the FIRST @Path
    // happened to work. It is a false negative waiting for the first resource that splits them —
    // and a sweep that silently skips a door is the one failure this whole file exists to prevent.
    String split = """
        @Path("/api/tenants")
        class R {
          @GET @Path("/{tenantId}/things")
          List<Thing> list(@PathParam("tenantId") Long tenantId) { return List.of(); }
        }
        """;
    assertTrue(pathOf(split).contains("{tenantId}"),
        "a tenant named on a method @Path puts the resource on the axis just as a class one does");
  }

  private static List<Path> resources() throws IOException {
    return TREE.javaFiles("Resource.java");
  }

  private static Path find(String fileName) throws IOException {
    return TREE.find(fileName).orElse(null);
  }

  /**
   * The resource's URL, preferring whichever {@code @Path} names a tenant.
   *
   * <p>Reading only the FIRST one happened to work because every resource today declares
   * {@code &#123;tenantId&#125;} at class level. It is a false negative waiting for the first
   * resource that splits them — {@code @Path("/api/tenants")} on the class and
   * {@code @Path("/&#123;tenantId&#125;/things")} on the method — which the sweep would then skip
   * as "not on the axis". A sweep that silently drops a door is the one failure this file exists
   * to prevent.
   */
  private static String pathOf(String code) {
    String first = null;
    int at = code.indexOf("@Path(\"");
    while (at >= 0) {
      int end = code.indexOf('"', at + 7);
      if (end < 0) break;
      String path = code.substring(at + 7, end);
      if (first == null) first = path;
      if (path.contains("{tenantId}")) return path;
      at = code.indexOf("@Path(\"", end);
    }
    return first == null ? "(no @Path)" : first;
  }
}
