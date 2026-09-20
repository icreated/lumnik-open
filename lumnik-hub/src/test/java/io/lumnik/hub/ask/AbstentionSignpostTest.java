package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The abstention stays the answer; it just stops being the end of the road.
 *
 * <p>Two questions abstain for the same structural reason — the card advertises canonical columns
 * and nothing else — and for both, a door one step away CAN answer:
 *
 * <ul>
 *   <li>« pourquoi je ne peux pas clôturer la commande ? » — the declared lifecycle lives in
 *       {@code rag.chunk}, which text-to-SQL never reads (#494).</li>
 *   <li>« où l'ERP et le CRM se contredisent-ils ? » — the fusion ledger is a jsonb {@code _conflicts}
 *       column the card does not advertise, so Ask cannot see it (#495).</li>
 * </ul>
 *
 * <p>Arbitrated 2026-09-13: the abstention is the honest answer in both cases and stays. Making the
 * analytic door read lifecycle chunks would have the SQL generator reasoning over non-SQL text, and
 * advertising a jsonb column on a card that types everything {@code text} would have the model
 * writing {@code _conflicts->>…} against a column declared as text — the confident-wrong the five
 * guards exist to prevent, in both directions. What was missing was not an answer. It was knowing
 * that one exists — which is why the signposts state the FACT and name no client's gesture.
 *
 * <p><b>The frozen sentence stays the prefix.</b> {@code AbstentionOtkIT} graded abstentions with
 * {@code CANNOT_ANSWER_MESSAGE.equals(answer)}, so appending anything would have made every correct
 * abstention read as a non-abstention to the oracle. The equality moves to {@link
 * AbstentionGuard#isCannotAnswer} — the question asked of the class that owns the words, not of a
 * literal copied into a grader.
 */
class AbstentionSignpostTest {

    private final AbstentionGuard guard = new AbstentionGuard();

    private static SchemaCard cardOf(String... tables) {
        return new SchemaCard("magasin", Stream.of(tables)
            .map(t -> new SchemaCard.Table(t, t, List.of(new SchemaCard.Column("id", "text", "id"))))
            .toList());
    }

    @Test
    void a_scope_with_nothing_else_to_offer_gets_the_sentence_unchanged() {
        String answer = guard.cannotAnswer(false, guard.hasAFusedEntity(cardOf("connector.t_orders"))).answer();

        assertThat(answer)
            .as("no lifecycle, no fused entity: there is nowhere to point, so nothing is added")
            .isEqualTo(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
    }

    @Test
    void a_declared_lifecycle_is_named_as_something_the_scope_holds() {
        String answer = guard.cannotAnswer(true, guard.hasAFusedEntity(cardOf("connector.t_orders"))).answer();

        assertThat(answer).startsWith(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
        assertThat(answer).endsWith(AbstentionGuard.LIFECYCLE_SIGNPOST);
    }

    @Test
    void a_fused_entity_points_at_the_conflicts_ledger() {
        String answer = guard.cannotAnswer(false, guard.hasAFusedEntity(cardOf("connector.t_orders", "connector.v_customer"))).answer();

        assertThat(answer).startsWith(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
        assertThat(answer).endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
    }

    @Test
    void both_conditions_give_both_signposts() {
        String answer = guard.cannotAnswer(true, guard.hasAFusedEntity(cardOf("connector.v_customer"))).answer();

        assertThat(answer).startsWith(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
        assertThat(answer)
            .contains(AbstentionGuard.LIFECYCLE_SIGNPOST)
            .endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
    }

    @Test
    void a_raw_table_named_like_a_view_is_not_a_fused_entity() {
        // The fused-entity signal is the connector.v_ prefix EntityViewCompiler writes, not the
        // letter v: a connector table called t_v_customer fuses nothing and has no ledger. Telling
        // a métier a conflicts ledger exists for a table that has none is a signpost to a dead end
        // — worse than no signpost.
        String answer = guard.cannotAnswer(false, guard.hasAFusedEntity(cardOf("connector.t_v_customer"))).answer();

        assertThat(answer).isEqualTo(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
    }

    /**
     * A signpost and its token are one decision, and this is what says so.
     *
     * <p>The prose tells every client the answer exists somewhere else; the token lets the clients
     * that have a gesture render it (#515). They are appended in the same branch of
     * {@code cannotAnswer} precisely so they cannot drift — but a future third signpost could be
     * added to the sentence and forgotten in the tokens, and nothing else in this file would go
     * red: the sentence would still be correct, and a client would simply stop being told. So the
     * count is compared, over every combination, against the {@code *_SIGNPOST} constants read off
     * the guard and the vocabulary {@link AskResult#DOORS} declares — never a list kept here.
     */
    @Test
    void everySignpostTheSentenceCarriesIsAlsoAToken() throws Exception {
        List<String> signposts = new ArrayList<>();
        for (Field f : AbstentionGuard.class.getDeclaredFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.getName().endsWith("_SIGNPOST")) signposts.add((String) f.get(null));
        }
        List<String> doors = AskResult.DOORS;

        assertThat(signposts).as("the sweep must find the constants it polices").isNotEmpty();
        assertThat(doors).as("one token per signpost, in both directions").hasSameSizeAs(signposts);

        for (boolean lifecycle : new boolean[] {false, true}) {
            for (boolean fused : new boolean[] {false, true}) {
                AbstentionGuard.Abstention a = guard.cannotAnswer(lifecycle, fused);
                long appended = signposts.stream().filter(sp -> a.answer().contains(sp)).count();
                assertThat(a.otherDoors())
                    .as("lifecycle=%s fused=%s: a client is told in prose about %d door(s) "
                        + "and given %d token(s)", lifecycle, fused, appended, a.otherDoors().size())
                    .hasSize((int) appended);
                assertThat(doors).containsAll(a.otherDoors());
            }
        }
    }

    @Test
    void signpost_composes_onto_any_dead_end_sentence_not_only_cannot_answer() {
        // SqlGuard rejection and ZeroRowGuard.CAVEAT are just as structurally true as NO_ANSWER
        // (#527) — signpost() is the mechanism cannotAnswer() itself is built from.
        String base = "I couldn't translate that into a safe query.";

        AbstentionGuard.Abstention a =
            guard.signpost(base, true, guard.hasAFusedEntity(cardOf("connector.v_customer")));

        assertThat(a.answer()).startsWith(base);
        assertThat(a.answer())
            .contains(AbstentionGuard.LIFECYCLE_SIGNPOST)
            .endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(a.otherDoors()).containsExactly(AskResult.DOOR_LIFECYCLE, AskResult.DOOR_CONFLICTS);
    }

    @Test
    void isCannotAnswer_recognises_the_abstention_with_and_without_its_signposts() {
        assertThat(guard.isCannotAnswer(AbstentionGuard.CANNOT_ANSWER_MESSAGE)).isTrue();
        assertThat(guard.isCannotAnswer(guard.cannotAnswer(true, guard.hasAFusedEntity(cardOf("connector.v_customer"))).answer()))
            .as("a signposted abstention is still an abstention — the OTK oracle grades on this")
            .isTrue();
    }

    @Test
    void isCannotAnswer_is_not_true_of_the_other_answers() {
        assertThat(guard.isCannotAnswer(AbstentionGuard.NO_SCHEMA_MESSAGE))
            .as("the empty-schema floor is a different refusal with different words")
            .isFalse();
        assertThat(guard.isCannotAnswer("There are 412 orders.")).isFalse();
        assertThat(guard.isCannotAnswer(null)).isFalse();
    }

    @Test
    void no_signpost_names_a_client_specific_gesture() {
        // The hub serves this prose verbatim to every client. The PWA renders `answer` as PLAIN
        // TEXT in its amber refusal bubble and posts mode:'analytic' with no toggle, so a métier on
        // a phone has neither a terminal for an `lm` command nor a semantic door to be sent to.
        // The signposts state the FACT; each client renders its own gesture for it.
        for (String signpost : List.of(AbstentionGuard.LIFECYCLE_SIGNPOST,
                                       AbstentionGuard.CONFLICTS_SIGNPOST)) {
            assertThat(signpost)
                .as("a CLI command in a payload the PWA prints as text is a dead end on a phone")
                .doesNotContain("lm ").doesNotContain("`");
        }
    }
}
