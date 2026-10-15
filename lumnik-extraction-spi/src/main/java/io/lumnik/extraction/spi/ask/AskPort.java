package io.lumnik.extraction.spi.ask;

/**
 * Boundary the chat resource depends on for analytic (text-to-SQL) questions.
 * The implementation (AskToSqlAgent) lives in lumnik-hub because it needs
 * named datasources. Injected via Instance&lt;AskPort&gt; so rag builds/tests without it.
 */
public interface AskPort {
    AskResult ask(long tenantId, String scope, String question);

    /**
     * The same ask, with the answer language the calling surface names ({@code "fr"}), or
     * {@code null} to let the question's own language decide (#879). An implementation that does
     * not phrase answers itself may ignore it.
     */
    default AskResult ask(long tenantId, String scope, String question, String lang) {
        return ask(tenantId, scope, question);
    }
}
