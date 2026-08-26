package io.lumnik.extraction.spi.ask;

/**
 * Boundary the chat resource depends on for analytic (text-to-SQL) questions.
 * The implementation (AskToSqlAgent) lives in lumnik-hub because it needs
 * named datasources. Injected via Instance&lt;AskPort&gt; so rag builds/tests without it.
 */
public interface AskPort {
    AskResult ask(long tenantId, String scope, String question);
}
