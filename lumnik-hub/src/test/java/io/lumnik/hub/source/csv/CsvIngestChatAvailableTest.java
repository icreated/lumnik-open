package io.lumnik.hub.source.csv;

import io.lumnik.llm.spi.ChatPort;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code chatAvailable} is what {@code lm csv ingest} reads before it promises "askable … try: lm ask".
 * The promise is about a chat model, not about a corpus sink: with {@code lumnik.ai.enabled=false}
 * the sink is still resolvable (it keeps the corpus consistent) and the chat is not.
 */
class CsvIngestChatAvailableTest {

    private static CsvIngestService withAnalyticChat(boolean resolvable) {
        var svc = new CsvIngestService();
        @SuppressWarnings("unchecked")
        Instance<ChatPort> chat = mock(Instance.class);
        when(chat.isResolvable()).thenReturn(resolvable);
        svc.analyticChat = chat;
        return svc;
    }

    @Test
    void the_chat_is_available_when_an_analytic_model_resolves() {
        assertThat(withAnalyticChat(true).chatAvailable()).isTrue();
    }

    @Test
    void the_chat_is_not_available_without_one() {
        assertThat(withAnalyticChat(false).chatAvailable()).isFalse();
    }
}
