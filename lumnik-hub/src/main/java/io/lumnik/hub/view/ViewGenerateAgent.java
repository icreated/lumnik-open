package io.lumnik.hub.view;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.AbstentionGuard;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Orchestrates NL description -> ViewSuggestion (the model SELECTS) -> the existing gate
 * (ViewResolver + ViewFilter — nothing new validates) -> deterministic YAML. Mirror of
 * AskToSqlAgent at the view layer. One corrective retry carrying the gate's exact rejection,
 * then an honest refusal with the raw output. The abstention ethos holds at both ends: an
 * empty card abstains before any model call, and the model's own {"abstain":true} is honored.
 */
@ApplicationScoped
public class ViewGenerateAgent {

    private static final Logger LOG = Logger.getLogger(ViewGenerateAgent.class);

    /** One generation outcome: yaml when OK; message (+ raw model output when FAILED) otherwise. */
    public record Outcome(Status status, String yaml, String message, String raw) {
        public enum Status { OK, ABSTAINED, FAILED }
        static Outcome ok(String yaml) { return new Outcome(Status.OK, yaml, null, null); }
        static Outcome abstained(String reason) { return new Outcome(Status.ABSTAINED, null, reason, null); }
        static Outcome failed(String error, String raw) { return new Outcome(Status.FAILED, null, error, raw); }
    }

    static final String PROMPT = """
        You are a careful assistant designing ONE read-only list view for a business user.
        Use ONLY these tables and columns (the text after -- is the business meaning):

        %s
        Answer with ONE JSON object and nothing else:
        {"table": "<schema.table>", "title": "<short title in the description's language>", "columns": ["col1", "col2"], "filters": []}

        Rules:
        - "columns": ONLY names listed above, all from ONE table, ordered for reading (identifying column first).
        - "table": the exact table name those columns belong to.
        - "filters": ONLY when the description explicitly restricts the rows. Forms: col=value (exact),
          col~text (contains), col>v col<v col>=v col<=v (number or ISO date). Filter only on columns
          you put in "columns". For a column listing "allowed:" values, a col=value filter must use one of them.
        - If the description asks for data these tables cannot show, answer exactly
          {"abstain": true, "reason": "<one short sentence>"} — do not guess.

        Description: %s
        """;

    static final String RETRY_SUFFIX = """

        Your previous answer was rejected: %s
        Your previous answer was: %s
        Answer again with ONE corrected JSON object and nothing else.
        """;

    @Inject @Analytic Instance<ChatPort> chatPorts;
    @Inject AbstentionGuard abstentionGuard;

    private final ViewResolver resolver = new ViewResolver();
    private final ViewYamlAssembler assembler = new ViewYamlAssembler();

    public Outcome generate(SchemaCard card, String scope, String description) {
        // The seam doctrine: an absent extension degrades, never explodes. Without this guard
        // the open edition raised UnsatisfiedResolutionException here — the one endpoint that
        // half-worked (audit P11). Abstention is the honest refusal the surface already renders.
        if (chatPorts.isUnsatisfied()) {
            return Outcome.abstained(
                "no chat model is wired in this edition — view generation needs the closed extensions");
        }
        return generate(card, scope, description, chatPorts.get());
    }

    /** Package-visible core taking the port explicitly — unit-testable without CDI. */
    Outcome generate(SchemaCard card, String scope, String description, ChatPort chat) {
        if (abstentionGuard.hasNoSchema(card)) {
            return Outcome.abstained(AbstentionGuard.NO_SCHEMA_MESSAGE);
        }
        String prompt = PROMPT.formatted(card.toPrompt(), description);
        // The SPI does not guarantee a non-null reply — normalize at the seam so a null
        // answer is a recoverable parse rejection (retry), never an NPE.
        String raw = orEmpty(chat.chat(prompt));
        try {
            return tryOnce(card, scope, raw);
        } catch (IllegalArgumentException first) {
            LOG.infof("view generation rejected, retrying once: %s", first.getMessage());
            // Bounded echo: the retry prompt must not amplify a runaway answer (the full raw
            // still rides on the FAILED outcome).
            String raw2 = orEmpty(chat.chat(prompt + RETRY_SUFFIX.formatted(first.getMessage(), clip(raw))));
            try {
                return tryOnce(card, scope, raw2);
            } catch (IllegalArgumentException second) {
                return Outcome.failed(second.getMessage(), raw2);
            }
        }
    }

    private static String clip(String s) {
        return s.length() <= 2_000 ? s : s.substring(0, 2_000) + "…";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** One pass through the existing gate; throws IllegalArgumentException on any rejection. */
    private Outcome tryOnce(SchemaCard card, String scope, String raw) {
        ViewSuggestion s = ViewSuggestion.parse(raw);
        if (s.abstain()) {
            return Outcome.abstained(s.reason() != null && !s.reason().isBlank()
                    ? s.reason() : "the scope cannot serve this description");
        }
        if (s.columns() == null || s.columns().isEmpty())
            throw new IllegalArgumentException("\"columns\" must select at least one column");

        String name = Identifiers.slug(
            s.title() != null && !s.title().isBlank() ? s.title() : scope, "view");
        ResolvedView rv = resolver.resolve(
            new ViewManifest(name, scope, s.table(), s.title(), s.columns()), card);
        SchemaCard.Table table = card.tables().stream()
            .filter(t -> t.name().equals(rv.table())).findFirst().orElseThrow();
        List<String> filters = s.filters() == null ? List.of() : s.filters();
        // Jackson yields [null, …] from "filters":[null] — a gate rejection, never an NPE→500.
        if (filters.stream().anyMatch(f -> f == null || f.isBlank()))
            throw new IllegalArgumentException("\"filters\" must not contain null or blank entries");
        ViewFilter.resolveAll(filters, rv, table);

        // Emit the RESOLVED table FQN always — the generated manifest is self-contained.
        return Outcome.ok(assembler.assemble(
            new ViewManifest(name, scope, rv.table(), s.title(), s.columns()), filters));
    }
}
