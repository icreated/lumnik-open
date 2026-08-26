package io.lumnik.testkit.llm;

import io.lumnik.llm.spi.ChatMessage;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * CDI mock bean for ChatPort. Satisfies ARC injection point validation in @QuarkusTest — the real
 * impl lives in lumnik-llm-langchain and is not on the test classpath of the modules that consume
 * this testkit (lumnik-hub, lumnik-rag, where RagChatResource injects an {@code Instance<ChatPort>}).
 *
 * <p>The canned reply is deliberately inert: tests that care about what the model says stub the
 * port with {@code @InjectMock}. Nothing asserts this string.
 */
@Mock
@ApplicationScoped
public class MockChatPort implements ChatPort {

    @Override
    public String name() { return "mock"; }

    @Override
    public String chat(List<ChatMessage> messages) {
        return "mock answer";
    }
}
