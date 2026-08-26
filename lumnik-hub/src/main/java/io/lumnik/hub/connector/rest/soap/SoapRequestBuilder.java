package io.lumnik.hub.connector.rest.soap;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.Urls;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a SOAP 1.1 request: wraps the (placeholder-substituted) body template in a
 * {@code <soap:Envelope>}, posts to {@code base_url + path} with {@code Content-Type: text/xml} and a
 * {@code SOAPAction} header. The optional header template becomes {@code <soap:Header>} content.
 * Mirrors {@code GraphQlRequestBuilder} (a request-shape on the HTTP engine).
 *
 * <p>Placeholders ({@code {{...}}}): {@code page_size} / {@code cursor} are the pagination values;
 * {@code secret:NAME} / {@code env:NAME} resolve a credential via the supplied resolver. An
 * unresolved placeholder fails fast (never sent literally; never logged).
 */
public final class SoapRequestBuilder {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    // Hyphens are valid in env-var names (e.g. MY-TOKEN, AWS-SECRET-KEY) — include them so the
    // fail-fast "unresolved placeholder" guard applies instead of sending the literal on the wire.
    private static final Pattern TOKEN    = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_:\\-]+)\\s*}}");
    // Broad sentinel: catches any {{...}} that TOKEN didn't match (e.g. a dot in the name).
    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[^}]*}}");

    private SoapRequestBuilder() {}

    public static RestRequest render(String baseUrl, String path, String soapAction,
                                     String headerTemplate, String bodyTemplate,
                                     String pageSize, String cursor, UnaryOperator<String> secrets) {
        if (bodyTemplate == null) {
            throw new IllegalStateException("SOAP body template must not be null — the validator requires 'body'");
        }
        String body = subst(bodyTemplate, pageSize, cursor, secrets);
        StringBuilder env = new StringBuilder()
            .append("<soap:Envelope xmlns:soap=\"").append(SOAP_NS).append("\">");
        if (headerTemplate != null && !headerTemplate.isBlank()) {
            env.append("<soap:Header>").append(subst(headerTemplate, pageSize, cursor, secrets)).append("</soap:Header>");
        }
        env.append("<soap:Body>").append(body).append("</soap:Body></soap:Envelope>");

        String url = Urls.join(baseUrl, path == null ? "" : path);
        return RestRequest.post(url, env.toString())
            .withHeader("Content-Type", "text/xml; charset=utf-8")
            // SOAP 1.1 requires the SOAPAction header (an empty quoted string is valid).
            .withHeader("SOAPAction", "\"" + (soapAction == null ? "" : soapAction) + "\"");
    }

    /**
     * Substitute {@code {{...}}} tokens; fail fast on an unknown/unresolved one.
     *
     * <p>Resolving a token is its own concern ({@link #resolveToken}); this method is the scan and
     * the never-echo guard that refuses anything which survived it.
     */
    private static String subst(String template, String pageSize, String cursor, UnaryOperator<String> secrets) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = resolveToken(m.group(1), pageSize, cursor, secrets);
            m.appendReplacement(out, Matcher.quoteReplacement(escapeXml(value)));
        }
        m.appendTail(out);
        String result = out.toString();
        // Post-substitution guard: any {{...}} that TOKEN didn't match (e.g. a dot in the name) would
        // survive here and be sent on the wire — fail fast to honour the never-echo contract.
        Matcher leftover = LEFTOVER.matcher(result);
        if (leftover.find()) {
            throw new IllegalStateException("unresolved placeholder in SOAP template: " + leftover.group());
        }
        return result;
    }

    /**
     * The four token shapes, each failing fast rather than sending something wrong on the wire: an
     * absent pagination value would otherwise NPE inside quoteReplacement, and an unresolved secret
     * would go out as the literal {@code {{secret:NAME}}}.
     */
    private static String resolveToken(String token, String pageSize, String cursor,
                                       UnaryOperator<String> secrets) {
        if ("page_size".equals(token)) return required(pageSize, token);
        if ("cursor".equals(token))    return required(cursor, token);
        if (token.startsWith("secret:") || token.startsWith("env:")) {
            String name = token.substring(token.indexOf(':') + 1);
            // Guard the resolver itself: a null resolver must fail closed, not NPE.
            String value = secrets == null ? null : secrets.apply(name);
            if (value == null) {
                // Fail closed: do not send "{{secret:NAME}}" literally, and do not echo any value.
                throw new IllegalStateException("unresolved secret/env placeholder: " + name);
            }
            return value;
        }
        throw new IllegalStateException("unknown placeholder: " + token);
    }

    private static String required(String value, String token) {
        if (value == null) {
            throw new IllegalStateException("missing pagination value for placeholder: " + token);
        }
        return value;
    }

    /** Escapes XML text-content special characters to prevent malformed XML and injection. */
    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
