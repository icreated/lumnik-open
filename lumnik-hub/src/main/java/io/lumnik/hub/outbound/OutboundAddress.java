package io.lumnik.hub.outbound;

import io.lumnik.common.text.Text;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where the hub may connect when a tenant chose the address (#2002): a REST source, an OpenAPI spec, a webhook, a JDBC
 * source, an S3 endpoint, an SFTP server, a Kafka bootstrap.
 *
 * <p>Judged on every address the host resolves to, not on its name: a name an attacker controls can resolve to
 * anything. Resolved with {@link InetAddress#getAllByName}, the resolver the JDK HTTP client uses a moment later, so
 * both read the JVM's address cache and agree while it holds the entry ({@code networkaddress.cache.ttl}, 30 s by
 * default). A name whose entry expires between the two is resolved again, unjudged: rebinding is narrowed, not
 * closed. A TTL of 0 is warned about at startup.
 *
 * <ul>
 *   <li>Never: link-local (169.254.0.0/16, where the cloud metadata answers; fe80::/10), the metadata services
 *       outside it, unspecified (0.0.0.0/8, ::) and multicast or broadcast. No source lives there.</li>
 *   <li>Loopback only when the operator allows it ({@code lumnik.outbound.allow-loopback}, default false; true in
 *       {@code %dev} and {@code %test}): in a container it is the hub itself.</li>
 *   <li>Private ranges (RFC 1918, 100.64.0.0/10, 198.18.0.0/15, 192.0.0.0/24, 240.0.0.0/4, fc00::/7) unless the operator refuses them
 *       ({@code lumnik.outbound.allow-private}, default true: the ERP of an on-premise hub sits on the LAN). The
 *       multi-tenant Helm values set it to false. The local-use NAT64 prefix {@code 64:ff9b:1::/48} goes with
 *       them, once each layout its IPv4 address may take was read ({@link #translated}).</li>
 * </ul>
 *
 * An IPv4 address carried in IPv6 (mapped, compatible, SIIT, NAT64, 6to4, Teredo) is judged as itself.
 */
@Singleton
public final class OutboundAddress {

    /** Where an address falls, named as a refusal says it. */
    enum Range {
        LOOPBACK("loopback"), LINK_LOCAL("link-local"), METADATA("cloud metadata"), UNSPECIFIED("unspecified"),
        MULTICAST("multicast"), TRANSLATED("local-use NAT64"), PRIVATE("private"), PUBLIC("public");

        final String label;

        Range(String label) {
            this.label = label;
        }
    }

    private static final byte[] IPV4_COMPATIBLE = new byte[12];
    private static final byte[] SIIT = {0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0};
    private static final byte[] NAT64 = {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] NAT64_LOCAL = {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0x00, 0x01};
    private static final byte[] SIX_TO_FOUR = {0x20, 0x02};
    private static final byte[] TEREDO = {0x20, 0x01, 0x00, 0x00};
    private static final byte[] BROADCAST = {-1, -1, -1, -1};
    /**
     * The cloud metadata services outside 169.254.0.0/16, refused whatever {@code allow-private} says: AWS over IPv6
     * ({@code fd00:ec2::254}), Alibaba ({@code 100.100.100.200}), Azure's WireServer ({@code 168.63.129.16}, a public
     * address) and Oracle Cloud's {@code 192.0.0.192}.
     */
    private static final Set<InetAddress> METADATA = Set.of(literal("fd00:ec2::254"), literal("100.100.100.200"),
        literal("168.63.129.16"), literal("192.0.0.192"));

    /** A DNS name (letters, digits, '-', '_', dot-separated labels, an optional final dot): what is left to a client. */
    private static final Pattern HOST_NAME =
        Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_-]*[A-Za-z0-9_])?(?:\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]*[A-Za-z0-9_])?)*\\.?");

    private static final Logger LOG = Logger.getLogger(OutboundAddress.class);

    private final boolean allowLoopback;
    private final boolean allowPrivate;

    /** As a test builds it: the JVM's own cache settings are not looked at. */
    public OutboundAddress(boolean allowLoopback, boolean allowPrivate) {
        this.allowLoopback = allowLoopback;
        this.allowPrivate = allowPrivate;
    }

    /**
     * {@code legacyTtl}: {@code sun.net.inetaddr.ttl}, a system property the JDK honours too, read through the config
     * (system properties are one of its sources).
     */
    @Inject
    public OutboundAddress(@ConfigProperty(name = "lumnik.outbound.allow-loopback", defaultValue = "false") boolean allowLoopback,
                           @ConfigProperty(name = "lumnik.outbound.allow-private", defaultValue = "true") boolean allowPrivate,
                           @ConfigProperty(name = "sun.net.inetaddr.ttl") Optional<String> legacyTtl) {
        this(allowLoopback, allowPrivate);
        if (cacheOff(Security.getProperty("networkaddress.cache.ttl"), legacyTtl)) {
            LOG.warn("an address cache TTL of 0: the HTTP client resolves a host again after the outbound rule judged"
                + " it, so a name that changes its answer in between is connected to unjudged (#2002)");
        }
    }

    /** Whether the JVM caches no address: the security property, or else the legacy system property, is 0. */
    static boolean cacheOff(String securityTtl, Optional<String> legacyTtl) {
        return "0".equals(securityTtl != null ? securityTtl : legacyTtl.orElse(null));
    }

    /**
     * Returns when every address of {@code url}'s host may be connected to. A refusal is a
     * {@link OutboundRefusedException}, never retried; it names the range and the switch, never the address. A host
     * that does not resolve is the {@link UnknownHostException} the connection would have met.
     */
    public void require(URI url) throws UnknownHostException {
        String host = url.getHost();
        if (Text.blank(host)) throw new OutboundRefusedException("the URL names no host");
        require(host);
    }

    /**
     * Every host a client names outside a URL — a JDBC driver's, an S3 endpoint's, an SFTP server, a Kafka bootstrap —
     * judged as {@link #require(String)} judges one. A host that does not resolve is left to the client: it meets the
     * same failure in its own words (a JDBC driver's SQLSTATE 08, which the run retries), and a failover list goes on
     * to its next host as the client would. The client's own lookup follows within milliseconds and reads the JVM's
     * negative cache ({@code networkaddress.cache.negative.ttl}, 10 s by default), so it meets the same "not resolved"
     * unless that entry expires in between — the window this class's own description names for a positive answer.
     */
    public void requireAll(Collection<String> hosts) {
        for (String host : hosts) {
            try {
                require(host);
            } catch (UnknownHostException leftToTheClient) {
                // Judged when it resolves, the client saying why it did not — if it is a host name at all. Anything
                // else (quoted, a list, spaces) its client may read otherwise, unjudged: refused here.
                if (!HOST_NAME.matcher(host).matches()) {
                    throw new OutboundRefusedException("the source names a host the hub cannot judge");
                }
            }
        }
    }

    /**
     * As {@link #requireAll}, for a client that keeps resolving for the whole run — a Kafka consumer reconnecting, an
     * S3 client opening connections. Left an unresolved name, it would look it up again later, past the JVM's cache,
     * and connect wherever it then led, unchecked. So a host that does not resolve is refused, as a source that cannot
     * be reached for now ({@link OutboundUnresolvedException}): the run retries it, and judges it again then.
     */
    public void requireAllResolved(Collection<String> hosts) {
        for (String host : hosts) {
            try {
                require(host);
            } catch (UnknownHostException unresolved) {
                throw new OutboundUnresolvedException();
            }
        }
    }

    /** As {@link #require(URI)}, for one host named outside a URL. A bracketed IPv6 literal is read as one. */
    public void require(String host) throws UnknownHostException {
        if (Text.blank(host)) throw new OutboundRefusedException("the source names no host");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            Range range = range(address);
            String why = switch (range) {
                case LINK_LOCAL, METADATA, UNSPECIFIED, MULTICAST ->
                    "the host resolves to a " + range.label + " address, which the hub never connects to";
                case LOOPBACK -> allowLoopback ? null
                    : "the host resolves to a loopback address, which this hub refuses (lumnik.outbound.allow-loopback)";
                case PRIVATE, TRANSLATED -> allowPrivate ? null
                    : "the host resolves to a " + range.label + " address, which this hub refuses (lumnik.outbound.allow-private)";
                case PUBLIC -> null;
            };
            if (why != null) throw new OutboundRefusedException(why);
        }
    }

    static Range range(InetAddress a) {
        if (a.isAnyLocalAddress()) return Range.UNSPECIFIED;
        if (a.isLoopbackAddress()) return Range.LOOPBACK;
        byte[] b = a.getAddress();
        if (startsWith(b, NAT64_LOCAL)) return translated(b);
        byte[] carried = a instanceof Inet6Address ? carriedIpv4(b) : null;
        if (carried != null) return range(ipv4(carried));
        if (a.isLinkLocalAddress()) return Range.LINK_LOCAL;
        if (METADATA.contains(a)) return Range.METADATA;
        if (a.isMulticastAddress() || Arrays.equals(b, BROADCAST)) return Range.MULTICAST;
        int first = b[0] & 0xff;
        if (b.length == 4 && first == 0) return Range.UNSPECIFIED;
        if (a.isSiteLocalAddress()
            || b.length == 4 && first == 100 && (b[1] & 0xc0) == 0x40      // 100.64.0.0/10, shared address space
            || b.length == 4 && first == 198 && (b[1] & 0xfe) == 18        // 198.18.0.0/15, benchmarking
            || b.length == 4 && first == 192 && b[1] == 0 && b[2] == 0     // 192.0.0.0/24, protocol assignments
            || b.length == 4 && first >= 240                               // 240.0.0.0/4, reserved
            || b.length == 16 && (first & 0xfe) == 0xfc) {                 // fc00::/7, unique local
            return Range.PRIVATE;
        }
        return Range.PUBLIC;
    }

    /**
     * The IPv4 address an IPv6 one carries, or null: compatible ({@code ::a.b.c.d}), SIIT ({@code ::ffff:0:a.b.c.d}),
     * NAT64 ({@code 64:ff9b::/96}), 6to4 ({@code 2002:AABB:CCDD::/48}) and
     * Teredo ({@code 2001:0::/32}, its client address inverted in the last four bytes). A mapped one
     * ({@code ::ffff:a.b.c.d}) never gets here: the JDK turns it into an {@code Inet4Address} itself.
     */
    private static byte[] carriedIpv4(byte[] b) {
        if (startsWith(b, IPV4_COMPATIBLE) || startsWith(b, SIIT) || startsWith(b, NAT64)) {
            return Arrays.copyOfRange(b, 12, 16);
        }
        if (startsWith(b, SIX_TO_FOUR)) return Arrays.copyOfRange(b, 2, 6);
        if (startsWith(b, TEREDO)) {
            byte[] client = Arrays.copyOfRange(b, 12, 16);
            for (int i = 0; i < 4; i++) client[i] = (byte) ~client[i];
            return client;
        }
        return null;
    }

    /**
     * A local-use NAT64 address ({@code 64:ff9b:1::/48}): its IPv4 address sits where the network's prefix length puts
     * it (RFC 6052: /48, /56, /64 or /96 under this prefix, the u-octet at byte 8 skipped), which the hub cannot know.
     * Each layout is read; one that carries a loopback, link-local or metadata address refuses it as that. Zeros read
     * through another layout's bytes are no evidence. Otherwise it goes with the private ranges.
     */
    private static Range translated(byte[] b) {
        byte[][] layouts = {
            {b[6], b[7], b[9], b[10]}, {b[7], b[9], b[10], b[11]}, {b[9], b[10], b[11], b[12]}, {b[12], b[13], b[14], b[15]}};
        for (byte[] carried : layouts) {
            Range r = range(ipv4(carried));
            if (r == Range.LOOPBACK || r == Range.LINK_LOCAL || r == Range.METADATA) return r;
        }
        return Range.TRANSLATED;
    }

    /** Whether an IPv6 address opens with {@code prefix}; an IPv4 one never does. */
    private static boolean startsWith(byte[] address, byte[] prefix) {
        return address.length == 16 && Arrays.equals(address, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static InetAddress ipv4(byte[] four) {
        try {
            return InetAddress.getByAddress(four);
        } catch (UnknownHostException impossible) {   // only for a length other than 4 or 16
            throw new IllegalStateException(impossible);
        }
    }

    /** An address literal: parsed, never looked up. */
    private static InetAddress literal(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
