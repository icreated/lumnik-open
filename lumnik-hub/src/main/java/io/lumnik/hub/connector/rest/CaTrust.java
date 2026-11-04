package io.lumnik.hub.connector.rest;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.secret.SecretRefs;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Map;

/**
 * A connector's trust in an internal CA (#1609): {@code spec.tls.ca_secret} names an lm secret holding the CA's PEM
 * certificate(s), and that connector's requests trust those CAs — and only those. An internal API, a cluster's API
 * server, a reverse proxy with its own PKI: the JDK's public trust refuses them at the handshake.
 *
 * <p>A refusal names the secret, never its content: the PEM is not secret, but the value an operator stored by
 * mistake under that name may be. The search cluster's {@code TlsTrust} builds the same context from a file.
 */
public final class CaTrust {
    private CaTrust() {}

    /**
     * The client a connector's requests go through, given its {@code tls} block — {@code null} when the connector
     * declares none, and only then {@code http} itself. A block that names no CA, a secret nothing holds, or one the
     * certificate parser cannot read is refused: a declared trust never falls back to the public one.
     */
    public static RestHttpClient clientFor(Map<String, Object> tls, RestHttpClient http, SecretRefs secrets) {
        if (tls == null) return http;
        if (!(tls.get("ca_secret") instanceof String name) || name.isBlank()) {
            throw new AuthConfigException("tls names no CA: ca_secret is missing or blank");
        }
        String pem = secrets.lookup(name);
        if (Text.blank(pem)) throw new AuthConfigException("CA certificate unavailable (secret=" + name + ")");
        try {
            return http.trusting(pem);
        } catch (IllegalArgumentException unreadable) {
            throw new AuthConfigException("secret " + name + " holds no PEM certificate");
        }
    }

    /**
     * An SSL context trusting the certificates of {@code pem} and nothing else. An unreadable PEM is an
     * {@link IllegalArgumentException} without a cause: a certificate parser's message can quote the bytes it read.
     */
    static SSLContext sslContext(String pem) {
        try {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (certs.isEmpty()) throw new CertificateException("no certificate");
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate cert : certs) store.setCertificateEntry("ca-" + i++, cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | IOException unreadable) {
            throw new IllegalArgumentException("no PEM certificate");
        }
    }
}
