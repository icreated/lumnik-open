package io.lumnik.llm.spi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPortDefaultTest {

    @Test void chatString_wrapsAsUserMessage() {
        ChatPort fake = new ChatPort() {
            @Override public String name() { return "fake"; }
            @Override public String chat(List<ChatMessage> messages) {
                assertThat(messages).hasSize(1);
                assertThat(messages.get(0).role()).isEqualTo(Role.USER);
                assertThat(messages.get(0).content()).isEqualTo("hello");
                return "ok";
            }
        };
        assertThat(fake.chat("hello")).isEqualTo("ok");
    }
}
