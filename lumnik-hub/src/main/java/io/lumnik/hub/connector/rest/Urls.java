package io.lumnik.hub.connector.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;

/** Small URL helpers shared by the REST/GraphQL request builders. */
public final class Urls {

    private Urls() {}

    /**
     * Joins {@code base} and {@code path} with exactly one slash between them: any trailing slashes on
     * {@code base} and any leading slashes on the (trimmed) {@code path} are collapsed, so neither
     * {@code https://hostpath} nor {@code https://host//path} can occur — even for a pathological
     * {@code path} like {@code "//items"}. A blank/null {@code path} yields the base (trailing slashes
     * stripped). {@code path} is not validated upstream, so this is the single point that normalizes
     * it; {@code base} must not be null.
     */
    public static String join(String base, String path) {
        Objects.requireNonNull(base, "base must not be null");
        String b = base.replaceFirst("/+$", "");           // drop any trailing slashes
        if (path == null || path.isBlank()) {
            return b;
        }
        return b + "/" + path.strip().replaceFirst("^/+", "");   // trim + exactly one separator
    }

    /**
     * Why {@code url} is not usable as a base URL, or empty when it is: it must parse and carry
     * both a scheme and a host.
     *
     * <p>It lives here rather than in the validator because both directions need it and only one
     * is allowed to exist: the manifest door ({@code ConfigDefValidators.isUrl}) has always stated
     * this rule, while the runtime's own {@code base_url} check tested presence alone. The flat
     * connector CRUD persists a config without connector-type validation, so a row holding
     * {@code "not a url"} reached {@code URI.create} inside the HTTP client and raised an
     * {@code IllegalArgumentException} — neither a ConnectorException nor an IOException, so it
     * escaped every catch on the way out and surfaced as {@code Internal Server Error}.
     *
     * <p>A private or loopback host is deliberately fine: an on-prem ERP behind a literal IP is
     * the normal case, as {@code PaginationUrlGuard} already records.
     *
     * <p>The answer names the reason and the position, never the URL: it is rendered into a 400
     * body, a CLI line and a log, and a {@code base_url} legally carries userinfo.
     */
    public static Optional<String> whyNotAbsolute(String url) {
        if (url == null || url.isBlank()) return Optional.of("must not be blank");
        try {
            URI u = new URI(url);
            return (u.getScheme() == null || u.getHost() == null)
                ? Optional.of("must be an absolute URL (scheme + host)")
                : Optional.empty();
        } catch (URISyntaxException notAUrl) {
            // getReason() + getIndex(), never getMessage(): the latter appends the offending string
            // in full, and a base_url legally carries userinfo — operators do paste tokens into one.
            // This refusal travels to a CLI, a 400 body and a log, so it says WHERE and WHAT, never
            // the value. (The same reason class-22 SQLSTATEs stay silent while 21/42/53/54/57 speak.)
            return Optional.of("must be a valid URL: " + notAUrl.getReason()
                + " at index " + notAUrl.getIndex());
        }
    }

    /**
     * A URL — or a bare path — stripped of everything that can carry a credential, for a message
     * or a log line:
     * userinfo — of an absolute URL or a protocol-relative {@code //host} one — becomes {@code …@},
     * and the query string and fragment are dropped — an OAuth
     * implicit flow returns its {@code access_token} in the fragment, which is as visible in a log
     * line as a query parameter.
     *
     * <p>A string operation, not a {@link URI} one, on purpose — its caller is the refusal for a
     * URL that could not be parsed in the first place, so anything that must parse would throw
     * exactly when it is needed.
     *
     * <p>Host and path survive: without them a refusal cannot say WHICH source it is about, and an
     * error nobody can act on is its own kind of defect.
     *
     * <p>A caller-supplied endpoint path goes through here too. It legitimately carries a query
     * ({@code /partners?type=customer}), so it can just as legitimately carry a token, and the
     * userinfo rule is anchored on {@code ://} precisely so a path without a scheme passes it by.
     */
    public static String forDisplay(String url) {
        if (url == null) return null;
        String addressOnly = url.split("[?#]", 2)[0];
        // The authority opens after any "://" — a compound scheme (jdbc:postgresql://) or text before
        // the URL included — or after a leading "//": a protocol-relative link has a userinfo too
        // (#1167 review). A "//" further into a path without a scheme is not an authority. Greedy to
        // the LAST '@' before the path: a password may hold an unescaped '@', and stopping at the
        // first one left the rest of it on show. Where URI reads a host, its userinfo holds no '@',
        // so this strips exactly what URI calls the userinfo.
        return addressOnly.replaceFirst("(?<=://)[^/]*@", "…@")
            .replaceFirst("^(\\s*//)[^/]*@", "$1…@");
    }
}
