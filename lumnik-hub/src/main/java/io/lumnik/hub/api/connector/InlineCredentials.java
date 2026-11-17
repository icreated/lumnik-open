package io.lumnik.hub.api.connector;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.rest.auth.SecretRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connector's inline credentials (#2003): the values a manifest may write in plain text instead of naming a secret,
 * which the hub stores in the connector's config. A read door serves the config {@link #redacted}; a write door takes a
 * config read back that way through {@link #restored}, so the {@code get → edit → apply} loop keeps them.
 *
 * <p>Judged by position, never by a key's name alone (a column may be called {@code password}), at the config's root
 * or under its {@code connection} block, from which a connector also runs:
 * <ul>
 *   <li>{@code password} (jdbc) and {@code auth.token_value} (REST), whatever their type;</li>
 *   <li>the value of a static header ({@code headers[i]}) whose name designates a credential
 *       ({@link SecretRedactor#isSecretHeaderName});</li>
 *   <li>in {@code url}, {@code base_url}, {@code auth.token_url}, {@code auth.login_url}, {@code auth.prime_url} and an
 *       endpoint's path: the password of a userinfo, Oracle's {@code user/password@}, and a parameter named as a
 *       credential ({@code password}, {@code sslpassword}, {@code pwd}, {@code api_key}, {@code access_token}, …).</li>
 * </ul>
 * A {@code *_env} key names a secret and is never touched. In a URL the marker is {@link #URL_MARKER}, the same text
 * percent-encoded, so the URL stays one a validator accepts.
 */
public final class InlineCredentials {

    public static final String MARKER = "<redacted>";
    public static final String URL_MARKER = "%3Credacted%3E";

    private static final Set<String> CREDENTIAL_PATHS = Set.of("password", "auth.token_value");
    private static final Set<String> URL_PATHS = Set.of("url", "base_url", "auth.token_url", "auth.login_url",
        "auth.prime_url");
    private static final Pattern HEADER_PATH = Pattern.compile("headers\\[\\d+]");
    /**
     * The places a credential may go: one read back redacted is restored only while each of them is the one it was
     * stored with, so a changed address never receives a credential its editor did not write.
     */
    private static final Set<String> DESTINATION_KEYS =
        Set.of("url", "base_url", "token_url", "login_url", "prime_url", "host", "port", "endpoint", "bootstrap_servers");
    /** The parameter names of a credential in a URL: named exactly, never a word that merely contains one. */
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
        "(?i)(?:[a-z]*password|passwd|pwd|pass|api[_-]?key|apikey|access[_-]?token|auth[_-]?token|token|secret"
            + "|client[_-]?secret|key|sig|signature)");
    /**
     * A parameter and its value, which runs as its separator says: after {@code ?} or {@code &} to the next {@code &};
     * after {@code ;} (SQL Server) to the next {@code ;}, or a braced value whole; after {@code (} (MySQL) to the
     * next {@code )}.
     */
    private static final Pattern PARAMETER = Pattern.compile(
        "([?&])([A-Za-z0-9_.-]+)=([^&]*)|(;)([A-Za-z0-9_.-]+)=(\\{(?:[^}]|}})*}|[^;]*)|(\\()([A-Za-z0-9_.-]+)=([^)]*)");

    private InlineCredentials() {
    }

    /** A copy of {@code config} with every inline credential replaced by the marker; {@code config} is untouched. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redacted(Map<String, Object> config) {
        return config == null ? null : (Map<String, Object>) redact("", config);
    }

    private static Object redact(String path, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), redact(child(path, String.valueOf(k)), v)));
            if (isSecretHeader(path, copy) && present(copy.get("value"))) copy.put("value", MARKER);
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) copy.add(redact(path + "[" + i + "]", list.get(i)));
            return copy;
        }
        String at = position(path);
        if (CREDENTIAL_PATHS.contains(at) && present(value)) return MARKER;
        if (URL_PATHS.contains(at) && value instanceof String url) return redactedUrl(url);
        return value;
    }

    /** A path as a credential position: the {@code connection} block is read as the root. */
    private static String position(String path) {
        return path.startsWith("connection.") ? path.substring("connection.".length()) : path;
    }

    /** A static header whose name designates a credential. */
    private static boolean isSecretHeader(String path, Map<?, ?> header) {
        return HEADER_PATH.matcher(position(path)).matches() && header.get("name") instanceof String name
            && SecretRedactor.isSecretHeaderName(name);
    }

    private static boolean present(Object value) {
        return value != null && !(value instanceof Map) && !(value instanceof List) && !String.valueOf(value).isBlank();
    }

    private static String child(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /**
     * An endpoint's path, or any URL, without its credentials: a parameter named as one, Oracle's
     * {@code user/password@}, and a userinfo's password — the authority's userinfo, or, when the authority holds no
     * {@code @}, everything up to the last {@code @} before the query that a {@code :} shows to be one (a password
     * holding {@code /}). A userinfo with no {@code :} is a token on its own, and goes whole.
     */
    public static String redactedUrl(String url) {
        if (url == null) return null;
        String redacted = PARAMETER.matcher(url).replaceAll(m -> {
            int g = m.group(1) != null ? 1 : m.group(4) != null ? 4 : 7;
            return Matcher.quoteReplacement(CREDENTIAL_PARAMETER.matcher(m.group(g + 1)).matches()
                ? m.group(g) + m.group(g + 1) + "=" + URL_MARKER : m.group());
        });
        int query = redacted.indexOf('?');
        int end = query < 0 ? redacted.length() : query;
        if (redacted.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:thin:")) {
            int start = "jdbc:oracle:thin:".length();
            int at = redacted.lastIndexOf('@', end - 1);
            int slash = redacted.indexOf('/', start);
            if (at > start && slash > start && slash < at) {
                redacted = redacted.substring(0, slash + 1) + URL_MARKER + redacted.substring(at);
            }
            return redacted;
        }
        int scheme = redacted.indexOf("//");
        if (scheme < 0 || scheme >= end) return redacted;
        int start = scheme + 2;
        int slash = redacted.indexOf('/', start);
        String authority = redacted.substring(start, slash < 0 || slash > end ? end : slash);
        int last = redacted.lastIndexOf('@', end - 1);
        if (last < start) return redacted;
        int at;
        if (authority.indexOf('@') >= 0) {
            // a userinfo: up to the last '@' when a ':' shows a password running past the first '/', else the authority's
            at = redacted.substring(start, last).indexOf(':') >= 0 ? last : start + authority.lastIndexOf('@');
        } else {
            // no '@' before the first '/': a userinfo only when what follows its ':' is no port (a password holding '/')
            int portColon = authority.indexOf(':');
            if (portColon < 0 || authority.substring(portColon + 1).matches("\\d*")) return redacted;
            at = last;
        }
        int colon = redacted.substring(start, at).indexOf(':');
        int from = colon >= 0 ? start + colon + 1 : start;
        return redacted.substring(0, from) + URL_MARKER + redacted.substring(at);
    }

    /** Whether two configs send to the same places. */
    public static boolean sameDestinations(Map<String, Object> a, Map<String, Object> b) {
        return destinations("", redacted(a), new LinkedHashMap<>()).equals(destinations("", redacted(b), new LinkedHashMap<>()));
    }

    /** {@link #restored(Map, Map, boolean)}, the destinations judged within the config. */
    public static Map<String, Object> restored(Map<String, Object> incoming, Map<String, Object> stored) {
        return restored(incoming, stored, false);
    }

    /**
     * {@code incoming} with each credential read back redacted replaced by the stored one: when the stored value,
     * redacted, reads exactly as the incoming one, a header (matched by name) or the auth block is unchanged around it,
     * and the request sends to the same places — this config's destinations, and {@code destinationChanged} false when
     * the connector they hang on changed address in the same request. Anything else carrying a marker where a
     * credential lives is refused; the marker text elsewhere is a value like any other.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> restored(Map<String, Object> incoming, Map<String, Object> stored,
                                               boolean destinationChanged) {
        if (incoming == null) return null;
        Map<String, Object> storedRedacted = redacted(stored);
        if (carriesMarker("", incoming)) {
            if (destinationChanged) throw destinationChanged("the connector's address");
            Map<String, Object> was = destinations("", storedRedacted, new LinkedHashMap<>());
            Map<String, Object> is = destinations("", redacted(incoming), new LinkedHashMap<>());
            for (String path : was.keySet()) if (!Objects.equals(was.get(path), is.get(path))) throw destinationChanged(path);
            for (String path : is.keySet()) if (!was.containsKey(path)) throw destinationChanged(path);
        }
        return (Map<String, Object>) restore("", incoming, stored, storedRedacted);
    }

    /** An endpoint's path read back redacted, restored as {@link #restored} restores a URL. */
    public static String restoredPath(String incoming, String stored, boolean destinationChanged) {
        if (incoming == null || !incoming.contains(URL_MARKER)) return incoming;
        if (destinationChanged) throw destinationChanged("the connector's address");
        if (incoming.equals(redactedUrl(stored))) return stored;
        throw new ConfigValidationException("path: " + URL_MARKER + " stands for the value the hub holds there, and it "
            + "holds none matching this one — write the value itself");
    }

    private static ConfigValidationException destinationChanged(String what) {
        return new ConfigValidationException(what + " changed: " + MARKER + " is restored only for the destination it "
            + "was stored with — write the credentials again for the new one");
    }

    private static boolean carriesMarker(String path, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (isMarkedHeader(path, map)) return true;
            return map.entrySet().stream().anyMatch(e -> carriesMarker(child(path, String.valueOf(e.getKey())), e.getValue()));
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) if (carriesMarker(path + "[" + i + "]", list.get(i))) return true;
            return false;
        }
        return isMarkedCredential(path, value);
    }

    private static boolean isMarkedCredential(String path, Object value) {
        String at = position(path);
        return value instanceof String s && (CREDENTIAL_PATHS.contains(at) && s.equals(MARKER)
            || URL_PATHS.contains(at) && s.contains(URL_MARKER));
    }

    private static boolean isMarkedHeader(String path, Map<?, ?> header) {
        return isSecretHeader(path, header) && MARKER.equals(header.get("value"));
    }

    /** Every destination key's value, by its path. */
    private static Map<String, Object> destinations(String path, Object value, Map<String, Object> into) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String at = child(path, String.valueOf(k));
                if (DESTINATION_KEYS.contains(String.valueOf(k)) && !(v instanceof Map) && !(v instanceof List)) into.put(at, v);
                else destinations(at, v, into);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) destinations(path + "[" + i + "]", list.get(i), into);
        }
        return into;
    }

    private static Object restore(String path, Object in, Object stored, Object storedRedacted) {
        if (in instanceof Map<?, ?> map) {
            // The auth block holding a marked credential is restored whole, or not at all: its kind decides where the
            // credential goes.
            if ("auth".equals(position(path)) && carriesMarker(path, map)) {
                if (map.equals(storedRedacted)) return stored;
                throw refused(path, "the block it was read from, unchanged — write the value itself to change anything beside it");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                copy.put(key, restore(child(path, key), v,
                    stored instanceof Map<?, ?> s ? s.get(k) : null,
                    storedRedacted instanceof Map<?, ?> r ? r.get(k) : null));
            });
            return copy;
        }
        if (in instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String at = path + "[" + i + "]";
                Object item = list.get(i);
                if (item instanceof Map<?, ?> header && isMarkedHeader(at, header)) {
                    copy.add(storedHeader(at, header, stored, storedRedacted));   // by its name: headers may move
                } else {
                    copy.add(restore(at, item,
                        stored instanceof List<?> s && i < s.size() ? s.get(i) : null,
                        storedRedacted instanceof List<?> r && i < r.size() ? r.get(i) : null));
                }
            }
            return copy;
        }
        if (isMarkedCredential(path, in)) {
            if (in.equals(storedRedacted)) return stored;
            throw refused(path, "the value the hub holds there, and it holds none matching this one — write the value itself");
        }
        return in;
    }

    /** The stored header of the same name, unchanged around its value; refused otherwise. */
    private static Object storedHeader(String path, Map<?, ?> header, Object stored, Object storedRedacted) {
        if (stored instanceof List<?> s && storedRedacted instanceof List<?> r) {
            for (int j = 0; j < r.size(); j++) {
                if (r.get(j) instanceof Map<?, ?> was && Objects.equals(was.get("name"), header.get("name")) && was.equals(header)) {
                    return s.get(j);
                }
            }
        }
        throw refused(path, "a header of the same name, unchanged — write the value itself");
    }

    private static ConfigValidationException refused(String path, String what) {
        return new ConfigValidationException(path + ": " + MARKER + " is restored only for " + what);
    }
}
