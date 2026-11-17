package io.lumnik.hub.outbound;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.outbound.OutboundAddress.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where a tenant-chosen connection may go (#2002): judged on the address the host resolves to. */
class OutboundAddressTest {

    private static final OutboundAddress PRODUCTION = new OutboundAddress(false, true);
    private static final OutboundAddress MULTI_TENANT = new OutboundAddress(false, false);
    private static final OutboundAddress TESTS = new OutboundAddress(true, true);

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        "127.0.0.1, LOOPBACK", "127.8.8.8, LOOPBACK", "::1, LOOPBACK",
        "169.254.169.254, LINK_LOCAL", "169.254.0.1, LINK_LOCAL", "fe80::1, LINK_LOCAL",
        "0.0.0.0, UNSPECIFIED", "0.1.2.3, UNSPECIFIED", "::, UNSPECIFIED",
        "224.0.0.1, MULTICAST", "ff02::1, MULTICAST", "255.255.255.255, MULTICAST",
        "10.0.0.1, PRIVATE", "172.16.0.1, PRIVATE", "172.31.255.255, PRIVATE", "192.168.1.1, PRIVATE",
        "100.64.0.1, PRIVATE", "100.100.100.201, PRIVATE", "fc00::1, PRIVATE", "fd00:ec2::253, PRIVATE",
        // the cloud metadata services outside 169.254.0.0/16: AWS over IPv6, Alibaba
        "fd00:ec2::254, METADATA", "100.100.100.200, METADATA", "168.63.129.16, METADATA", "192.0.0.192, METADATA",
        "168.63.129.17, PUBLIC",
        // an IPv4 address carried in IPv6 is judged as itself
        "::ffff:127.0.0.1, LOOPBACK", "::ffff:169.254.169.254, LINK_LOCAL", "::127.0.0.1, LOOPBACK",
        "::a9fe:a9fe, LINK_LOCAL", "64:ff9b::a9fe:a9fe, LINK_LOCAL", "64:ff9b::a00:1, PRIVATE",
        // SIIT, local-use NAT64 (RFC 8215), 6to4, Teredo (its client address inverted)
        "::ffff:0:a9fe:a9fe, LINK_LOCAL", "2002:a9fe:a9fe::1, LINK_LOCAL",
        // local-use NAT64 puts the IPv4 address where its prefix length says: refused outright
        // ...so each RFC 6052 layout a /48 network may use is read, and the worst one stands
        "64:ff9b:1::a00:1, TRANSLATED", "64:ff9b:1::a9fe:a9fe, LINK_LOCAL",
        "64:ff9b:1:a9fe:a9:fe08:808:808, LINK_LOCAL", "64:ff9b:1:7f:0:1:: , LOOPBACK",
        // reserved ranges a private network uses
        "198.18.0.1, PRIVATE", "198.19.255.255, PRIVATE", "192.0.0.8, PRIVATE", "240.0.0.1, PRIVATE", "198.20.0.1, PUBLIC",
        "2002:808:808::1, PUBLIC", "2001:0:4136:e378:8000:63bf:5601:5601, LINK_LOCAL",
        "8.8.8.8, PUBLIC", "172.32.0.1, PUBLIC", "100.128.0.1, PUBLIC", "2001:4860:4860::8888, PUBLIC",
    })
    void each_address_falls_in_its_range(String literal, Range expected) throws UnknownHostException {
        assertThat(OutboundAddress.range(InetAddress.getByName(literal))).isEqualTo(expected);
    }

    @Test
    void link_local_unspecified_and_multicast_are_refused_whatever_the_operator_allows() {
        for (String literal : new String[] {"169.254.169.254", "0.0.0.0", "224.0.0.1", "fe80::1", "fd00:ec2::254", "100.100.100.200"}) {
            assertThatThrownBy(() -> TESTS.require(URI.create("http://" + bracketed(literal) + "/latest/meta-data/")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("never connects to");
        }
    }

    /** The warning the rebinding narrowing relies on: either TTL property set to 0 turns the cache off. */
    @ParameterizedTest(name = "security={0} legacy={1} -> {2}")
    @CsvSource(nullValues = "null", value = {"null, null, false", "0, null, true", "null, 0, true", "30, null, false", "-1, 5, false"})
    void a_cache_ttl_of_zero_is_noticed(String securityTtl, String legacyTtl, boolean off) {
        assertThat(OutboundAddress.cacheOff(securityTtl, Optional.ofNullable(legacyTtl))).isEqualTo(off);
    }

    @Test
    void loopback_is_refused_unless_the_operator_allows_it() {
        URI local = URI.create("http://127.0.0.1:1/");
        assertThatThrownBy(() -> PRODUCTION.require(local))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("loopback")
            .hasMessageContaining("lumnik.outbound.allow-loopback");
        assertThatCode(() -> TESTS.require(local)).doesNotThrowAnyException();
    }

    @Test
    void private_is_allowed_on_premise_and_refused_in_multi_tenant() {
        URI lan = URI.create("http://10.1.2.3:8080/api");
        assertThatCode(() -> PRODUCTION.require(lan)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MULTI_TENANT.require(lan))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("private")
            .hasMessageContaining("lumnik.outbound.allow-private");
    }

    /** A local-use NAT64 address goes with the private ones: a translated LAN is the case it was made for. */
    @Test
    void local_use_nat64_follows_the_private_switch() {
        URI translated = URI.create("http://[64:ff9b:1::a00:1]/api");
        assertThatCode(() -> PRODUCTION.require(translated)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MULTI_TENANT.require(translated))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("local-use NAT64")
            .hasMessageContaining("lumnik.outbound.allow-private");
    }

    /**
     * The address judged is the one Java connects to: the same resolver reads the host for both. A
     * decimal form of 127.0.0.1 is loopback here as it would be on the wire.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2130706433", "[::1]", "[::ffff:127.0.0.1]", "localhost"})
    void another_spelling_of_loopback_is_still_loopback(String host) {
        assertThatThrownBy(() -> PRODUCTION.require(URI.create("http://" + host + ":1/")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("loopback");
    }

    /** {@code 127.1} is no host to {@link URI}, so the HTTP client would not connect to it either: refused. */
    @Test
    void a_short_form_is_no_host_and_refused() {
        assertThatThrownBy(() -> PRODUCTION.require(URI.create("http://127.1:1/")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("no host");
    }

    @Test
    void every_address_of_the_host_is_judged_not_only_the_first() {
        // localhost resolves to 127.0.0.1 and ::1; refusing loopback must refuse it whatever comes first
        assertThatThrownBy(() -> PRODUCTION.require(URI.create("http://localhost/")))
            .isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void the_refusal_names_the_range_never_the_address() {
        assertThatThrownBy(() -> TESTS.require(URI.create("http://169.254.169.254/")))
            .message().doesNotContain("169.254");
    }

    @Test
    void a_url_without_a_host_is_refused() {
        assertThatThrownBy(() -> PRODUCTION.require(URI.create("file:///etc/passwd")))
            .isInstanceOf(ConfigValidationException.class);
    }

    private static String bracketed(String literal) {
        return literal.contains(":") ? "[" + literal + "]" : literal;
    }

    /**
     * A host that does not resolve is left to the client: it fails in its own words (a driver's
     * SQLSTATE 08, retried), and a failover list goes on to its next host. Those that resolve are judged.
     */
    @Test
    void require_all_leaves_an_unresolved_host_to_the_client_and_judges_the_rest() {
        assertThatCode(() -> PRODUCTION.requireAll(List.of("no-such-host.invalid", "8.8.8.8"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PRODUCTION.requireAll(List.of("no-such-host.invalid", "169.254.169.254")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("never connects to");
    }

    /**
     * A string that does not resolve and is not even a host name — quoted, a list, spaces — is refused: its client may
     * read it otherwise (Oracle Net unquotes a HOST), and leaving it to the client would let that reading go unjudged.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"10.0.0.5\"", "public.example.com,169.254.169.254", "erp db", "erp;x"})
    void a_string_that_is_no_host_name_is_refused_not_left_to_the_client(String host) {
        assertThatThrownBy(() -> PRODUCTION.requireAll(List.of(host)))
            .isInstanceOf(OutboundRefusedException.class);
    }

    /** A blank or missing host — URI.getHost() of an unparsed authority — is refused, never left to the client. */
    @Test
    void a_blank_host_is_refused_by_require_all() {
        assertThatThrownBy(() -> PRODUCTION.requireAll(Collections.singletonList(null))).isInstanceOf(OutboundRefusedException.class);
        assertThatThrownBy(() -> PRODUCTION.requireAll(List.of(""))).isInstanceOf(OutboundRefusedException.class);
    }

    @Test
    void an_unresolved_name_with_an_underscore_is_still_a_name() {
        assertThatCode(() -> PRODUCTION.requireAll(List.of("erp_db.invalid"))).doesNotThrowAnyException();
    }

    /**
     * A client that keeps resolving for the whole run (Kafka, S3) is not left an unresolved name: it would look it up
     * again later, past the cache, and connect wherever it then leads, unchecked. Refused, as a failure the run retries.
     */
    @Test
    void a_long_lived_client_is_not_left_an_unresolved_name() {
        assertThatThrownBy(() -> PRODUCTION.requireAllResolved(List.of("no-such-host.invalid")))
            .isInstanceOf(OutboundUnresolvedException.class)
            .isInstanceOf(SourceUnreachableException.class)
            .satisfies(e -> assertThat(((SourceUnreachableException) e).isRetryable()).isTrue())
            .message().doesNotContain("no-such-host");
        assertThatCode(() -> PRODUCTION.requireAllResolved(List.of("8.8.8.8"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PRODUCTION.requireAllResolved(List.of("169.254.169.254")))
            .isInstanceOf(OutboundRefusedException.class);
    }
}
