package io.lumnik.llm.spi;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageTest {

    @Test void nullRole_throws() {
        assertThatThrownBy(() -> new ChatMessage(null, "hi"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nullContent_throws() {
        assertThatThrownBy(() -> new ChatMessage(Role.USER, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validMessage_constructed() {
        ChatMessage m = new ChatMessage(Role.SYSTEM, "you are helpful");
        assertThat(m.role()).isEqualTo(Role.SYSTEM);
        assertThat(m.content()).isEqualTo("you are helpful");
    }
}
