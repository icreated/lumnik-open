package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i).*(key|token|secret|password|auth).*");
    /** The scheme word of an Authorization value: {@code Bearer}, {@code Basic}, … */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    /**
     * Below this length a bare value in a body that is not ingested is replaced only where it stands alone, so an
     * error text stays readable; oauth2 refuses a token this short ({@code OAuth2Grant}).
     */
    static final int MIN_LENGTH = 8;
    private static final String REDACTED = "<redacted>";
    /** The marker in markup, where {@code <redacted>} would be a tag; a parser reads it back as {@link #REDACTED}. */
    private static final String REDACTED_MARKUP = "&lt;redacted&gt;";

    private SecretRedactor() {}

    public static String redactHeader(String name, String value) {
        if (!SECRET.matcher(name).matches()) return value;
        if (value != null && value.startsWith("Bearer ")) return "Bearer <redacted>";
        if (value != null && value.startsWith("Basic "))  return "Basic <redacted>";
        return "<redacted>";
    }

    /**
     * {@code resp} without the credentials {@code signed} carried, wherever its body repeats them — a 401 or a
     * GraphQL error that echoes the Authorization header, a debug page that prints its query (#1393 security
     * review). Applied where a data-plane response is born ({@code RestHttpClient.exchange}), so the validators,
     * the retries, a run's failure text, a pre-flight's sample and the ingested rows all see the same body.
     *
     * <p>No length tells a bare credential from a value of the rows: a token {@code 12345678} and a
     * {@code "customer_id":12345678} are the same text (#1394 reviews). So a body to ingest — a 2xx its validator
     * accepts — loses only the structured forms, which no row writes: the whole {@code Authorization} value
     * ({@code Bearer …} → {@code Bearer <redacted>}) and a query's {@code name=value}. Its data is never rewritten.
     * A body that is not ingested — not 2xx, or refused by its validator (a GraphQL {@code errors}, a SOAP Fault) —
     * loses the bare value too: as sent, JSON-escaped ({@code \/}) and URL-encoded, a Basic pair decoded as well,
     * one under {@link #MIN_LENGTH} characters where it stands alone. In a markup body (XML, a SOAP envelope, an HTML
     * page) the marker is written {@code &lt;redacted&gt;}, which a parser reads back as {@code <redacted>}: the
     * body stays well-formed. The response is rebuilt only when something
     * was replaced, its JSON read again.
     */
    public static RestResponse withoutEchoedCredentials(RestResponse resp, RestRequest signed, boolean ingestible) {
        String body = resp.body();
        if (body == null || body.isEmpty()) return resp;
        String marker = isMarkup(body) ? REDACTED_MARKUP : REDACTED;
        String out = body;
        for (Map.Entry<String, String> form : longestFirst(structured(signed, marker))) {
            out = out.replace(form.getKey(), form.getValue());
        }
        if (!ingestible) {
            for (Map.Entry<String, String> form : longestFirst(bare(signed))) {
                out = form.getKey().length() >= MIN_LENGTH ? out.replace(form.getKey(), marker)
                    : Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(form.getKey()) + "(?![A-Za-z0-9])")
                        .matcher(out).replaceAll(Matcher.quoteReplacement(marker));
            }
        }
        return out.equals(body) ? resp : RestResponse.of(resp.status(), resp.headers(), out);
    }

    /** The forms no row writes, whatever their length: {@code Bearer …} and a query's {@code name=value}. */
    static Map<String, String> structured(RestRequest signed, String marker) {
        Map<String, String> found = new HashMap<>();
        signed.headers().forEach((name, value) -> {
            if (!isCredential(name, value)) return;
            int space = value.indexOf(' ');
            if (space > 0 && SCHEME.matcher(value.substring(0, space)).matches()) {
                String redacted = value.substring(0, space) + " " + marker;
                found.put(value, redacted);
                found.put(value.replace("/", "\\/"), redacted);
            }
        });
        signed.queryParams().forEach((name, value) -> {
            if (!isCredential(name, value)) return;
            found.put(name + "=" + value, name + "=" + marker);
            found.put(encode(name) + "=" + encode(value), encode(name) + "=" + marker);
        });
        return found;
    }

    /** The bare values, in the forms a source writes back: as sent, JSON-escaped, URL-encoded; a Basic pair decoded. */
    static Map<String, String> bare(RestRequest signed) {
        Map<String, String> found = new HashMap<>();
        signed.headers().forEach((name, value) -> {
            if (!isCredential(name, value)) return;
            int space = value.indexOf(' ');
            boolean schemed = space > 0 && SCHEME.matcher(value.substring(0, space)).matches();
            String credential = schemed ? value.substring(space + 1).strip() : value;
            forms(credential, found);
            if (schemed && value.substring(0, space).equalsIgnoreCase("basic")) {
                try {
                    String pair = new String(Base64.getDecoder().decode(credential), StandardCharsets.UTF_8);
                    forms(pair, found);
                    forms(pair.substring(pair.indexOf(':') + 1), found);
                } catch (IllegalArgumentException notBase64) {
                    // a Basic value that is not base64 is only the value itself
                }
            }
        });
        signed.queryParams().forEach((name, value) -> {
            if (isCredential(name, value)) forms(value, found);
        });
        return found;
    }

    /**
     * Whether the body is markup (XML, a SOAP envelope, an HTML page): its first character past whitespace and a BOM
     * is {@code <}. Read by index — the body is a whole page, on every response.
     */
    private static boolean isMarkup(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\uFEFF' && !Character.isWhitespace(c)) return c == '<';
        }
        return false;
    }

    private static boolean isCredential(String name, String value) {
        return value != null && !value.isBlank() && SECRET.matcher(name).matches();
    }

    private static void forms(String credential, Map<String, String> found) {
        if (credential.isBlank()) return;
        for (String form : List.of(credential, credential.replace("/", "\\/"), encode(credential))) {
            found.put(form, REDACTED);
        }
    }

    /** Longest first, so no shorter form cuts into a longer one. */
    private static List<Map.Entry<String, String>> longestFirst(Map<String, String> forms) {
        return forms.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
            .toList();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
