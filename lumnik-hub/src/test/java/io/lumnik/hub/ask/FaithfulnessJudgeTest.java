package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.llm.spi.ChatPort;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FaithfulnessJudgeTest {

  private final ChatPort judgePort = Mockito.mock(ChatPort.class);
  private final FaithfulnessJudge judge = judgeWith(judgePort);

  /** A judge wired to {@code port}, or to no model at all when {@code port} is null. */
  private static FaithfulnessJudge judgeWith(ChatPort port) {
    @SuppressWarnings("unchecked")
    Instance<ChatPort> judges = Mockito.mock(Instance.class);
    Mockito.when(judges.isUnsatisfied()).thenReturn(port == null);
    if (port != null) Mockito.when(judges.get()).thenReturn(port);
    return new FaithfulnessJudge(judges);
  }

  private static SchemaCard card() {
    return new SchemaCard("clients", List.of(new SchemaCard.Table("connector.v_customer", "customer",
        List.of(new SchemaCard.Column("city", "text", "City")))));
  }

  @Test
  void parses_faithful() {
    Mockito.when(judgePort.chat(Mockito.anyString())).thenReturn("FAITHFUL");
    assertThat(judge.assess("how many in Lyon?",
        "SELECT count(*) FROM connector.v_customer WHERE city = 'Lyon'", card()).verdict())
        .isEqualTo(FaithfulnessJudge.Verdict.FAITHFUL);
  }

  @Test
  void parses_unfaithful_with_reason() {
    Mockito.when(judgePort.chat(Mockito.anyString()))
        .thenReturn("UNFAITHFUL: counts all rows, ignores the city filter");
    var r = judge.assess("how many in Lyon?", "SELECT count(*) FROM connector.v_customer", card());
    assertThat(r.verdict()).isEqualTo(FaithfulnessJudge.Verdict.UNFAITHFUL);
    assertThat(r.reason()).contains("ignores the city filter");
  }

  @Test
  void unfaithful_reason_is_taken_after_the_verdict_token_not_an_earlier_colon() {
    // A chatty judge may prefix the line ("Verdict: UNFAITHFUL: ..."); the reason must not swallow the token.
    Mockito.when(judgePort.chat(Mockito.anyString())).thenReturn("Verdict: UNFAITHFUL: ignores the city filter");
    var r = judge.assess("how many in Lyon?", "SELECT count(*) FROM connector.v_customer", card());
    assertThat(r.verdict()).isEqualTo(FaithfulnessJudge.Verdict.UNFAITHFUL);
    assertThat(r.reason()).isEqualTo("ignores the city filter");
  }

  @Test
  void a_later_line_mention_does_not_flip_the_first_line_verdict() {
    // The verdict is on the FIRST line; the word "unfaithful" in a later-line explanation must not flip it.
    Mockito.when(judgePort.chat(Mockito.anyString()))
        .thenReturn("FAITHFUL\nNote: it would be UNFAITHFUL if the city filter were dropped.");
    assertThat(judge.assess("how many in Lyon?",
        "SELECT count(*) FROM connector.v_customer WHERE city = 'Lyon'", card()).verdict())
        .isEqualTo(FaithfulnessJudge.Verdict.FAITHFUL);
  }

  @Test
  void parses_an_explicit_inconclusive_verdict_with_its_reason() {
    // The judge may abstain (rubric allows INCONCLUSIVE); the token is the verdict, not the reason.
    Mockito.when(judgePort.chat(Mockito.anyString()))
        .thenReturn("INCONCLUSIVE: the question is too ambiguous to judge");
    var r = judge.assess("tell me about it", "SELECT 1", card());
    assertThat(r.verdict()).isEqualTo(FaithfulnessJudge.Verdict.INCONCLUSIVE);
    assertThat(r.reason()).isEqualTo("the question is too ambiguous to judge");
  }

  @Test
  void garbage_or_blank_or_crash_is_inconclusive() {
    Mockito.when(judgePort.chat(Mockito.anyString())).thenReturn("hmm, maybe, not sure");
    assertThat(judge.assess("q", "SELECT 1", card()).verdict()).isEqualTo(FaithfulnessJudge.Verdict.INCONCLUSIVE);
    Mockito.when(judgePort.chat(Mockito.anyString())).thenReturn("   ");
    assertThat(judge.assess("q", "SELECT 1", card()).verdict()).isEqualTo(FaithfulnessJudge.Verdict.INCONCLUSIVE);
    Mockito.when(judgePort.chat(Mockito.anyString())).thenThrow(new RuntimeException("judge down"));
    assertThat(judge.assess("q", "SELECT 1", card()).verdict()).isEqualTo(FaithfulnessJudge.Verdict.INCONCLUSIVE);
  }

  // The open edition ships without lumnik-llm-langchain, so no @Judge ChatPort exists at all. This
  // class already abstains when the judge crashes or answers noise; abstaining when there IS no
  // judge is the same doctrine applied one step earlier — and it is what lets the hub START without
  // the closed modules (publication ritual, Act 1 seam 3).
  @Test
  void no_judge_model_on_the_classpath_is_inconclusive_not_a_crash() {
    var r = judgeWith(null).assess("how many in Lyon?", "SELECT 1", card());
    assertThat(r.verdict()).isEqualTo(FaithfulnessJudge.Verdict.INCONCLUSIVE);
    assertThat(r.reason()).contains("no judge model");
  }

  @Test
  void the_rubric_includes_question_sql_and_schema() {
    Mockito.when(judgePort.chat(Mockito.anyString())).thenReturn("FAITHFUL");
    judge.assess("how many in Lyon?", "SELECT count(*) FROM connector.v_customer", card());
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    Mockito.verify(judgePort).chat(prompt.capture());
    assertThat(prompt.getValue())
        .contains("how many in Lyon?")
        .contains("SELECT count(*) FROM connector.v_customer")
        // The CARD is included, and this must be a line only the card can produce: `v_customer` alone
        // is already inside the SQL asserted above, so it could not have failed on its own.
        .contains("TABLE \"connector\".\"v_customer\"")
        .containsIgnoringCase("LIMIT") // tells the judge the auto-injected safety LIMIT is not an unfaithfulness
        .containsIgnoringCase("LEFT JOIN"); // the JOIN-TYPE hint: "each X" + INNER drops unmatched X = unfaithful
  }
}
