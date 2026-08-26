package io.lumnik.hub.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

@ApplicationScoped
public class WebhookDeliveryService {

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject SecretCipher cipher;

    public DeliveryResult deliver(WebhookEndpoint ep, String eventType, String body) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(ep.getTargetUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Lumnik-Event", eventType);

            String secret = cipher.decrypt(ep.getSecretEncrypted());

            switch (ep.getAuthMode()) {
                case HMAC -> {
                    if (secret == null)
                        return DeliveryResult.failure("HMAC mode but no secret configured", 0, durationMs(start));
                    rq.header("X-Lumnik-Signature", "sha256=" + hmacSha256(secret, body));
                }
                case BEARER -> {
                    if (secret == null)
                        return DeliveryResult.failure("Bearer mode but no token configured", 0, durationMs(start));
                    rq.header("Authorization", "Bearer " + secret);
                }
                case NONE -> { /* no extra headers */ }
            }

            HttpResponse<String> resp = http.send(
                rq.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            int dur = durationMs(start);
            return (code >= 200 && code < 300)
                ? DeliveryResult.success(code, truncate(resp.body(), 2000), dur)
                : DeliveryResult.httpFailure(code, truncate(resp.body(), 2000), dur);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure("interrupted", 0, durationMs(start));
        } catch (Exception e) {
            return DeliveryResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage(),
                                          0, durationMs(start));
        }
    }

    private static int durationMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String hmacSha256(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }

    public record DeliveryResult(boolean ok, Integer httpStatus,
                                 String responseBody, String error, int durationMs) {
        public static DeliveryResult success(int code, String body, int dur) {
            return new DeliveryResult(true, code, body, null, dur);
        }
        public static DeliveryResult httpFailure(int code, String body, int dur) {
            return new DeliveryResult(false, code, body,
                "HTTP " + code + ": " + truncate200(body), dur);
        }
        public static DeliveryResult failure(String err, int code, int dur) {
            return new DeliveryResult(false, code == 0 ? null : code, null, err, dur);
        }
        private static String truncate200(String s) {
            return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
        }
    }
}
