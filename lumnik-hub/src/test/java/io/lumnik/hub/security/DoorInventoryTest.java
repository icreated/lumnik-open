package io.lumnik.hub.security;

import io.lumnik.common.security.ScopeAccess;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.TenantWideFilter;
import io.lumnik.identity.TenantInPathFilter;
import io.lumnik.testkit.sources.ScopeBoundaryRule;
import io.lumnik.testkit.sources.ScopeInventory;
import io.lumnik.testkit.sources.SourceTree;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every door, in every module, decides about a scope-bound caller (#1992, #2017). A door is any Java class carrying a
 * {@code @Path}, whatever its URL. {@link ScopeBoundaryInventoryTest} also sweeps the doors that touch a scope by a
 * marker.
 *
 * <p>A door decides by being {@code @TenantWide} (its filter refuses every bound caller), by asking
 * {@code ScopeGrants}/{@code ScopeAccess}/{@code ConnectorScope} itself, or through a named helper that asks them. A
 * door that does none of these is listed in the exemptions, with a reason a reader can check: what it answers, and why
 * that is no tenant's scoped data. The lists live in {@code scripts/scope-inventory.json} ({@code doors}), the file
 * {@code check-scope-inventory.py} reads per pull request.
 *
 * <p>A file is judged whole, as {@link ScopeBoundaryInventoryTest} judges it: one deciding call anywhere counts for
 * every method of the class. That is why {@code @TenantWide} marks a class, never a method.
 */
class DoorInventoryTest {

  /** A helper that decides for the doors calling it: its call, and the file that must itself ask a decider. */
  private static final Map<String, String> HELPERS = ScopeInventory.map("doors", "helper_deciders");

  /**
   * The calls that decide directly: the {@code @ScopeDecision} methods of {@code ScopeGrants} and {@code ScopeAccess}.
   * A helper's file must make one of these: a helper never vouches for another, and {@code @TenantWide} decides only
   * on a door, where its filter binds.
   */
  private static final List<String> DIRECT_CALLS = Stream.concat(
      ScopeBoundaryRule.decidingCalls(ScopeGrants.class).stream(),
      ScopeBoundaryRule.decidingCalls(ScopeAccess.class).stream()).toList();

  /** {@code @TenantWide}: an annotation a door carries, never a call that decides for its callers. */
  private static final List<String> EXTRA = ScopeInventory.list("hub_scope_boundary", "extra_deciding_calls");

  /** What counts as a decision in a door: a direct call, {@code @TenantWide}, or a named helper's. */
  private static final List<String> DECIDING_CALLS =
      Stream.of(DIRECT_CALLS.stream(), EXTRA.stream(), HELPERS.keySet().stream()).flatMap(s -> s).toList();

  /** Doors that decide nothing themselves, by class name. Each reason says what the door answers. */
  private static final Map<String, String> EXEMPT = ScopeInventory.exempt("doors");

  /** A string literal, so a {@code "@Path("} written as text (a test helper reading a door's URL) makes no door. */
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\n])*\"");

  /** {@code @Path(}, {@code @Path (} or {@code @jakarta.ws.rs.Path(}. */
  private static final Pattern PATH_ANNOTATION = Pattern.compile("@(?:jakarta\\.ws\\.rs\\.)?Path\\s*\\(");

  @Test
  void every_door_decides_about_a_scope_bound_caller() throws IOException {
    Map<String, String> doors = doors();
    assertThat(doors).as("the sweep must find the doors it polices — an empty sweep is not a clean one")
        .hasSizeGreaterThanOrEqualTo(ScopeInventory.positiveInt("doors", "min_doors"));

    List<String> undecided = doors.entrySet().stream()
        .filter(door -> !consults(door.getValue()) && !EXEMPT.containsKey(door.getKey()))
        .map(Map.Entry::getKey).toList();
    assertThat(undecided)
        .as("""
            These doors pass a caller bound to a scope:* role on its role alone. Mark the class @TenantWide when \
            its subject is the whole tenant, ask ScopeGrants/ConnectorScope when it is one scope's, or exempt it \
            in scripts/scope-inventory.json (doors) with what it answers when it holds no tenant's scoped data.""")
        .isEmpty();
  }

  @Test
  void every_exemption_names_a_door_that_does_not_decide() throws IOException {
    Map<String, String> doors = doors();
    List<String> stale = new ArrayList<>();
    for (var e : EXEMPT.entrySet()) {
      String code = doors.get(e.getKey());
      if (!e.getKey().contains(".")) stale.add(e.getKey() + " — not a class name (io.lumnik.….SomeResource)");
      else if (code == null && !pruned(e.getKey())) stale.add(e.getKey() + " — no such door");
      if (code != null && consults(code)) stale.add(e.getKey() + " — decides now, so the exemption says nothing");
      if (e.getValue().isBlank()) stale.add(e.getKey() + " — exempted without a reason");
    }
    assertThat(stale).as("a stale exemption is a rule quietly widening").isEmpty();
  }

  /** A call that decides for its callers is a helper, checked below; the extra list holds annotations only. */
  @Test
  void the_extra_deciding_calls_are_annotations() {
    assertThat(EXTRA).as("a call that decides for its callers belongs in doors.helper_deciders, where its file is checked")
        .allMatch(c -> c.startsWith("@"));
  }

  /** A helper counts as a decision only while it asks a decider itself: removing that call must turn the sweep red. */
  @Test
  void every_helper_decider_asks_a_decider_itself() throws IOException {
    List<String> blind = new ArrayList<>();
    for (var h : HELPERS.entrySet()) {
      Path file = SourceTree.reactorRoot().resolve(h.getValue());
      String owner = h.getKey().split("\\.|::")[0];
      if (!Files.isRegularFile(file)) blind.add(h.getKey() + " — " + h.getValue() + " does not exist");
      else if (!file.getFileName().toString().equals(owner + ".java"))
        blind.add(h.getKey() + " — " + h.getValue() + " is not " + owner + "'s file");
      else if (!ScopeBoundaryRule.consults(DIRECT_CALLS, SourceTree.code(Files.readString(file))))
        blind.add(h.getKey() + " — " + h.getValue() + " asks no decider, so its callers decide nothing");
    }
    assertThat(blind).as("a helper decider that stopped deciding credits every door that calls it").isEmpty();
  }

  @Test
  void a_mention_is_not_a_decision() {
    assertThat(consults("class R { /* @TenantWide is not needed here */ }")).isFalse();
    assertThat(consults("import io.lumnik.common.security.TenantWide;\nclass R {}")).isFalse();
    assertThat(consults("@TenantWide\nclass R {}")).isTrue();
    assertThat(consults("class R { void f() { if (ScopeGrants.granted().isPresent()) return; } }")).isTrue();
  }

  @Test
  void a_door_is_any_class_carrying_a_path_whatever_its_url() {
    assertThat(isDoor("@Path(\"/api/platform/x\") class R {}")).isTrue();
    assertThat(isDoor("@Path(\"/api/entities/{name}/resolve\") class R {}")).isTrue();
    assertThat(isDoor("@Path(BASE) class R { static final String BASE = \"/x\"; }")).isTrue();
    assertThat(isDoor("class R { String u = \"/api/platform/x\"; }")).as("not a resource").isFalse();
    assertThat(isDoor("class R { int at = src.indexOf(\"@Path(\\\"\"); }")).as("@Path( in a string").isFalse();
    assertThat(isDoor("@jakarta.ws.rs.Path(\"/x\") class R {}")).as("the qualified annotation").isTrue();
    assertThat(isDoor("@Path (\"/x\") class R {}")).as("a space before the parenthesis").isTrue();
  }

  /** On a door carrying both markers, a bound caller gets the scope refusal whichever tenant the URL names. */
  @Test
  void the_scope_refusal_runs_before_the_url_tenant_check() {
    assertThat(TenantWideFilter.class.getAnnotation(Priority.class).value())
        .as("equal priorities leave the two filters' order undefined")
        .isLessThan(TenantInPathFilter.class.getAnnotation(Priority.class).value());
  }

  private static boolean isDoor(String code) {
    return PATH_ANNOTATION.matcher(STRING_LITERAL.matcher(code).replaceAll("\"\"")).find();
  }

  private static boolean consults(String javaSource) {
    return ScopeBoundaryRule.consults(DECIDING_CALLS, javaSource);
  }

  /**
   * A door the open edition prunes: this tree has no {@code lumnik-rag} (the RAG sweep's module), and the class's
   * package is in no module of it ({@code io.lumnik.rag}, {@code io.lumnik.hub.pro}). In the monorepo nothing is pruned,
   * so a missing door is reported.
   */
  private static boolean pruned(String className) throws IOException {
    String rag = ScopeInventory.value("rag_scope_boundary", "module").asText();
    if (Files.isDirectory(SourceTree.reactorRoot().resolve(rag))) return false;
    String pkg = className.substring(0, className.lastIndexOf('.')).replace('.', File.separatorChar);
    for (Path root : mainSourceRoots()) if (Files.isDirectory(root.resolve(pkg))) return false;
    return true;
  }

  private static Map<String, String> doors;

  /** Every resource class, in every module of the reactor: its class name, and its code without comments or imports. */
  private static Map<String, String> doors() throws IOException {
    if (doors != null) return doors;
    Map<String, String> found = new TreeMap<>();
    for (Path java : mainSourceRoots()) {
      try (Stream<Path> files = Files.walk(java)) {
        for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          String src = Files.readString(f);
          if (!src.contains("Path")) continue;   // most files: no strip
          String code = SourceTree.code(src);
          if (isDoor(code)) found.put(className(java, f), code);
        }
      }
    }
    return doors = found;
  }

  private static List<Path> roots;

  /**
   * Each {@code lumnik-*} module's {@code src/main/java}, listed rather than named: a module added later is swept
   * without editing this test. Not {@code SourceTree.find(module, …)}: from a module's cwd its first candidate is
   * that module's own tree, whichever module is asked for.
   */
  private static List<Path> mainSourceRoots() throws IOException {
    if (roots != null) return roots;
    try (Stream<Path> modules = Files.list(SourceTree.reactorRoot())) {
      return roots = modules.filter(m -> m.getFileName().toString().startsWith("lumnik-"))
          .map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).sorted().toList();
    }
  }

  private static String className(Path sourceRoot, Path file) {
    String rel = sourceRoot.relativize(file).toString();
    return rel.substring(0, rel.length() - ".java".length()).replace(File.separatorChar, '.');
  }
}
