package io.lumnik.hub.manifest;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every manifest kind names the keys it does not honour, and every door that accepts one asks.
 *
 * <p>A key the parser ignores is not a small thing. It is the integrator writing something, the hub
 * answering 200, and the thing they wrote never happening — the exact shape the C3 verdict closed
 * for {@code kind:View}, {@code kind:Process} and {@code kind:Workflow}, and the exact shape
 * {@code kind:Entity} still has: it deserializes with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and
 * exposes no inventory at all, so {@code scope:} written for {@code scopes:} is dropped in silence
 * and the entity becomes invisible to every scope-bound user, with a clean report.
 *
 * <p>Two rules, both anchored on the production source rather than on a list of kinds kept here:
 *
 * <ol>
 *   <li>a manifest parser exposes {@code unknownKeys}, and {@code honoredSurface} so the refusal can
 *       say what IS honoured rather than only what is not;
 *   <li>a door that parses a manifest calls it — a written inventory nobody consults is the
 *       machinery-not-unused-but-unusable shape this repo has met before.
 * </ol>
 *
 * <p>Both fail in either direction: a new kind that forgets the inventory fails, and so does an
 * exemption whose file no longer exists or no longer parses a manifest.
 */
class ManifestKeyInventoryTest {

  private static final SourceTree TREE = SourceTree.of("lumnik-hub", "io/lumnik/hub");

  /**
   * Kinds whose key inventory deliberately lives elsewhere. Each needs a reason a reader can check
   * — a name alone is a rubber stamp.
   */
  private static final Map<String, String> EXEMPT_PARSERS = Map.of();

  /**
   * Doors that parse a manifest and deliberately do NOT refuse on unknown keys. The reason has to
   * survive a reader's scrutiny, because this is the exemption most likely to be abused.
   */
  private static final Map<String, String> EXEMPT_DOORS = Map.of(
      "ViewRenderResource.java",
      "POST /api/views/render takes a manifest the PWA fetched from the store and hands back. "
          + "Refusing an unknown key here would stop RENDERING a view that is already saved with "
          + "one — apply only started refusing them at C3, so older rows may carry them. The apply "
          + "door is the gate; this is a read. Reporting them as a warning in the response would "
          + "change the PWA contract and wants its own change.",
      "ViewDetailResource.java",
      "POST /api/views/detail — the same body, from the same store, for the same reason.",
      "SearchOperationsResource.java",
      "POST /api/platform/search/index/{name}/rebuild re-parses core.search_index.manifest_yaml — "
          + "already stored and already validated by SearchIndexApplyResource's own apply door — "
          + "solely to rebuild triggerRebuild's authorizedSnapshot for its stale-declaration equality "
          + "check. The apply door is the gate; this is a read of an already-applied declaration, "
          + "never an unvalidated write path.");

  @Test
  void everyManifestParserNamesTheKeysItDoesNotHonour() throws IOException {
    Map<String, String> missing = new LinkedHashMap<>();
    List<String> seen = new ArrayList<>();

    for (Path p : sources("ManifestParser.java")) {
      String name = p.getFileName().toString();
      seen.add(name);
      if (EXEMPT_PARSERS.containsKey(name)) continue;

      String code = SourceTree.code(Files.readString(p));
      List<String> absent = new ArrayList<>();
      if (!code.contains("unknownKeys")) absent.add("unknownKeys");
      if (!code.contains("honoredSurface")) absent.add("honoredSurface");
      if (!absent.isEmpty()) missing.put(name, String.join(" + ", absent));
    }

    assertThat(seen).as("the sweep must find the parsers it is meant to police").isNotEmpty();
    assertThat(missing)
        .as("""
            These manifest kinds accept keys they never honour and say nothing. The integrator \
            writes it, the hub answers 200, and the thing they wrote never happens. Expose \
            unknownKeys + honoredSurface — ManifestKeys does the walking — or add the file to \
            EXEMPT_PARSERS with a reason a reader can check.""")
        .isEmpty();
  }

  @Test
  void everyDoorThatParsesAManifestAsksWhatItDoesNotHonour() throws IOException {
    Map<String, String> silent = new LinkedHashMap<>();
    List<String> seen = new ArrayList<>();

    for (Path p : sources("Resource.java")) {
      String code = SourceTree.code(Files.readString(p));
      if (!code.contains("ManifestParser")) continue;   // not a manifest door

      String name = p.getFileName().toString();
      seen.add(name);
      if (EXEMPT_DOORS.containsKey(name)) continue;
      if (!code.contains(".unknownKeys(")) silent.put(name, pathOf(code));
    }

    assertThat(seen).as("the sweep must find the doors it is meant to police").isNotEmpty();
    assertThat(silent)
        .as("""
            These doors parse a manifest and never ask which of its keys are ignored. An inventory \
            written but not consulted is machinery that is not unused but UNUSABLE — refuse on \
            unknownKeys, quoting honoredSurface, or add the file to EXEMPT_DOORS with a reason.""")
        .isEmpty();
  }

  /**
   * An exemption goes stale three ways, and the doc promised all three: the file is gone, the file
   * is no longer on the axis the rule polices, or the reason was never written. Checking only the
   * first two-thirds is the same defect this PR fixes — a claim with no code behind it.
   */
  @Test
  void everyExemptionStillNamesSomethingOnTheAxisItIsExemptedFrom() throws IOException {
    List<String> stale = new ArrayList<>();

    for (Map.Entry<String, String> e : EXEMPT_PARSERS.entrySet()) {
      Path p = find(e.getKey());
      if (p == null) { stale.add(e.getKey() + " — no such source any more"); continue; }
      if (!e.getKey().endsWith("ManifestParser.java")) {
        stale.add(e.getKey() + " — no longer a manifest parser, so the exemption says nothing");
      }
      if (e.getValue().isBlank()) stale.add(e.getKey() + " — exempted without a reason");
    }

    for (Map.Entry<String, String> e : EXEMPT_DOORS.entrySet()) {
      Path p = find(e.getKey());
      if (p == null) { stale.add(e.getKey() + " — no such source any more"); continue; }
      if (!SourceTree.code(Files.readString(p)).contains("ManifestParser")) {
        stale.add(e.getKey() + " — no longer parses a manifest, so the exemption says nothing");
      }
      if (e.getValue().isBlank()) stale.add(e.getKey() + " — exempted without a reason");
    }

    assertThat(stale)
        .as("a stale exemption is a rule quietly widening — delete it or restate why it holds")
        .isEmpty();
  }

  // ---- the guardian, guarded ----

  @Test
  void aMentionInACommentOrAnImportIsNotAnImplementation() {
    assertThat(SourceTree.code("import io.lumnik.hub.manifest.ManifestKeys;\nclass P {}"))
        .as("an import must not satisfy either rule").doesNotContain("ManifestKeys");
    assertThat(SourceTree.code("class P { /* unknownKeys is not needed here */ }"))
        .as("a comment explaining the absence is not the thing").doesNotContain("unknownKeys");
    assertThat(SourceTree.code("class P { List<String> unknownKeys(String y) { return List.of(); } }"))
        .as("a real declaration is").contains("unknownKeys");
  }

  private static List<Path> sources(String suffix) throws IOException {
    return TREE.javaFiles(suffix);
  }

  private static Path find(String fileName) throws IOException {
    return TREE.find(fileName).orElse(null);
  }

  private static String pathOf(String code) {
    int at = code.indexOf("@Path(\"");
    if (at < 0) return "(no @Path)";
    int end = code.indexOf('"', at + 7);
    return end < 0 ? "(no @Path)" : code.substring(at + 7, end);
  }
}
