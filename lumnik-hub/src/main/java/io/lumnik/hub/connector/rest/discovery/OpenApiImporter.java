package io.lumnik.hub.connector.rest.discovery;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.rest.Urls;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Drafts a {@code rest-generic} manifest from an OpenAPI spec: one endpoint per GET path.
 *
 * <p>The draft writes what the spec SAYS and nothing it does not. Auth comes from the security
 * schemes; paging only when the parameters and the response shape both say how pages work. A
 * guess is worse than a gap here: a page draft that starts at 1 on a source numbered from 0, or an
 * offset limit above what the source serves, ends the run early and reports success. So a path the
 * draft cannot page correctly is kept, commented out, under a line naming what to declare.
 */
@ApplicationScoped
public class OpenApiImporter {

    private static final String PAGINATION_DOCS = "https://docs.lumnik.io/connectors/rest/pagination/";
    /** A response property the draft can name in a dotted JSONPath without quoting. */
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Query parameters that carry a page size next to {@code page}, in the order they are looked for. */
    private static final List<String> SIZE_PARAMS = List.of("size", "page_size", "pageSize", "per_page", "limit");
    /** An HTTP token (RFC 9110 tchar): what a header name can be, and all an API key name may be here. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public String importFromUrl(String url, String name, List<String> tags) {
        // What a URL holding no spec actually does, measured rather than assumed: OpenAPIV3Parser
        // does not answer null — it raises a NullPointerException from inside itself (it iterates
        // its own null message list) for an unreachable host AND for a reachable page that is not a
        // spec. Escaping here, that reached the operator as Internal Server Error plus an error id,
        // with the URL they mistyped nowhere in the answer. Naming it is this class's business:
        // what the failure means is knowledge the caller does not have.
        OpenAPI api;
        try {
            api = new OpenAPIV3Parser().read(url);
        } catch (RuntimeException unreadable) {
            throw new ConnectorException(unreadableSpec(url), unreadable);
        }
        if (api == null) throw new ConnectorException(unreadableSpec(url));   // belt: not observed
        return toManifest(api, name, tags);
    }

    /**
     * The URL is shown through {@link Urls#forDisplay}: a spec URL legally carries userinfo, and a
     * signed one carries a token in its query, either of which would otherwise land in a 502 body
     * and a hub log. Host and path survive, because a refusal that cannot say WHICH url it is about
     * helps nobody.
     */
    private static String unreadableSpec(String url) {
        return "no OpenAPI spec could be read at " + Urls.forDisplay(url)
            + " — unreachable, not a spec, or not parseable";
    }

    public String importFromYaml(String yaml, String name, List<String> tags) {
        OpenAPI api = new OpenAPIV3Parser().readContents(yaml).getOpenAPI();
        if (api == null) throw new ConnectorException("the supplied document is not a parseable OpenAPI spec");
        return toManifest(api, name, tags);
    }

    /** One GET path as the draft will write it. */
    private record Draft(String path, String slug, Auth auth, Paging paging) {}

