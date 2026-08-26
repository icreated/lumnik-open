package io.lumnik.hub.api.secret;

public record SecretUpdateRequest(String value, String description) {
    @Override public String toString() {
        return "SecretUpdateRequest[value=***, description=" + description + "]";
    }
}
