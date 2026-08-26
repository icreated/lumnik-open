package io.lumnik.identity.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
@LookupIfProperty(name = "lumnik.geocoder.provider", stringValue = "nominatim", lookupIfMissing = true)
public class NominatimGeocoder implements Geocoder {

    private static final Logger LOG = Logger.getLogger(NominatimGeocoder.class);

    @ConfigProperty(name = "lumnik.geocoder.nominatim.url",
                    defaultValue = "https://nominatim.openstreetmap.org/search")
    String baseUrl;

    @ConfigProperty(name = "lumnik.geocoder.user-agent",
                    defaultValue = "lumnik/0.1 (contact@lumnik.io)")
    String userAgent;

    @Inject ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Optional<Coords> resolve(AddressInput addr) {
        if (addr == null || !addr.isUsable()) return Optional.empty();
        String q = encode(buildQuery(addr));
        String url = baseUrl + "?format=json&limit=1&q=" + q;
        try {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", userAgent)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("Nominatim returned %d for %s", resp.statusCode(), url);
                return Optional.empty();
            }
            JsonNode arr = mapper.readTree(resp.body());
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            JsonNode first = arr.get(0);
            return Optional.of(new Coords(
                new BigDecimal(first.get("lat").asText()),
                new BigDecimal(first.get("lon").asText())));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.warnf(e, "Nominatim resolve failed for %s", url);
            return Optional.empty();
        }
    }

    private String buildQuery(AddressInput addr) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, addr.line1());
        appendIfPresent(sb, addr.postalCode());
        appendIfPresent(sb, addr.city());
        appendIfPresent(sb, addr.country());
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String v) {
        if (v == null || v.isBlank()) return;
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(v.strip());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
