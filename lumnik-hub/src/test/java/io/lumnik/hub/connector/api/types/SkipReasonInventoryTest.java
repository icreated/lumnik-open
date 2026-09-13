package io.lumnik.hub.connector.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guardian of the benign-skip rule, anchored on the PRODUCTION constant rather than on a copy
 * of it — {@code SkipReason.BENIGN_NAMES} is read here, never restated, so this test cannot agree
 * with a list that has drifted.
 *
 * <p>What it exists to catch. The rule had three homes, spelled two ways: a string list in
 * {@code ConnectorDeadLetterRepository}, and an {@code instanceof} pair in each of
 * {@code ChunkExecutor} and {@code DeadLetterCapture}, under a comment asking the next reader to
 * keep them in step by hand. They now share one constant, and the failure this test watches for is
 * the one that survives that unification: {@code BENIGN_NAMES} holding a string no reason persists
 * under — a typo there silently makes NOTHING benign, every duplicate becomes a dead letter, and
 * OTK Law 1 starts counting declared skips as loss.
 */
class SkipReasonInventoryTest {

    /** Every variant of the sealed hierarchy, each one built the way the runtime builds it. */
    static Stream<SkipReason> everyReason() {
        return Stream.of(
            new SkipReason.MappingFailed("statut", "not a date"),
            new SkipReason.ValidationFailed(List.of("statut is required")),
            new SkipReason.WriteFailed("t_orders", "23505"),
            new SkipReason.Duplicate("CMD-1"),
            new SkipReason.PolicyExcluded("skip-empty-rows"),
            new SkipReason.MalformedSource("orders.csv", 42L, "unterminated quote"));
    }

    /**
     * The inventory is complete by construction — {@code persistedName()} switches exhaustively
     * over a sealed interface, so a seventh variant does not fail HERE, it fails to COMPILE there.
     * This asserts the other half: the six names are distinct, since two reasons sharing one name
     * would make the string-keyed query answer for both.
     */
    @Test
    void every_reason_persists_under_a_name_of_its_own() {
        List<String> names = everyReason().map(SkipReason::persistedName).toList();
        assertThat(names).doesNotContainNull().doesNotHaveDuplicates().hasSize(6);
    }

    /**
     * The drift this is really here for: a name in {@code BENIGN_NAMES} that no reason answers to.
     * It fails closed in the worst way — nothing is benign, so declared skips are dead-lettered and
     * counted as loss — and nothing else in the tree would notice.
     */
    @Test
    void every_benign_name_is_a_name_some_reason_actually_persists_under() {
        List<String> all = everyReason().map(SkipReason::persistedName).toList();
        assertThat(SkipReason.BENIGN_NAMES).isNotEmpty().allSatisfy(n ->
            assertThat(all).as("BENIGN_NAMES entry '%s' matches no SkipReason", n).contains(n));
    }

    /**
     * Both directions, one row per variant: adding a benign reason without listing it fails here,
     * and listing a reason that is a real loss fails here too.
     *
     * <p>⚠️ A fourth reader lives in Go and cannot import this constant: OTK Law 1
     * ({@code recordsIn == recordsOut + dlqCount}, {@code lm/test/otk/checks/reconcile.go}) holds
     * only while a benign skip is never dead-lettered. Changing this table means changing that file.
     */
    @ParameterizedTest(name = "{0} is benign only if the ingestion DECLARED the skip")
    @MethodSource("everyReason")
    void a_skip_is_benign_exactly_when_it_was_declared_rather_than_lost(SkipReason r) {
        boolean declared = r instanceof SkipReason.Duplicate || r instanceof SkipReason.PolicyExcluded;
        assertThat(r.benign())
            .as("%s: a declared skip is not a loss; a loss is not declared", r.persistedName())
            .isEqualTo(declared);
    }
}
