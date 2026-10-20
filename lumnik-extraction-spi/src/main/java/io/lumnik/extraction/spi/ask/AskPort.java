package io.lumnik.extraction.spi.ask;

/**
 * Boundary the chat resource depends on for analytic (text-to-SQL) questions.
 * The implementation (AskToSqlAgent) lives in lumnik-hub because it needs
 * named datasources. Injected via Instance&lt;AskPort&gt; so rag builds/tests without it.
 */
public interface AskPort {

    /**
     * The longest {@code previous} a door accepts (#1096) — and so the longest question an implementation may return
     * as {@link AskResult#understoodAs()}, which a surface sends back as the next {@code previous}.
     */
    int MAX_PREVIOUS_CHARS = 1000;

    AskResult ask(long tenantId, String scope, String question);

    /**
     * The same ask, with the answer language the calling surface names ({@code "fr"}), or
     * {@code null} to let the question's own language decide (#879). An implementation that does
     * not phrase answers itself may ignore it.
     */
    default AskResult ask(long tenantId, String scope, String question, String lang) {
        return ask(tenantId, scope, question);
    }

    /**
     * The same ask, continuing {@code previous}: the question the last answer was given for, as the surface keeps it
     * (#1096). Null or blank is a standalone question. An implementation that resolves the follow-up answers the
     * resolved question and returns it as {@link AskResult#understoodAs()}; one that does not answers
     * {@code question} alone and returns none.
     */
    default AskResult ask(long tenantId, String scope, String question, String lang, String previous) {
        return ask(tenantId, scope, question, lang);
    }
}
