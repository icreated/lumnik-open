package io.lumnik.hub.connector.rest;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A source served over HTTPS by a certificate no public CA signed — an internal API, a cluster's API server (#1609).
 * The JDK's {@code keytool} makes the certificate at test time, so nothing secret is checked in.
 */
class RestHttpClientTrustIT {
    @TempDir static Path dir;
    static WireMockServer wm;
    static String serverPem;
    static String otherPem;

    @BeforeAll static void start() throws Exception {
        Path store = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit");
        serverPem = exportPem(store, "server");
        Path other = dir.resolve("other.p12");
        keytool("-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=other", "-storetype", "PKCS12", "-keystore", other.toString(),
            "-storepass", "changeit", "-keypass", "changeit");
        otherPem = exportPem(other, "other");

        wm = new WireMockServer(options().dynamicPort().dynamicHttpsPort()
            .keystorePath(store.toString()).keystorePassword("changeit").keyManagerPassword("changeit")
            .keystoreType("PKCS12"));
        wm.start();
        wm.stubFor(get(urlPathEqualTo("/api/v1/pods")).willReturn(aResponse().withStatus(200).withBody("{\"items\":[]}")));
    }

    @AfterAll static void stop() { if (wm != null) wm.stop(); }

    private static RestRequest pods() { return RestRequest.get("https://localhost:" + wm.httpsPort() + "/api/v1/pods"); }

    @Test
    void the_default_trust_refuses_an_internal_ca() {
        SSLHandshakeException e = assertThrows(SSLHandshakeException.class, () -> new RestHttpClient().execute(pods()));
        // What RetryPolicy reads to give up at once: the JDK puts a CertificateException under a trust refusal.
        assertTrue(OutboundFailure.isCertificateRefusal(e), "a trust refusal carries a CertificateException");
    }

    @Test
    void a_client_trusting_the_internal_ca_reads_the_source() throws Exception {
        RestResponse resp = new RestHttpClient().trusting(serverPem).execute(pods());
        assertEquals(200, resp.status());
        assertEquals("{\"items\":[]}", resp.body());
    }

    @Test
    void trusting_another_ca_still_refuses() {
        assertThrows(SSLHandshakeException.class, () -> new RestHttpClient().trusting(otherPem).execute(pods()));
    }

    @Test
    void the_same_ca_reuses_one_client() {
        RestHttpClient base = new RestHttpClient();
        assertSame(base.trusting(serverPem), base.trusting(serverPem));
        assertNotSame(base.trusting(serverPem), base.trusting(otherPem));
    }

    private static String exportPem(Path store, String alias) throws Exception {
        Path pem = dir.resolve(alias + ".pem");
        keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", store.toString(), "-storepass", "changeit",
            "-file", pem.toString());
        return Files.readString(pem);
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }
}
