package io.lumnik.hub.connector.rest.reliability;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RateLimiterRegistry {

    private final ConcurrentHashMap<UUID, RateLimiter> byConnector = new ConcurrentHashMap<>();

    public RateLimiter forConnector(UUID connectorId, String spec) {
        return byConnector.computeIfAbsent(connectorId, id -> parse(spec));
    }

    static RateLimiter parse(String spec) {
        String[] parts = spec.split("/");
        int count = Integer.parseInt(parts[0].trim());
        String dur = parts[1].trim();
        int n;
        char unit;
        if (Character.isDigit(dur.charAt(0))) {
            int idx = 0;
            while (idx < dur.length() && Character.isDigit(dur.charAt(idx))) idx++;
            n = Integer.parseInt(dur.substring(0, idx));
            unit = dur.charAt(idx);
        } else {
            n = 1;
            unit = dur.charAt(0);
        }
        Duration d = switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default  -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
        return new RateLimiter(count, d);
    }
}
