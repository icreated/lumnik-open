package io.lumnik.hub.security;

import io.lumnik.hub.view.MapTiles;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.net.HostAndPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

/**
 * The PWA's Content-Security-Policy (#1994). It keeps its tokens in sessionStorage, so a script injected into
 * {@code /app/} would be a token theft with no second line: the page runs only the hub's own scripts, cannot be
 * framed by another origin, and connects only to its hub and to that hub's Keycloak. Written per request because the
 * Keycloak source is the page's own host on {@link #KEYCLOAK_PORT} — {@code config.mjs}'s {@code kcBase} without the
 * TLS façade — which no static header can name ({@code http://*:8180} would let a script send a token to any host on
 * that port). Behind the façade Keycloak is the same origin, and {@code 'self'} covers it. The map's tile server
 * (#2057, {@link MapTiles}) is an image source only when the operator named one.
 */
@ApplicationScoped
public class PwaContentSecurityPolicy {

    /** kcBase's Keycloak port (PwaContentSecurityPolicyTest holds the two together). */
    static final int KEYCLOAK_PORT = 8180;

    /** A DNS name or an IPv4 address; an IPv6 literal is checked apart. Anything else names no second source. */
    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    @Inject MapTiles mapTiles;

    void register(@Observes Filters filters) {
        String tiles = mapTiles.cspSource().orElse(null);
        filters.register(ctx -> {
            if (isPwa(ctx.normalizedPath())) {
                HostAndPort authority = ctx.request().authority();
                ctx.response().putHeader("Content-Security-Policy", policy(authority == null ? null : authority.host(), tiles));
            }
            ctx.next();
        }, 100);
    }

    static boolean isPwa(String path) {
        return path != null && (path.equals("/app") || path.startsWith("/app/"));
    }

    static String policy(String host) {
        return policy(host, null);
    }

    static String policy(String host, String tiles) {
        return "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:"
            + (tiles == null ? "" : " " + tiles) + "; connect-src 'self'"
            + keycloakSource(host)
            + "; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";
    }

    private static String keycloakSource(String host) {
        if (host == null) return "";
        if (HOSTNAME.matcher(host).matches()) return " http://" + host + ":" + KEYCLOAK_PORT;
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return host.contains(":") && IPV6.matcher(literal).matches()
            ? " http://[" + literal + "]:" + KEYCLOAK_PORT : "";
    }
}
