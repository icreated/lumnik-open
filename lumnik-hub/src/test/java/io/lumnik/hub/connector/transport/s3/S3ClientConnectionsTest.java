package io.lumnik.hub.connector.transport.s3;

import com.sun.net.httpserver.HttpServer;
import io.lumnik.hub.outbound.OutboundAddress;
import io.lumnik.hub.outbound.OutboundRefusedException;
import io.lumnik.hub.outbound.OutboundUnresolvedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2027: an S3 client looks its endpoint up again for each new connection, all run long. Each lookup is the hub's,
 * judged, and the client connects to the very addresses judged. No proxy of the hub's is used.
 */
class S3ClientConnectionsTest {

    private HttpServer store;
    private HttpServer proxy;
    private final AtomicInteger storeHits = new AtomicInteger();
    private final AtomicInteger proxyHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        store = server(storeHits);
        proxy = server(proxyHits);
    }

    @AfterEach
    void stop() {
        store.stop(0);
        proxy.stop(0);
    }

    private static HttpServer server(AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** The client alone, without the check S3Clients.build makes first: what each connection meets on its own. */
    private S3Client client(OutboundAddress addresses) {
        int port = store.getAddress().getPort();
        return S3Client.builder().region(Region.EU_WEST_3).credentialsProvider(AnonymousCredentialsProvider.create())
            .endpointOverride(URI.create("http://127.0.0.1:" + port)).forcePathStyle(true)
            .httpClientBuilder(S3Clients.httpClient(addresses, port)).build();
    }

    @Test
    void aConnectionIsMadeToAnAddressTheRuleAllows() {
        try (S3Client client = client(new OutboundAddress(true, true))) {
            client.headBucket(b -> b.bucket("erp"));
        }
        assertThat(storeHits).hasValue(1);
    }

    @Test
    void aConnectionToAnAddressTheRuleRefusesIsNeverOpened() {
        try (S3Client client = client(new OutboundAddress(false, true))) {
            assertThatThrownBy(() -> client.headBucket(b -> b.bucket("erp")))
                .isInstanceOf(OutboundRefusedException.class)   // as the hub wrote it: the run says why
                .hasMessageContaining("loopback");
        }
        assertThat(storeHits).hasValue(0);
    }

    @Test
    void theHubsProxyIsNotUsed() {
        Map<String, String> proxied = Map.of("http.proxyHost", "127.0.0.1",
            "http.proxyPort", String.valueOf(proxy.getAddress().getPort()), "http.nonProxyHosts", "");
        Map<String, String> before = new HashMap<>();
        proxied.forEach((name, value) -> {
            before.put(name, System.getProperty(name));
            System.setProperty(name, value);
        });
        try (S3Client client = client(new OutboundAddress(true, true))) {
            client.headBucket(b -> b.bucket("erp"));
        } finally {
            before.forEach((name, value) -> {
                if (value == null) System.clearProperty(name); else System.setProperty(name, value);
            });
        }
        assertThat(proxyHits).hasValue(0);
        assertThat(storeHits).hasValue(1);
    }

    /** A name that stops resolving mid-run: "host not resolved", as at the start, and the run retries. */
    @Test
    void aNameThatNoLongerResolvesIsRetryable() {
        try (S3Client client = S3Client.builder().region(Region.EU_WEST_3).credentialsProvider(AnonymousCredentialsProvider.create())
                .endpointOverride(URI.create("http://no-such-store.invalid:9000")).forcePathStyle(true)
                .httpClientBuilder(S3Clients.httpClient(new OutboundAddress(true, true), 9000)).build()) {
            assertThatThrownBy(() -> client.headBucket(b -> b.bucket("erp"))).isInstanceOf(OutboundUnresolvedException.class);
        }
    }
}
