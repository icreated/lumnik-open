package io.lumnik.hub.manifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one description of an external link template (#1050), for every manifest kind that links out
 * (kind:View today; kind:SearchIndex next, #1051). A template is TEXT the integrator writes once:
 * {@code https://} only, the host spelled out, and {@code {name}} placeholders only after the first
 * {@code /}, {@code ?} or {@code #}. What a placeholder is replaced with — the clicked row's, or hit's,
 * value, percent-encoded — is the client's business; nothing here evaluates anything.
 *
 * <p>What differs between kinds is only the set of names a placeholder may use, passed in.
 */
public final class LinkTemplate {

    public static final int MAX_URL_LENGTH = 2048;

    /** A placeholder: {@code {name}}, the name being a plain identifier. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern AUTHORITY = Pattern.compile("[A-Za-z0-9.-]+(:[0-9]{1,5})?");

    private LinkTemplate() {}

    /** The names a template mentions, in order. */
    public static List<String> placeholders(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Every {placeholder} names one of {@code allowed}, and no stray brace is left.
     *
     * @param allowedWhere how the refusal names the allowed set, e.g. {@code spec.columns}
     */
    public static void checkPlaceholders(String template, String path, Collection<String> allowed, String allowedWhere) {
        for (String name : placeholders(template))
            if (!allowed.contains(name))
                throw new IllegalArgumentException(path + ": {" + name + "} is not one of " + allowedWhere);
        String rest = PLACEHOLDER.matcher(template).replaceAll("");
        if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0)
            throw new IllegalArgumentException(path + ": a '{' or '}' that is not a {name} placeholder");
    }

    /** The template, stripped, once it is a well-formed external link; refuses with a message addressed by {@code path}. */
    public static String externalUrl(String raw, String path, Collection<String> allowed, String allowedWhere) {
        String url = raw.strip();
        if (url.length() > MAX_URL_LENGTH)
            throw new IllegalArgumentException(path + ": at most " + MAX_URL_LENGTH + " characters");
        if (url.chars().anyMatch(c -> c <= 0x20 || c == 0x7f))
            throw new IllegalArgumentException(path + ": no whitespace or control character");
        if (!url.startsWith("https://"))
            throw new IllegalArgumentException(path + ": an external link is https://… — nothing else is followed");
        String rest = url.substring("https://".length());
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        if (!AUTHORITY.matcher(rest.substring(0, end)).matches())
            throw new IllegalArgumentException(path + ": the host must be written out in full (letters, digits,"
                + " dots, an optional port) — no credentials and no {placeholder} before the first '/'");
        checkPlaceholders(url, path, allowed, allowedWhere);
        return url;
    }
}