    private String toManifest(OpenAPI api, String name, List<String> tags) {
        // metadata.name keeps the human label; safeName is the sanitized identifier used wherever the
        // name forms a SQL/env token (table prefix, secret names) so spaces/unicode can't break the DDL.
        String safeName = Identifiers.slug(name, "api");
        String envPrefix = safeName.toUpperCase(Locale.ROOT);
        // De-dup within this import so DDD-collision paths (Person_1/Person_2) and Unicode renames
        // can't yield duplicate or blank endpoint names / target tables.
        Identifiers.Unique endpointNames = new Identifiers.Unique();
        List<Draft> drafts = new ArrayList<>();
        api.getPaths().forEach((path, item) -> {
            Operation get = item.getGet();
            if (get == null) return;
            String slug = endpointNames.take(Identifiers.slug(path, "endpoint"));
            drafts.add(new Draft(path, slug, auth(api, get, envPrefix), paging(api, item, get)));
        });
        // Auth is one per connector: the one most drafted endpoints ask for (the first on a tie).
        // The paths that ask for another are named where they stand, not handed the wrong credential.
        List<Draft> live = drafts.stream().filter(d -> d.paging().block() != null).toList();
        Auth connectorAuth = mostCommon((live.isEmpty() ? drafts : live).stream().map(Draft::auth).toList());

        StringBuilder sb = new StringBuilder();
        sb.append("apiVersion: connectors.lumnik.io/v1\nkind: Connector\nmetadata:\n");
        // connector_type is what routes the manifest to a validator and an apply handler;
        // tenant is deliberately NOT pinned — apply refuses a manifest whose declared tenant
        // is not the authenticated one, so a hardcoded value made every draft un-appliable.
        sb.append("  name: ").append(name).append('\n').append("  connector_type: rest-generic\n");
        if (tags != null && !tags.isEmpty()) sb.append("  scopes: [").append(String.join(", ", tags)).append("]\n");
        // The spec is the remote party's document, and `--apply` applies the draft unread: every value
        // taken from it is written quoted, so none can end its scalar or its line and add a key.
        sb.append("spec:\n  base_url: ").append(quoted(api.getServers().get(0).getUrl())).append('\n');
        if (connectorAuth.block() != null) {
            sb.append("  auth: ").append(connectorAuth.block()).append('\n');
        } else if (!connectorAuth.drafted()) {
            sb.append(comment("  # auth: not drafted — the spec asks for " + connectorAuth.says()
                + ", which the draft does not translate. Declare spec.auth."));
        }
        sb.append("  endpoints:\n");
        for (Draft d : drafts) {
            if (!d.auth().equals(connectorAuth)) {
                sb.append(comment("    # auth: this path asks for " + d.auth().says() + ", not "
                    + connectorAuth.says() + " — move it to a connector of its own."));
            }
            String indent = "    ";
            Paging paging = d.paging();
            if (paging.block() == null) {
                sb.append(comment("    # pagination: not drafted — " + paging.why() + "."))
                  .append(comment("    # Declare " + paging.declare() + ", then uncomment (" + PAGINATION_DOCS + ")."));
                indent = "    # ";
            }
            sb.append(indent).append("- id: ").append(Identifiers.kebab(d.slug(), "endpoint")).append('\n')
              .append(indent).append("  method: GET\n")
              .append(indent).append("  path: ").append(quoted(d.path())).append('\n');
            if (paging.block() != null) sb.append(indent).append("  pagination: ").append(paging.block()).append('\n');
            sb.append(indent).append("  incremental: { mode: full }\n")
              .append(indent).append("  target: { kind: hybrid, table: ext.").append(safeName).append('_').append(d.slug())
              .append(", unique_key: $.id, promote: [] }\n");
        }
        return sb.toString();
    }

    private static Auth mostCommon(List<Auth> auths) {
        Map<Auth, Long> counts = new LinkedHashMap<>();
        auths.forEach(a -> counts.merge(a, 1L, Long::sum));
        return counts.entrySet().stream()
            .reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
            .map(Map.Entry::getKey)
            .orElse(Auth.NONE);
    }

    // ── auth ────────────────────────────────────────────────────────────────────────────────

    /**
     * The credential a path asks for. {@code block} is the manifest's auth mapping (null for none, and
     * for a scheme the draft does not translate — {@code drafted} tells the two apart); {@code says}
     * names it in a comment.
     */
    private record Auth(String block, String says, boolean drafted) {
        static final Auth NONE = new Auth(null, "no credentials", true);

        static Auth untranslated(String says) {
            return new Auth(null, says, false);
        }

        /** 0: drafted whole · 1: basic, whose user name the spec cannot give · 2: not drafted. */
        int rank() {
            if (!drafted) return 2;
            return block != null && block.startsWith(BASIC) ? 1 : 0;
        }
    }

    private static final String BASIC = "{ kind: basic,";

    /**
     * The operation's own {@code security} replaces the global one; an empty list lifts it. Of the
     * alternatives it offers, the first the draft writes whole wins: a bearer token or an API key
     * offered beside basic auth is taken before it, since basic needs a user name the spec never has.
     */
    private static Auth auth(OpenAPI api, Operation op, String envPrefix) {
        List<SecurityRequirement> alternatives = op.getSecurity() != null ? op.getSecurity() : api.getSecurity();
        if (alternatives == null) return Auth.NONE;
        return alternatives.stream()
            .map(requirement -> auth(api, requirement, envPrefix))
            .min(Comparator.comparingInt(Auth::rank))
            .orElse(Auth.NONE);
    }

