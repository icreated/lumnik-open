package io.lumnik.hub;

import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatMessage;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * CDI mock for the {@link Analytic} ChatPort (AskToSqlAgent's port) — it SHADOWS the real
 * AnalyticChatPort (from lumnik-llm-langchain) so the ask ITs boot without Ollama and replace it via
 * {@code @InjectMock @Analytic ChatPort}. Gated by {@code lumnik.judgeeval.mock-analytic} (active by
 * default / when missing); {@code JudgeEvalIT}'s profile sets it false to use the REAL qwen-backed port.
 */
@Mock
@ApplicationScoped
@Analytic
@IfBuildProperty(name = "lumnik.judgeeval.mock-analytic", stringValue = "true", enableIfMissing = true)
public class MockAnalyticChatPort implements ChatPort {

    @Override
    public String name() { return "mock-analytic"; }

    @Override
    public String chat(List<ChatMessage> messages) {
        return "SELECT 1";
    }
}
