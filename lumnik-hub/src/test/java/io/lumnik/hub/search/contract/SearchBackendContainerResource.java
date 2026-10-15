package io.lumnik.hub.search.contract;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

/**
 * Gives the hub's #674 lane a real search backend when — and only when — the build says so
 * ({@code lumnik.test.searchBackend.image} is set by an opt-in Search profile). The container is shared
 * by every test class of the JVM: the lane's two profiles ({@link SearchBackendOidcProfile},
 * {@link SearchProviderContractRlsProfile}) each declare this resource, and a container per application
 * would multiply a 20-second start and exhaust a developer machine's Docker memory.
 *
 * <p>Deliberately knows no backend by name: an image, a port, {@code KEY=VALUE;KEY=VALUE} environment,
 * and the configuration key that should receive the URL.
 */
public class SearchBackendContainerResource implements QuarkusTestResourceLifecycleManager {

    /**
     * Shared through a system property, not a static field: Quarkus loads a test resource in a class loader
     * of its own for each application, so a static would be per profile and the two profiles would start two
     * clusters. A property is the one place every class loader of the JVM sees.
     */
    private static final String SHARED_URL = "lumnik.test.searchBackend.sharedUrl";

    @Override
    public synchronized Map<String, String> start() {
        String image = System.getProperty("lumnik.test.searchBackend.image", "");
        if (image.isBlank()) return Map.of();
        String urlProperty = System.getProperty("lumnik.test.searchBackend.urlProperty");
        String url = System.getProperty(SHARED_URL);
        if (url == null) {
            GenericContainer<?> c = container(image);
            url = "http://" + c.getHost() + ":" + c.getMappedPort(port());
            System.setProperty(SHARED_URL, url);
        }
        return Map.of(urlProperty, url);
    }

    private static int port() {
        return Integer.parseInt(System.getProperty("lumnik.test.searchBackend.port", "9200"));
    }

    private static GenericContainer<?> container(String image) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
            .withExposedPorts(port())
            .withStartupTimeout(Duration.ofMinutes(3));
        for (String pair : System.getProperty("lumnik.test.searchBackend.env", "").split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) c.withEnv(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        c.waitingFor(Wait.forHttp("/").forPort(port()).forStatusCode(200));
        c.start();
        Runtime.getRuntime().addShutdownHook(new Thread(c::stop));
        return c;
    }

    @Override
    public void stop() {
        // shared across classes; the shutdown hook and Ryuk stop it
    }
}
