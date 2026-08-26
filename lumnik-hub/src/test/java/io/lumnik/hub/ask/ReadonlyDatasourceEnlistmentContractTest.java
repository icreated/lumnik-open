package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift contract: JTA enlistment on the {@code readonly} datasource may be disabled under
 * {@code %test} ONLY. It must never be promoted to all profiles.
 *
 * <p>This test previously asserted the exact opposite. On 2026-08-11 the `%test`-only setting was
 * promoted to every profile as "defence in depth", reasoning that a datasource whose only job is
 * running guarded read-only SQL has no business in a transaction. That reasoning was wrong, and
 * this test pinned the mistake.
 *
 * <p><b>Enlistment is load-bearing for RLS here.</b> {@link io.lumnik.hub.sql.TenantRlsScope} pins
 * the tenant with {@code SET LOCAL app.current_tenant} and closes unqualified table names with
 * {@code SET LOCAL search_path = ''}. {@code SET LOCAL} is transaction-scoped: on a non-enlisted
 * (autoCommit) connection each statement commits into its own empty transaction and is discarded
 * before the query. Disabling enlistment therefore unbinds the tenant on all four raw-JDBC readers
 * — view render, view detail, ask-to-SQL, workflow data-truth — leaving them returning zero rows
 * (fail-closed for isolation) with the LeakGuard search_path closure gone (fail-open).
 *
 * <p>Nothing caught it: under {@code %test} enlistment is disabled anyway, so the test profile
 * cannot reproduce the production behaviour. That asymmetry is why this contract is asserted on
 * the file rather than on the resolved config — and why {@code TenantRlsScope} now fails fast on a
 * missing transaction, which is the guard that actually holds.
 */
class ReadonlyDatasourceEnlistmentContractTest {

  private static final String KEY = "quarkus.datasource.\"readonly\".jdbc.transactions";

  /**
   * Collects EVERY declaration of the key, whatever profile carries it. Checking only the
   * unprofiled spelling would leave the regression one keystroke away: {@code %prod.<KEY>=disabled}
   * unbinds production exactly as the unprofiled form did, while a test that only looks for the
   * unprofiled key stays green.
   */
  @Test
  void the_only_permitted_declaration_is_test_scoped_and_disabled() throws Exception {
    List<String> lines = Files.readAllLines(Path.of("src/main/resources/application.properties"));

    List<String> declarations = new ArrayList<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (line.startsWith("#")) continue;
      // matches KEY= and %anyprofile.KEY=
      if (line.startsWith(KEY + "=") || line.matches("%[^.]+\\." + Pattern.quote(KEY) + "=.*")) {
        declarations.add(line);
      }
    }

    assertThat(declarations)
        .as("'%s' may be declared exactly once, and only under %%test. Any other profile — "
            + "unprofiled, %%prod, %%dev — unbinds the tenant on all four raw-JDBC readers, "
            + "because enlistment is what lets TenantRlsScope's SET LOCAL survive to the query.", KEY)
        .hasSize(1);

    String only = declarations.get(0);
    assertThat(only)
        .as("the single declaration must be %%test-scoped")
        .startsWith("%test." + KEY + "=");
    assertThat(only.substring(only.indexOf('=') + 1).strip())
        .as("declared but enabled would be worse than absent: it reads as a deliberate posture "
            + "while behaving like the default")
        .isEqualToIgnoringCase("disabled");
  }
}
