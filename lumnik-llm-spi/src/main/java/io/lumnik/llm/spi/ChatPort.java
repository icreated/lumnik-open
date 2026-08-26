package io.lumnik.llm.spi;

import java.util.List;

public interface ChatPort {

    /** Unique port identifier ("ollama", "openai", "anthropic", ...). */
    String name();

    /**
     * Generate a chat completion given an ordered list of messages with roles.
     * Implementations must be safe for concurrent calls.
     */
    String chat(List<ChatMessage> messages);

    /** Convenience: wraps the prompt as a single USER message. */
    default String chat(String prompt) {
        return chat(List.of(new ChatMessage(Role.USER, prompt)));
    }
}
