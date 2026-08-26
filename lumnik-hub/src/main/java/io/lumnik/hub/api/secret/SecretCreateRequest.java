package io.lumnik.hub.api.secret;

public record SecretCreateRequest(String name, String value, String description) {
    @Override public String toString() {
        return "SecretCreateRequest[name=" + name + ", value=***, description=" + description + "]";
    }
}
