package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.secret.SecretRefs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** {@code spec.tls.ca_secret}: the connector-level trust in an internal CA (#1609). */
class CaTrustTest {
    private static final Map<String, Object> TLS = Map.of("ca_secret", "CORP_CA");
    private final RestHttpClient base = new RestHttpClient();

    private static SecretRefs holding(Map<String, String> secrets) {
        return SecretRefs.environmentOnly(secrets::get);
    }

    @Test
    void without_a_tls_block_the_default_client_is_kept() {
        assertSame(base, CaTrust.clientFor(null, base, holding(Map.of())));
    }

    /** A tls block that names no usable CA fails closed — never the public trust (review of #1612). */
    @ParameterizedTest
    @MethodSource("unusableBlocks")
    void a_tls_block_naming_no_ca_is_refused(Map<String, Object> tls) {
        AuthConfigException e = assertThrows(AuthConfigException.class,
            () -> CaTrust.clientFor(tls, base, holding(Map.of())));
        assertEquals("tls names no CA: ca_secret is missing or blank", e.getMessage());
    }

    static Stream<Map<String, Object>> unusableBlocks() {
        return Stream.of(Map.of(), Map.of("ca_secret", ""), Map.of("ca_secret", "  "), Map.of("ca_secret", 42));
    }

    @Test
    void a_named_secret_nothing_holds_is_refused_by_its_name() {
        AuthConfigException e = assertThrows(AuthConfigException.class,
            () -> CaTrust.clientFor(TLS, base, holding(Map.of())));
        assertEquals("CA certificate unavailable (secret=CORP_CA)", e.getMessage());
    }

    @Test
    void a_secret_that_holds_no_certificate_is_refused_without_quoting_it() {
        AuthConfigException e = assertThrows(AuthConfigException.class,
            () -> CaTrust.clientFor(TLS, base, holding(Map.of("CORP_CA", "s3cr3t-not-a-pem"))));
        assertEquals("secret CORP_CA holds no PEM certificate", e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"));
    }

    @Test
    void a_truncated_certificate_is_refused_without_quoting_it() {
        String broken = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUs3cr3t\n-----END CERTIFICATE-----\n";
        AuthConfigException e = assertThrows(AuthConfigException.class,
            () -> CaTrust.clientFor(TLS, base, holding(Map.of("CORP_CA", broken))));
        assertEquals("secret CORP_CA holds no PEM certificate", e.getMessage());
    }
}
