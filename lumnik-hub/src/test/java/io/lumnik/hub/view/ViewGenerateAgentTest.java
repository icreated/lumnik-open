package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AbstentionGuard;
import io.lumnik.llm.spi.ChatMessage;
import io.lumnik.llm.spi.ChatPort;
import jakarta.enterprise.inject.Instance;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ViewGenerateAgentTest {

    /** Scripted fake: returns the queued answers in order and records every prompt it saw. */
    static final class FakeChat implements ChatPort {
        final List<String> prompts = new ArrayList<>();
        private final List<String> answers;
        FakeChat(String... answers) { this.answers = new ArrayList<>(Arrays.asList(answers)); }
        @Override public String name() { return "fake"; }
        @Override public String chat(List<ChatMessage> messages) { throw new UnsupportedOperationException(); }
        @Override public String chat(String prompt) {
            prompts.add(prompt);
            return answers.remove(0);
        }
    }

    private static SchemaCard card() {
        return new SchemaCard("clients", List.of(
            new SchemaCard.Table("connector.v_customer", "Clients", List.of(
                new SchemaCard.Column("customer_code", "text", "Code client"),
                new SchemaCard.Column("name", "text", "Nom"),
                new SchemaCard.Column("city", "text", "Ville"),
                new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                        List.of("GOLD", "SILVER", "BRONZE"))))));
    }

    private static ViewGenerateAgent agent() {
        ViewGenerateAgent a = new ViewGenerateAgent();
        a.abstentionGuard = new AbstentionGuard();
        return a;
    }

    private static final String GOOD =
        "{\"table\":\"connector.v_customer\",\"title\":\"Clients gold de Lyon\","
        + "\"columns\":[\"customer_code\",\"name\",\"city\",\"segment\"],"
        + "\"filters\":[\"segment=GOLD\",\"city~Lyon\"]}";

    @Test
    void without_a_chat_port_the_public_entry_abstains_instead_of_raising() {
        // The seam doctrine: an absent extension degrades, never explodes. This endpoint was
        // the one exception the whole doc set had to carry ('nothing half-works, with today
        // one named exception') — on the open edition, chatPorts.get() raised instead of
        // refusing honestly (audit P11, #286).
        ViewGenerateAgent a = agent();
        @SuppressWarnings("unchecked")
        Instance<ChatPort> none =
            Mockito.mock(Instance.class);
        Mockito.when(none.isUnsatisfied()).thenReturn(true);
        a.chatPorts = none;

        ViewGenerateAgent.Outcome out = a.generate(card(), "clients", "les clients gold");

        Assertions.assertThat(out.status())
            .isEqualTo(ViewGenerateAgent.Outcome.Status.ABSTAINED);
        Assertions.assertThat(out.message()).contains("edition");
    }

    @Test
    void happyPath_selectionsBecomeAValidatedManifestWithTheSuggestedCommand() {
        FakeChat chat = new FakeChat(GOOD);
        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertTrue(out.yaml().startsWith(
            "# lm view -f clients_gold_de_lyon.yaml --filter 'segment=GOLD' --filter 'city~Lyon'"));
        assertTrue(out.yaml().contains("table: \"connector.v_customer\"")
                || out.yaml().contains("table: connector.v_customer"));
        // The prompt grounds the model in the card and the description.
        assertTrue(chat.prompts.get(0).contains("connector.v_customer"));
        assertTrue(chat.prompts.get(0).contains("les clients gold de Lyon"));
    }

    @Test
    void nearMissColumn_isRetriedOnceWithTheGateErrorInjected_thenSucceeds() {
        String bad = GOOD.replace("\"name\"", "\"customer_name\"");
        FakeChat chat = new FakeChat(bad, GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertEquals(2, chat.prompts.size());
        // The retry prompt carries the resolver's EXACT rejection and the previous answer.
        assertTrue(chat.prompts.get(1).contains("customer_name"));
        assertTrue(chat.prompts.get(1).contains("rejected"));
    }

    @Test
    void doubleFailure_isAnHonestRefusalCarryingTheRawOutput() {
        String bad = GOOD.replace("\"name\"", "\"customer_name\"");
        FakeChat chat = new FakeChat(bad, bad);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.FAILED, out.status());
        assertTrue(out.message().contains("customer_name"));
        assertEquals(bad, out.raw());
    }

    @Test
    void modelAbstention_isHonored() {
        FakeChat chat = new FakeChat("{\"abstain\":true,\"reason\":\"no order data here\"}");
        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les commandes de mars", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.ABSTAINED, out.status());
        assertEquals("no order data here", out.message());
        assertEquals(1, chat.prompts.size());
    }

    @Test
    void emptyCard_abstainsBeforeAnyModelCall() {
        FakeChat chat = new FakeChat();
        ViewGenerateAgent.Outcome out =
            agent().generate(new SchemaCard("clients", List.of()), "clients", "les clients", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.ABSTAINED, out.status());
        assertEquals(0, chat.prompts.size());
    }

    @Test
    void outOfDomainFilter_goesThroughTheRetry() {
        String bad = GOOD.replace("segment=GOLD", "segment=PLATINUM");
        FakeChat chat = new FakeChat(bad, GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients platinum", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertTrue(chat.prompts.get(1).contains("PLATINUM"));
    }

    @Test
    void lineBreakInAFilterValue_goesThroughTheRetry() {
        // JSON \n inside the value: passes ViewFilter (~ accepts any text) but would split the
        // suggested-command comment — the assembler refuses, the refusal feeds the retry.
        String bad = GOOD.replace("city~Lyon", "city~Ly\\non");
        FakeChat chat = new FakeChat(bad, GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertEquals(2, chat.prompts.size());
        assertTrue(chat.prompts.get(1).contains("line break"));
    }

    @Test
    void aNullAnswerFromThePort_goesThroughTheRetryNotAnNpe() {
        // The ChatPort SPI does not guarantee a non-null reply — a null first answer must be
        // a recoverable rejection (retry), never an NPE→500.
        FakeChat chat = new FakeChat(null, GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertEquals(2, chat.prompts.size());
    }

    @Test
    void aHugeGarbageAnswer_isTruncatedInTheRetryPrompt() {
        // The full raw already rides on the FAILED outcome — the retry prompt must stay bounded
        // (token limits / resource amplification).
        FakeChat chat = new FakeChat("x".repeat(50_000), GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertTrue(chat.prompts.get(1).length() < chat.prompts.get(0).length() + 5_000,
            "retry prompt was " + chat.prompts.get(1).length() + " chars");
    }

    @Test
    void aNullFilterEntry_goesThroughTheRetryNotAnNpe() {
        // Jackson happily yields [null, …] from "filters":[null, …] — that must be a gate
        // rejection feeding the retry, never a NullPointerException escaping as a 500.
        String bad = GOOD.replace("\"filters\":[\"segment=GOLD\",\"city~Lyon\"]",
                                  "\"filters\":[null,\"segment=GOLD\"]");
        FakeChat chat = new FakeChat(bad, GOOD);

        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertEquals(2, chat.prompts.size());
        assertTrue(chat.prompts.get(1).contains("null or blank"));
    }

    @Test
    void unparseableOutput_goesThroughTheRetry() {
        FakeChat chat = new FakeChat("Sure! Here is your view.", GOOD);
        ViewGenerateAgent.Outcome out =
            agent().generate(card(), "clients", "les clients gold de Lyon", chat);
        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertEquals(2, chat.prompts.size());
    }

    @Test
    void multiTableScope_theResolvedTableIsAlwaysEmitted() {
        SchemaCard two = new SchemaCard("demo", List.of(
            new SchemaCard.Table("connector.t_a", "A", List.of(
                new SchemaCard.Column("id", "text", "Id"))),
            new SchemaCard.Table("connector.t_b", "B", List.of(
                new SchemaCard.Column("code", "text", "Code")))));
        FakeChat chat = new FakeChat(
            "{\"table\":\"connector.t_b\",\"title\":\"B desk\",\"columns\":[\"code\"],\"filters\":[]}");

        ViewGenerateAgent.Outcome out = agent().generate(two, "demo", "the B desk", chat);

        assertEquals(ViewGenerateAgent.Outcome.Status.OK, out.status());
        assertTrue(out.yaml().contains("connector.t_b"));
    }

    @Test
    void emptyColumns_isAGateFailureNotACrash() {
        String noCols = "{\"table\":\"connector.v_customer\",\"title\":\"Desk\",\"columns\":[]}";
        FakeChat chat = new FakeChat(noCols, noCols);
        ViewGenerateAgent.Outcome out = agent().generate(card(), "clients", "les clients", chat);
        assertEquals(ViewGenerateAgent.Outcome.Status.FAILED, out.status());
        assertTrue(out.message().contains("columns"));
    }
}
