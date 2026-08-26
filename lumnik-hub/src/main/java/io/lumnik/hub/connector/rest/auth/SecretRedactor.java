package io.lumnik.hub.connector.rest.auth;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i).*(key|token|secret|password|auth).*");

    private SecretRedactor() {}

    public static String redactHeader(String name, String value) {
        if (!SECRET.matcher(name).matches()) return value;
        if (value != null && value.startsWith("Bearer ")) return "Bearer <redacted>";
        if (value != null && value.startsWith("Basic "))  return "Basic <redacted>";
        return "<redacted>";
    }
}
