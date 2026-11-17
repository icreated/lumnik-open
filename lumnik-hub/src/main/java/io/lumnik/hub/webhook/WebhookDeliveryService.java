package io.lumnik.hub.webhook;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.outbound.OutboundAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class WebhookDeliveryService {

    /** The most of a receiver's body a delivery stores. */
    private static final int STORED_CHARS = 2000;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject SecretCipher cipher;
    @Inject OutboundAddress addresses;
    /** How long a receiver's body is read once its status arrived (#2002): the request's timeout ends there. */
    Duration bodyDeadline = Duration.ofSeconds(10);

    /**
     * {@code eventId} — the {@code core.event_outbox} id — rides an UNSIGNED header: the HMAC
     * (below) covers only the raw body, so a receiver correlating by this id must trust transport
     * security, not the signature. May be null (the {@code /test} door fires a synthetic event with
     * no outbox row); the header is then simply omitted.
     */
    public DeliveryResult deliver(WebhookEndpoint ep, String eventType, Long eventId, String body) {
        long start = System.nanoTime();
        try {
            URI target = URI.create(ep.getTargetUrl());
            addresses.require(target);   // #2002: before the secret is read or anything is sent
            HttpRequest.Builder rq = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Lumnik-Event", eventType);
            if (eventId != null) rq.header("X-Lumnik-Event-Id", String.valueOf(eventId));

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

            HttpResponse<InputStream> resp = http.send(
                rq.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            String stored = stored(resp.body());
            int code = resp.statusCode();
            int dur = durationMs(start);
            return (code >= 200 && code < 300)
                ? DeliveryResult.success(code, stored, dur)
                : DeliveryResult.httpFailure(code, stored, dur);
        } catch (ConfigValidationException refused) {
            return DeliveryResult.failure(refused.getMessage(), 0, durationMs(start));   // OutboundAddress: names the range only
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure("interrupted", 0, durationMs(start));
        } catch (Exception e) {
            // The kind and the class, never the message (#1162): see OutboundFailure.
            return DeliveryResult.failure(OutboundFailure.describe(e), 0, durationMs(start));
        }
    }

    /**
     * What is stored of a receiver's body, and no more is read (#2002): closing the stream stops the transfer. Read
     * until {@link #bodyDeadline}, then closed under the reader: the status has answered, the body is a courtesy, so a
     * cut one is stored empty.
     */
    private String stored(InputStream body) {
        CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> closeQuietly(body),
            CompletableFuture.delayedExecutor(bodyDeadline.toMillis(), TimeUnit.MILLISECONDS));
        try (body) {
            return truncate(new String(body.readNBytes(STORED_CHARS * 4), StandardCharsets.UTF_8), STORED_CHARS);
        } catch (IOException cut) {
            return "";
        } finally {
            deadline.cancel(false);   // a cancelled runAsync never runs its task
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException alreadyGone) {
            // the reader sees the close either way
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
        /**
         * The status alone in the error (#2002): the error is what the doors hand back — the test, the replay, the
         * delivery list — and a receiver's body there was a read of whatever the URL reached. The body is stored.
         */
        public static DeliveryResult httpFailure(int code, String body, int dur) {
            return new DeliveryResult(false, code, body, "HTTP " + code, dur);
        }
        public static DeliveryResult failure(String err, int code, int dur) {
            return new DeliveryResult(false, code == 0 ? null : code, null, err, dur);
        }
    }
}