    private static Auth auth(OpenAPI api, SecurityRequirement requirement, String envPrefix) {
        if (requirement.isEmpty()) return Auth.NONE;   // {} among the alternatives: anonymous is allowed
        if (requirement.size() > 1) return Auth.untranslated("several schemes at once");
        String name = requirement.keySet().iterator().next();
        SecurityScheme scheme = api.getComponents() == null || api.getComponents().getSecuritySchemes() == null
            ? null : api.getComponents().getSecuritySchemes().get(name);
        if (scheme == null || scheme.getType() == null) return Auth.untranslated("a scheme it does not declare");
        return switch (scheme.getType()) {
            case HTTP -> {
                String kind = String.valueOf(scheme.getScheme()).toLowerCase(Locale.ROOT);
                if ("basic".equals(kind)) {
                    yield new Auth(BASIC + " username: CHANGE_ME, password_env: " + envPrefix + "_PASSWORD }",
                        "http basic", true);
                }
                if ("bearer".equals(kind)) {
                    yield new Auth("{ kind: bearer, token_env: " + envPrefix + "_API_KEY }", "a bearer token", true);
                }
                yield Auth.untranslated("an http scheme other than basic or bearer");
            }
            case APIKEY -> {
                String in = String.valueOf(scheme.getIn());
                if (!"header".equals(in) && !"query".equals(in)) yield Auth.untranslated("an API key outside a header or query");
                String keyName = scheme.getName();
                if (keyName == null || !TOKEN.matcher(keyName).matches()) {
                    yield Auth.untranslated("an API key named with characters a header cannot carry");
                }
                yield new Auth("{ kind: api-key, in: " + in + ", name: " + quoted(keyName)
                    + ", value_env: " + envPrefix + "_API_KEY }", "an API key in " + in + " " + keyName, true);
            }
            default -> Auth.untranslated(scheme.getType().toString());
        };
    }

    // ── paging ──────────────────────────────────────────────────────────────────────────────

    /** Either a pagination mapping, or why there is none and what the integrator declares instead. */
    private record Paging(String block, String why, String declare) {
        static Paging drafted(String block) {
            return new Paging(block, null, null);
        }

        static Paging missing(String why, String declare) {
            return new Paging(null, why, declare);
        }
    }

    /** Where the rows are in a 200 answer, and the answer's own properties (empty for a bare array). */
    private record Rows(String path, Schema<?> row, Map<String, Schema> props) {}

    /**
     * JSONPaths are written quoted: in a flow mapping a plain {@code $.data[-1:].id} is not a scalar
     * but the start of a sequence, and the draft did not parse.
     */
    private static Paging paging(OpenAPI api, PathItem item, Operation op) {
        Map<String, Parameter> query = queryParams(api, item, op);
        boolean cursor = query.containsKey("starting_after");
        boolean offset = query.containsKey("offset") && query.containsKey("limit");
        boolean page = query.containsKey("page");
        int styles = (cursor ? 1 : 0) + (offset ? 1 : 0) + (page ? 1 : 0);
        if (styles == 0) {
            return Paging.missing("the spec declares no paging parameter (page, offset and limit, starting_after)",
                "pagination");
        }
        if (styles > 1) return Paging.missing("the spec declares parameters of more than one paging style", "pagination");
        Rows rows = rows(api, op);
        if (rows == null) return Paging.missing("the answer has no single array of rows", "pagination with its response_path");
        if (cursor) {
            boolean hasMore = rows.props().containsKey("has_more") && is(deref(api, rows.props().get("has_more")), "boolean");
            boolean ids = rows.row() != null && rows.row().getProperties() != null && rows.row().getProperties().containsKey("id");
            if (!hasMore || !ids) {
                return Paging.missing("the answer has no has_more flag or no id on its rows",
                    "pagination with its has_more_path and cursor_path");
            }
            return Paging.drafted("{ kind: cursor, cursor_param: starting_after, cursor_path: \"" + rows.path()
                + "[-1:].id\", has_more_path: \"$.has_more\", response_path: \"" + rows.path() + "\" }");
        }
        if (offset) {
            // offset stops on the first short page, so a limit above what the source serves ends the run
            // after page one. The spec's own default is served whole; its maximum is the most it serves.
            Integer limit = firstNonNull(declared(api, query.get("limit"), Schema::getDefault),
                declared(api, query.get("limit"), Schema::getMaximum));
            if (limit == null || limit < 1) {
                return Paging.missing("limit has no default and no maximum, so the page size is unknown",
                    "an offset pagination with a limit the source serves whole");
            }
            return Paging.drafted("{ kind: offset, offset_param: offset, limit_param: limit, limit: " + limit
                + ", response_path: \"" + rows.path() + "\" }");
        }
        // Starting at 1 on a source numbered from 0 skips a whole page and still ends clean.
        Integer start = firstNonNull(declared(api, query.get("page"), Schema::getDefault),
            declared(api, query.get("page"), Schema::getMinimum));
        if (start == null || start < 0) {
            return Paging.missing("page has no default and no minimum, so where pages start is unknown",
                "a page pagination with its start_page");
        }
        StringBuilder block = new StringBuilder("{ kind: page, page_param: page");
        SIZE_PARAMS.stream().filter(query::containsKey).findFirst().ifPresent(sizeParam -> {
            block.append(", size_param: ").append(sizeParam);
            Integer size = declared(api, query.get(sizeParam), Schema::getDefault);
            if (size != null && size > 0) block.append(", size: ").append(size);
        });
        return Paging.drafted(block.append(", start_page: ").append(start)
            .append(", response_path: \"").append(rows.path()).append("\" }").toString());
    }

