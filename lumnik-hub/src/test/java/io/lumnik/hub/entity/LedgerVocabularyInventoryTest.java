package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerVocabularyInventoryTest {

  /** Phrases that said "the ledger = sources disagree" — false for a lookup ambiguity (#846). */
  private static final List<String> STALE = List.of(
      "Où les sources divergent", "toutes les sources concordent", "Les sources ne sont pas d’accord",
      "Toutes les sources sont d’accord", "every source agrees", "all sources agree",
      "sources still disagree", "sources disagree", "unresolved fusion conflicts");

  /** Every surface that names the ledger, relative to the repo root. */
  private static final List<String> SURFACES = List.of(
      "lm/internal/cli/view.go", "lm/internal/cli/conflicts.go", "lm/internal/cli/ask.go",
      "lm/internal/client/conflicts.go", "lm/internal/client/view.go",
      "lm/internal/tui/help.go", "lm/internal/tui/resources/ask.go",
      "lm/internal/tui/resources/viewpage.go", "lm/internal/tui/resources/conflicts.go",
      "lumnik-hub/src/main/resources/META-INF/resources/app/src/components/detail.mjs",
      "lumnik-hub/src/main/java/io/lumnik/hub/ask/AbstentionGuard.java",
      "lumnik-hub/src/main/java/io/lumnik/hub/entity/EntityConflictsResource.java",
      "docs/entities.md", "docs/apps/views.md", "docs/apps/pwa.md", "docs/cli/tui.md",
      "docs/cli/lm-reference.md", "docs/apps/demo/views/10-customers.yaml");

  @Test
  void no_ledger_surface_still_says_the_ledger_is_only_about_disagreeing_sources() throws Exception {
    Path repo = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
    List<String> hits = new ArrayList<>();
    for (String rel : SURFACES) {
      String text = Files.readString(repo.resolve(rel));
      for (String phrase : STALE) if (text.contains(phrase)) hits.add(rel + " ⟵ " + phrase);
    }
    assertThat(hits).as("reword to cover lookup ambiguities too").isEmpty();
  }

  @Test
  void the_new_wording_is_present_on_the_surfaces_that_render_the_ledger() throws Exception {
    Path repo = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
    for (String rel : List.of("lm/internal/cli/view.go",
        "lumnik-hub/src/main/resources/META-INF/resources/app/src/components/detail.mjs")) {
      assertThat(Files.readString(repo.resolve(rel))).as(rel).contains("Ambiguïtés et divergences");
    }
  }
}
