package io.lumnik.testkit.sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHO the caller is comes from the authenticated context, never from a header the caller wrote.
 *
 * <p>{@code RequireTenant} said this for the tenant since Act 0 finding #16. Nobody said it for the
 * USER, and five doors took {@code @HeaderParam("X-User-Id")}: one read and wrote another user's
 * default scope, three attributed every manual DLQ discard and connector run to user 0. A rule
 * stated for one axis does not cover its twin — so this one is stated once, here, for every module.
 *
 * <p>The header is not the defect: an auth filter reads it legitimately under {@code dev-bypass},
 * and that is the ONE place allowed to — where a header BECOMES an identity, behind the
 * deactivation guard. Each module names its own exempt file; everything else must take the identity
 * from the authenticated context.
 *
 * <p>Structural on purpose: a door that cannot declare the parameter cannot read it.
 */
public final class IdentityHeaderRule {

    /**
     * The headers that name WHO — as opposed to {@code X-Impersonate-Tenant}, which the filter alone
     * acts on.
     *
     * <p>Every spelling of the same annotation, each one found by a mutation that planted it:
     * {@code @HeaderParam("X-User-Id")}, the explicit member {@code @HeaderParam(value = "…")}, and
     * the fully-qualified {@code @jakarta.ws.rs.HeaderParam("…")} — which needs no import, so
     * stripping imports does not reach it. A guard that knows one spelling of the thing it forbids
     * is a guard with a door in it, and this one exists to close a door.
     */
    public static final Pattern IDENTITY_HEADER = Pattern.compile(
        "@(?:[\\w.]+\\.)?HeaderParam\\(\\s*(?:value\\s*=\\s*)?\"(X-User-Id|X-Tenant-Id)\"\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    /**
     * What every module's failure says. Module-agnostic on purpose: {@code RequireUser} and
     * {@code RequireTenant} live in {@code lumnik-hub}, so naming them here would hand
     * {@code lumnik-rag} and {@code lumnik-identity} a remedy their module cannot compile — a
     * message that lies at two of its three sites. Each sweep appends its own gesture.
     */
    public static final String RULE = """
        These read the caller's identity from a header the caller controls, so any authenticated \
        request can claim to be someone else. Take it from the authenticated context instead.""";

    private IdentityHeaderRule() {}

    /**
     * Every file in the tree that reads an identity header, by file name → the header it read.
     *
     * @param exempt the file allowed to turn a header into an identity — the module's auth filter,
     *               or {@code null} where the module has none. Passing a name no file bears fails:
     *               an exemption that names nothing is a rule quietly widening.
     */
    public static Map<String, String> offendersIn(SourceTree tree, String exempt) throws IOException {
        Map<String, String> offenders = new LinkedHashMap<>();
        boolean sawExempt = false;

        for (Path p : tree.javaFiles()) {
            String name = p.getFileName().toString();
            if (name.equals(exempt)) {
                sawExempt = true;
                continue;
            }
            Matcher m = IDENTITY_HEADER.matcher(SourceTree.code(Files.readString(p)));
            if (m.find()) offenders.put(name, m.group(1));
        }

        if (exempt != null && !sawExempt) {
            throw new IllegalStateException(
                "the sweep never saw its exempt file '" + exempt + "' under " + tree.root()
                    + " — an exemption that names nothing is a rule quietly widening");
        }
        return offenders;
    }
}