    /** The path item's query parameters, overridden by the operation's own. */
    private static Map<String, Parameter> queryParams(OpenAPI api, PathItem item, Operation op) {
        Map<String, Parameter> query = new HashMap<>();
        for (List<Parameter> params : Arrays.asList(item.getParameters(), op.getParameters())) {
            if (params == null) continue;
            for (Parameter p : params) {
                Parameter resolved = deref(api, p);
                if (resolved != null && "query".equals(resolved.getIn()) && resolved.getName() != null) {
                    query.put(resolved.getName(), resolved);
                }
            }
        }
        return query;
    }

    /** The rows of the 200 JSON answer: the answer itself when it is an array, else its ONE array property. */
    private static Rows rows(OpenAPI api, Operation op) {
        ApiResponse ok = op.getResponses() == null ? null : op.getResponses().get("200");
        if (ok == null || ok.getContent() == null) return null;
        MediaType json = ok.getContent().entrySet().stream()
            .filter(e -> e.getKey().contains("json"))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
        Schema<?> answer = json == null ? null : deref(api, json.getSchema());
        if (answer == null) return null;
        if (is(answer, "array")) return new Rows("$", deref(api, answer.getItems()), Map.of());
        Map<String, Schema> props = answer.getProperties() == null ? Map.of() : answer.getProperties();
        List<String> arrays = props.entrySet().stream()
            .filter(e -> is(deref(api, e.getValue()), "array"))
            .map(Map.Entry::getKey)
            .toList();
        if (arrays.size() != 1 || !SIMPLE_NAME.matcher(arrays.get(0)).matches()) return null;
        return new Rows("$." + arrays.get(0), deref(api, deref(api, props.get(arrays.get(0))).getItems()), props);
    }

    /** A whole number the parameter's schema declares, or null. */
    private static Integer declared(OpenAPI api, Parameter p, Function<Schema, Object> field) {
        Schema<?> schema = p == null ? null : deref(api, p.getSchema());
        Object value = schema == null ? null : field.apply(schema);
        return value instanceof Number n && n.doubleValue() == n.intValue() ? n.intValue() : null;
    }

    private static Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }

    private static boolean is(Schema<?> s, String type) {
        return s != null && (type.equals(s.getType()) || (s.getTypes() != null && s.getTypes().contains(type)));
    }

    /** Follows a local {@code #/components/schemas/…} reference; a bound stops a reference cycle. */
    private static Schema<?> deref(OpenAPI api, Schema<?> s) {
        for (int hops = 0; s != null && s.get$ref() != null && hops < 8; hops++) {
            Map<String, Schema> schemas = api.getComponents() == null ? null : api.getComponents().getSchemas();
            s = schemas == null ? null : schemas.get(lastSegment(s.get$ref()));
        }
        return s;
    }

    private static Parameter deref(OpenAPI api, Parameter p) {
        if (p == null || p.get$ref() == null) return p;
        Map<String, Parameter> params = api.getComponents() == null ? null : api.getComponents().getParameters();
        return params == null ? null : params.get(lastSegment(p.get$ref()));
    }

    private static String lastSegment(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    // ── writing spec values ─────────────────────────────────────────────────────────────────

    /**
     * A value as a YAML double-quoted scalar. {@code \}, {@code "} and every character that could end a
     * line or that YAML refuses are escaped, so the value cannot close its scalar, start a new line, or
     * add a key.
     */
    static String quoted(String value) {
        StringBuilder sb = new StringBuilder("\"");
        String v = value == null ? "" : value;
        v.codePoints().forEach(cp -> {
            switch (cp) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (unwritable(cp)) sb.append(String.format("\\u%04x", cp));
                    else sb.appendCodePoint(cp);
                }
            }
        });
        return sb.append('"').toString();
    }

    /** One comment line, ended: a character that could end it early is replaced by a space. */
    static String comment(String line) {
        StringBuilder sb = new StringBuilder();
        line.codePoints().forEach(cp -> sb.appendCodePoint(unwritable(cp) ? ' ' : cp));
        return sb.append('\n').toString();
    }

    /** Control characters (CR, LF and NEL among them), the Unicode line and paragraph separators, and what YAML refuses. */
    private static boolean unwritable(int cp) {
        return Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0xFFFE || cp == 0xFFFF
            || Character.isSurrogate((char) cp) && cp <= 0xFFFF;
    }
}
