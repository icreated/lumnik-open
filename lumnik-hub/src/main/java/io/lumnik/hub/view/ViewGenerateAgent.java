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

import java.util.ArrayList;
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
        - Every name above carries SQL double-quotes, because that is how SQL spells a name. Drop those
          SQL quotes from the name itself; each value is still an ordinary JSON string. So the card's
          "connector"."v_customer" and "Partner Name" are written here as
          "table": "connector.v_customer" and "columns": ["Partner Name"].
        - "columns": ONLY names listed above, all from ONE table, ordered for reading (identifying column first).
        - "table": the unquoted schema.table those columns belong to, exactly as listed apart from the quotes.
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
            return tryOnce(card, scope, description, raw);
        } catch (IllegalArgumentException first) {
            LOG.infof("view generation rejected, retrying once: %s", first.getMessage());
            // Bounded echo: the retry prompt must not amplify a runaway answer (the full raw
            // still rides on the FAILED outcome).
            String raw2 = orEmpty(chat.chat(prompt + RETRY_SUFFIX.formatted(first.getMessage(), clip(raw))));
            try {
                return tryOnce(card, scope, description, raw2);
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
    private Outcome tryOnce(SchemaCard card, String scope, String description, String raw) {
        ViewSuggestion s = ViewSuggestion.parse(raw);
        if (s.abstain()) {
            return Outcome.abstained(s.reason() != null && !s.reason().isBlank()
                    ? s.reason() : "the scope cannot serve this description");
        }
        if (s.columns() == null || s.columns().isEmpty())
            throw new IllegalArgumentException("\"columns\" must select at least one column");

        String name = Identifiers.slug(
            s.title() != null && !s.title().isBlank() ? s.title() : scope, "view");
        // The TABLE first, from the resolver's own lookup: which columns this view ends up
        // declaring depends on the filters, and whether a filter is addable depends on the table.
        SchemaCard.Table table = resolver.findTable(
            new ViewManifest(name, scope, s.table(), s.title(), s.columns()), card);
        List<String> filters = s.filters() == null ? List.of() : s.filters();
        // Jackson yields [null, …] from "filters":[null] — a gate rejection, never an NPE→500.
        if (filters.stream().anyMatch(f -> f == null || f.isBlank()))
            throw new IllegalArgumentException("\"filters\" must not contain null or blank entries");
        // The model spells a value the way SQL does ('GOLD'); the grammar's value is a bare literal.
        // Shed the quotes ONCE, here — before the gate, which refused them on a domain column, and
        // before assembly, where on a domainless column they became a filter matching nothing.
        filters = filters.stream().map(ViewFilter::withUnquotedValue).toList();

        List<String> columns = withFilteredColumns(s.columns(), filters, table);
        // Resolved ONCE, on the final column list — which is also what validates every column.
        ResolvedView rv = resolver.resolve(
            new ViewManifest(name, scope, s.table(), s.title(), columns), card);
        ViewFilter.resolveAll(filters, rv, table);

        // Emit the RESOLVED table FQN always — the generated manifest is self-contained.
        return Outcome.ok(assembler.assemble(
            new ViewManifest(name, scope, rv.table(), s.title(), columns), filters, description));
    }

    /**
     * The model's selection, plus any column its own filters act on that it forgot to select.
     *
     * <p>{@code ViewFilter} refuses a filter on a column the view does not show, because a table
     * whose rows are narrowed by an invisible column cannot be read honestly. Measured 3/3 on
     * 2026-09-13, that refusal is where "les clients gold de Lyon" died: the model selected the
     * name and the city, filtered on the segment, and the one corrective retry carried the gate's
     * own sentence back without acting on it (#505).
     *
     * <p>The rule is right, so it is SATISFIED rather than weakened — the human asked for gold
     * customers, so the segment belongs in their view, and showing it is precisely what makes the
     * narrowed table readable. Appended last, so the model's reading order (identifying column
     * first) survives. Bounded: only a column the card's table really has can be added; an
     * invented one keeps the honest refusal, or the rescue would manufacture a column.
     */
    private static List<String> withFilteredColumns(List<String> columns, List<String> filters,
                                                    SchemaCard.Table table) {
        // columns is non-null by the time this runs: tryOnce refuses a null-or-empty selection
        // before it reaches the table lookup, and a test pins that refusal. Said here because the
        // sibling `filters` IS normalised in place, which makes the asymmetry look like an
        // oversight rather than a guard that already fired (raised on #510).
        List<String> out = new ArrayList<>(columns);
        for (String raw : filters) {
            // A malformed filter throws here instead of two lines later, in the same gate pass.
            String col = ViewFilter.parse(raw).column();
            if (!out.contains(col) && table.columns().stream().anyMatch(c -> c.name().equals(col))) {
                out.add(col);
            }
        }
        return out;
    }
}
