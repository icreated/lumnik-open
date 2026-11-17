package io.lumnik.hub.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #1994: the PWA talks to its hub and to that hub's Keycloak, never to any host on Keycloak's port. */
class PwaContentSecurityPolicyTest {

    @Test
    void connectSrcNamesThePagesOwnHostOnKeycloaksPort() {
        assertThat(PwaContentSecurityPolicy.policy("lumnik.lan"))
            .contains("connect-src 'self' http://lumnik.lan:8180;")
            .contains("default-src 'self'", "script-src 'self'", "object-src 'none'", "base-uri 'self'",
                "frame-ancestors 'self'")
            .doesNotContain("*", "unsafe-inline", "unsafe-eval");
    }

    @Test
    void anIpv6HostIsBracketed() {
        assertThat(PwaContentSecurityPolicy.policy("::1")).contains("connect-src 'self' http://[::1]:8180;");
        assertThat(PwaContentSecurityPolicy.policy("[::1]")).as("as a Host header writes it")
            .contains("connect-src 'self' http://[::1]:8180;");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a b", "evil.example; script-src *", "x\r\nSet-Cookie: a=b", "host'"})
    void aHostThatIsNotOneNamesNoSecondSource(String host) {
        assertThat(PwaContentSecurityPolicy.policy(host)).contains("connect-src 'self';").doesNotContain("http://");
    }

    @Test
    void onlyThePwaCarriesIt() {
        assertThat(PwaContentSecurityPolicy.isPwa("/app")).isTrue();
        assertThat(PwaContentSecurityPolicy.isPwa("/app/")).isTrue();
        assertThat(PwaContentSecurityPolicy.isPwa("/app/src/config.mjs")).isTrue();
        assertThat(PwaContentSecurityPolicy.isPwa("/apple")).isFalse();
        assertThat(PwaContentSecurityPolicy.isPwa("/api/bff/v1/me")).isFalse();
    }

    /** config.mjs's kcBase and this policy name one port: neither may move alone. */
    @Test
    void theMapsTileServerIsAnImageSourceOnlyWhenTheOperatorNamedOne() {
        assertThat(PwaContentSecurityPolicy.policy("lumnik.lan", null))
            .isEqualTo(PwaContentSecurityPolicy.policy("lumnik.lan"))
            .contains("img-src 'self' data:;");
        assertThat(PwaContentSecurityPolicy.policy("lumnik.lan", "https://tile.openstreetmap.org"))
            .contains("img-src 'self' data: https://tile.openstreetmap.org;")
            .contains("connect-src 'self' http://lumnik.lan:8180;")
            .containsOnlyOnce("tile.openstreetmap.org");
    }

    @Test
    void thePortIsThePwasOwn() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/META-INF/resources/app/src/config.mjs"));
        assertThat(config).contains(":" + PwaContentSecurityPolicy.KEYCLOAK_PORT + "/realms/");
    }
}
