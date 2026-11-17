package io.lumnik.hub.tenant;

import io.lumnik.hub.api.DeploymentWideInterceptor;
import io.lumnik.testkit.sources.SourceTree;
import io.quarkus.narayana.jta.runtime.interceptor.TransactionalInterceptorRequired;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1991, #2016: every call that writes what every tenant reads — an entity's view and manifest, a saved view, an app,
 * the platform corpus — sits in a {@code @DeploymentWide} door method (or class), or in a class this test exempts with
 * its reason. Anchored on the writers' mutating methods, not on who injects them: a constructor, or a service between
 * the door and the writer, cannot hide a write. A mark counts on a door only: off a resource class, the filter never
 * runs. A new writer of a deployment-wide thing joins {@link #WRITES}.
 */
class DeploymentWideInventoryTest {

  /** Each writer, and its methods that change what every tenant reads. */
  private static final Map<String, String> WRITES = Map.of(
      "EntityViewCompiler", "apply|delete|reshape|afterRebuilt",
      "SavedViewStore", "upsert|delete",
      "AppRegistrationStore", "upsert|delete",
      "PlatformIndexer", "reindex");

  /** Callers that are no door: the hub's own work, with no caller whose role could be asked. */
  private static final Map<String, String> EXEMPT = Map.of(
      "io.lumnik.hub.connector.mapping.writer.DynamicTableWriter",
      "rebuilds entity views from their stored manifests around a column widening: the definitions are unchanged");

  /** As DoorInventoryTest's: a literal's text is not code ({@code @Path("/{name}")} holds a brace). */
  private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\n])*\"");
  private static final Pattern CLASS = Pattern.compile("\\bclass\\s+\\w+");
  /** An annotation's array value ({@code @Consumes({…})}): its braces are no block. */
  private static final Pattern ANNOTATION_ARRAY = Pattern.compile("(@[\\w.]+\\s*\\(\\s*)\\{[^{}]*}");
  private static final Pattern DOOR = Pattern.compile("@(?:jakarta\\.ws\\.rs\\.)?Path\\s*\\(");
  /** A resource method: the filter runs on nothing else. */
  private static final Pattern VERB = Pattern.compile("@(?:GET|POST|PUT|DELETE|PATCH)\\b");

  @Test
  void every_write_to_what_every_tenant_reads_is_in_a_deployment_wide_door() throws IOException {
    Path root = SourceTree.reactorRoot().resolve("lumnik-hub/src/main/java");
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String rel = root.relativize(f).toString();
        String className = rel.substring(0, rel.length() - ".java".length()).replace(File.separatorChar, '.');
        if (EXEMPT.containsKey(className)) continue;
        unmarkedWrites(SourceTree.code(Files.readString(f))).forEach(w -> found.add(className + " " + w));
      }
    }
    assertThat(found).as("""
        These calls write what every tenant reads outside a @DeploymentWide door: on a multi-tenant hub, a tenant's \
        admin would rewrite or delete another tenant's. Mark the door method or class, or exempt a caller that is \
        no door, with its reason.""").isEmpty();
  }

  @Test
  void every_exemption_still_writes() throws IOException {
    for (String className : EXEMPT.keySet()) {
      Path f = SourceTree.reactorRoot().resolve("lumnik-hub/src/main/java/" + className.replace('.', '/') + ".java");
      assertThat(f).as(className + " is exempt and gone").exists();
      assertThat(unmarkedWrites(SourceTree.code(Files.readString(f))))
          .as(className + " is exempt and no longer writes: drop the exemption").isNotEmpty();
    }
  }

  @Test
  void a_write_is_found_wherever_the_writer_comes_from_and_a_mark_is_honoured() {
    String door = "@Path(\"/x\") class R { private final SavedViewStore store; R(SavedViewStore store) { this.store = store; } "
        + "@GET public X get() { return store.find(\"a\"); } "
        + "@DELETE @Path(\"/{n}\") public X delete(String n) { store.delete(n); return null; } }";
    assertThat(unmarkedWrites(door)).containsExactly("store.delete");
    assertThat(unmarkedWrites(door.replace("@DELETE", "@DELETE @DeploymentWide"))).as("on the method").isEmpty();
    assertThat(unmarkedWrites(door.replace("@Path(\"/{n}\")", "@Path(\"/{n}\") @DeploymentWide")))
        .as("a mark after a path holding a brace").isEmpty();
    assertThat(unmarkedWrites("@DeploymentWide " + door)).as("on the class").isEmpty();
    assertThat(unmarkedWrites("class S { @Inject EntityViewCompiler c; void run() { c.apply(m, y, null); } }"))
        .as("a service between the door and the writer").containsExactly("c.apply");
    assertThat(unmarkedWrites("class S { @Inject PlatformIndexer i; Object s() { return i.status(); } }"))
        .as("a read").isEmpty();
    assertThat(unmarkedWrites("class S { @Inject EntityViewCompiler c; @DeploymentWide void run() { c.apply(m); } }"))
        .as("a mark off a door does nothing").containsExactly("c.apply");
    assertThat(unmarkedWrites(door.replace("@DELETE", "@DELETE @DeploymentWide @Consumes({\"a\", \"b\"})")))
        .as("an annotation's array after the mark").isEmpty();
    assertThat(unmarkedWrites("@Path(\"/x\") class R { @Inject SavedViewStore s; @DELETE public X d(String n) { return h(n); } "
        + "@DeploymentWide private X h(String n) { s.delete(n); return null; } }"))
        .as("a mark on a helper, where the interceptor never runs").containsExactly("s.delete");
    assertThat(unmarkedWrites("@Path(\"/x\") class R { @Inject Instance<PlatformIndexer> i; @POST public void r() { i.get().reindex(); } }"))
        .as("through an Instance").containsExactly("i.reindex");
    assertThat(unmarkedWrites("@Path(\"/x\") class R { @Inject SavedViewStore s; @DELETE public void d(List<String> n) { n.forEach(s::delete); } }"))
        .as("as a method reference").containsExactly("s.delete");
  }

  /** Around the write's transaction: the count is let go after the commit, never before it. */
  @Test
  void the_count_outlives_the_writes_transaction() {
    assertThat(DeploymentWideInterceptor.class.getAnnotation(Priority.class).value())
        .isLessThan(TransactionalInterceptorRequired.class.getAnnotation(Priority.class).value());
  }

  /** The writer calls in {@code source} whose method, and class, carry no @DeploymentWide. */
  static List<String> unmarkedWrites(String source) {
    String code = ANNOTATION_ARRAY.matcher(STRING_LITERAL.matcher(source).replaceAll("\"\"")).replaceAll("$1");
    Matcher cls = CLASS.matcher(code);
    String classHeader = cls.find() ? code.substring(0, cls.start()) : "";
    boolean door = DOOR.matcher(classHeader).find();
    if (door && classHeader.contains("@DeploymentWide")) return List.of();
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, String> w : WRITES.entrySet()) {
      // a field, a parameter or a local; held directly or as an Instance<…>
      Matcher var = Pattern.compile("\\b(?:Instance\\s*<\\s*)?" + w.getKey() + "\\s*>?\\s+(\\w+)\\s*[;,)=]").matcher(code);
      while (var.find()) {
        // called, through .get() when an Instance, or passed as a method reference
        Matcher call = Pattern.compile("\\b" + var.group(1) + "\\s*(?:\\.\\s*get\\s*\\(\\s*\\))?\\s*(?:\\.\\s*("
            + w.getValue() + ")\\s*\\(|::\\s*(" + w.getValue() + ")\\b)").matcher(code);
        while (call.find()) {
          String header = enclosingMethodHeader(code, call.start());
          if (!door || !header.contains("@DeploymentWide") || !VERB.matcher(header).find()) {
            out.add(var.group(1) + "." + (call.group(1) != null ? call.group(1) : call.group(2)));
          }
        }
      }
    }
    return out.stream().distinct().toList();
  }

  /** The annotations and signature of the method whose body holds {@code at}: the class body is depth 1. */
  private static String enclosingMethodHeader(String code, int at) {
    int depth = 0;
    int bodyStart = -1;
    for (int i = 0; i < at; i++) {
      char ch = code.charAt(i);
      if (ch == '{' && ++depth == 2) bodyStart = i;
      else if (ch == '}') depth--;
    }
    if (bodyStart < 0) return "";
    int from = Math.max(code.lastIndexOf(';', bodyStart), code.lastIndexOf('}', bodyStart));
    int classBody = code.lastIndexOf('{', bodyStart - 1);
    return code.substring(Math.max(Math.max(from, classBody), 0), bodyStart);
  }
}
