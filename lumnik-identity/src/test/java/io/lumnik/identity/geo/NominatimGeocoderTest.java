package io.lumnik.identity.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class NominatimGeocoderTest {

    HttpServer server;
    NominatimGeocoder geocoder;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", ex -> {
            String body = "[{\"lat\":\"48.8566\",\"lon\":\"2.3522\"}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        geocoder = new NominatimGeocoder();
        geocoder.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/search";
        geocoder.userAgent = "test/1.0";
        geocoder.mapper = new ObjectMapper();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void resolves_paris() {
        Optional<Coords> c = geocoder.resolve(new AddressInput("1 rue de Rivoli", "75001", "Paris", "FR"));
        assertThat(c, is(notNullValue()));
        assertThat(c.isPresent(), is(true));
        assertThat(c.get().latitude().doubleValue(), closeTo(48.8566, 0.0001));
        assertThat(c.get().longitude().doubleValue(), closeTo(2.3522, 0.0001));
    }

    @Test
    void empty_address_returns_empty() {
        Optional<Coords> c = geocoder.resolve(new AddressInput(null, null, null, null));
        assertThat(c, is(notNullValue()));
        assertThat(c.isEmpty(), is(true));
    }
}
