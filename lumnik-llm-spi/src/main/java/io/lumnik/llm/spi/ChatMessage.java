package io.lumnik.llm.spi;

public record ChatMessage(Role role, String content) {
    public ChatMessage {
        if (role == null) throw new IllegalArgumentException("role required");
        if (content == null) throw new IllegalArgumentException("content required");
    }
}
