package io.lumnik.hub.connector.rest;

import io.lumnik.common.error.Throwables;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.util.Comparator;

/**
 * What to say about an outbound call that threw: the failure's kind and the exception's class,
 * never its message. A JDK message may quote the target URL — whole, without its userinfo, or its
 * path alone — and that URL is configured by an integrator: a webhook URL is often the credential
 * itself, a REST {@code base_url} legally carries userinfo or a token (#1162, #1164). Every word
 * here is fixed, so nothing depends on how the JDK words a message. {@link Urls#forDisplay} stays
 * for the places that show a URL on purpose.
 *
 * <p>One classifier for every caller — the webhook delivery, the REST probe and the REST
 * pre-flight — so the categories cannot drift apart.
 */
public final class OutboundFailure {

    private OutboundFailure() {}

    public static String describe(Throwable t) {
        return kind(t).label + " (" + t.getClass().getSimpleName() + ")";
    }

    /**
     * Whether the handshake failed on the certificate — an untrusted CA, a name that does not match (#1609). Not a
     * network blip: it fails the same way on every retry.
     */
    public static boolean isCertificateRefusal(Throwable t) {
        return kind(t) == Kind.CERTIFICATE_REFUSED;
    }

    /** A failure's kind, most specific first: the enum's order is the priority across the cause chain. */
    private enum Kind {
        HOST_NOT_RESOLVED("host not resolved"), INVALID_URL("invalid URL"), CONNECT_TIMEOUT("connect timeout"),
        TIMEOUT("timeout"), CERTIFICATE_REFUSED("certificate refused"), TLS("TLS failure"), CONNECTION("connection failed"), IO("I/O error"), OTHER("other");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * The most specific kind found anywhere in the cause chain: how the JDK nests its exceptions is
     * not ours. The walk is {@link Throwables#causeChain} — bounded, cycle-safe, and written once.
     */
    private static Kind kind(Throwable t) {
        return Throwables.causeChain(t).stream()
            .map(OutboundFailure::kindOf)
            .min(Comparator.naturalOrder())
            .orElse(Kind.OTHER);
    }

    /**
     * One link's kind. The compiler refuses a case placed after one of its supertypes, so the type
     * hierarchy cannot be got wrong here: UnresolvedAddressException IS an IllegalArgumentException.
     */
    private static Kind kindOf(Throwable t) {
        return switch (t) {
            case UnknownHostException u -> Kind.HOST_NOT_RESOLVED;
            case UnresolvedAddressException u -> Kind.HOST_NOT_RESOLVED;
            case IllegalArgumentException i -> Kind.INVALID_URL;   // URI.create, HttpRequest.newBuilder
            case HttpConnectTimeoutException c -> Kind.CONNECT_TIMEOUT;
            case HttpTimeoutException h -> Kind.TIMEOUT;
            case CertificateException c -> Kind.CERTIFICATE_REFUSED;   // under a handshake the trust refused (#1609)
            case SSLException s -> Kind.TLS;
            case ConnectException c -> Kind.CONNECTION;
            case IOException io -> Kind.IO;
            default -> Kind.OTHER;
        };
    }
}
