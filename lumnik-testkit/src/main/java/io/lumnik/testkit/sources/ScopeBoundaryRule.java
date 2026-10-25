package io.lumnik.testkit.sources;

import io.lumnik.common.security.ScopeDecision;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A door that touches a scope's data must decide whether the caller holds it — stated
 * once, here, so hub and rag are held to the same shape even though each names its own axis (what
 * "touches a scope" means for that module's doors) and its own decider (the primitive a door must
 * call to ask). Each module runs its own copy of the sweep, from its own {@link SourceTree}, the
 * way {@link IdentityHeaderRule} is: a rule confined to one module's test run is worth nothing to
 * a module whose own tests never trigger it (#508 — a sweep that lived only in {@code lumnik-hub}
 * never saw {@code lumnik-rag}'s doors, not even once {@code lumnik-rag} was made to build them).
 *
 * <p>The deciding calls are derived by REFLECTION over the decider's methods, never hand-copied: a
 * hand-kept list goes stale the moment the primitive grows a method — which is exactly what
 * happened when {@code ScopeGrants} grew {@code allowsAll} and this walk reported an
 * already-guarded door as unguarded, until the list was widened by hand. This is also why rag's
 * own decider, {@code ScopeAccess}, had to become a public primitive rather than staying
 * module-private (#508): {@code Class.getMethods()} only returns public members, so a
 * package-private decider is invisible to it no matter which module runs the sweep.
 *
 * <p>Not every public static method on a decider IS a decision, though: {@code
 * ScopeAccess.normalized} only strips and collapses client input, and {@code
 * ScopeGrants.nameProblem}/{@code grantedForLog} validate a name's shape or render a log line — a
 * door that calls only one of those decides nothing, yet each is public and static for the same
 * reason a real decision is. Reflecting over every public static method let a door that called
 * only {@code normalized(...)} pass the sweep while refusing no one (round 1 review, #508). So the
 * reflection filters on {@link ScopeDecision} instead — marked at the method, next to the
 * decision, so adding one still needs no edit here, but a helper that merely shapes the INPUT to a
 * decision is never mistaken for the decision itself. Reflection also drops a constant like
 * {@code ScopeGrants.REFUSAL} for free: a field is not a method, so it never asks anything.
 */
public final class ScopeBoundaryRule {

    private ScopeBoundaryRule() {}

    /** What one module's sweep found: every door on axis, and which of those never decide. */
    public record Sweep(List<String> seen, Map<String, String> unguarded) {}

    /**
     * The calls that ASK the question, derived from {@code decider}'s own {@link ScopeDecision}
     * methods — public and static like every other method here, but that alone does not make a
     * method a decision; the annotation does.
     */
    public static List<String> decidingCalls(Class<?> decider) {
        return Arrays.stream(decider.getMethods())
            .filter(m -> Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()))
            .filter(m -> m.getDeclaringClass() == decider)
            .filter(m -> m.isAnnotationPresent(ScopeDecision.class))
            .map(m -> decider.getSimpleName() + "." + m.getName() + "(")
            .distinct()
            .toList();
    }

    /** Does this source touch the scope, by one of these markers? Comments do not count as code. */
    public static boolean touches(List<String> markers, String javaSource) {
        return markers.stream().anyMatch(SourceTree.code(javaSource)::contains);
    }

    /**
     * Does this source actually ASK — call one of {@code decidingCalls}? A bare mention (an unused
     * import, a javadoc {@code @link}, a comment explaining the check's absence) does not count,
     * because {@link SourceTree#code} already stripped it; and a reference to a REFUSAL constant is
     * not a decision either, because {@link #decidingCalls} never puts a field in this list.
     */
    public static boolean consults(List<String> decidingCalls, String javaSource) {
        return decidingCalls.stream().anyMatch(SourceTree.code(javaSource)::contains);
    }

    /**
     * Walk {@code root}'s {@code *suffix} files, keep the ones that touch the scope, and report
     * those that never consult the decider and are not {@code exempt}.
     */
    public static Sweep sweep(SourceTree root, String suffix, List<String> touching,
            List<String> decidingCalls, Set<String> exempt) throws IOException {
        List<String> seen = new ArrayList<>();
        Map<String, String> unguarded = new LinkedHashMap<>();
        for (Path p : root.javaFiles(suffix)) {
            String src = Files.readString(p);
            if (!touches(touching, src)) continue;
            String name = p.getFileName().toString();
            seen.add(name);
            if (!consults(decidingCalls, src) && !exempt.contains(name)) {
                unguarded.put(name, pathOf(src));
            }
        }
        return new Sweep(seen, unguarded);
    }

    /** The @Path of the resource, so a failure names the URL an integrator would actually call. */
    private static String pathOf(String src) {
        int at = src.indexOf("@Path(\"");
        if (at < 0) return "(no @Path)";
        int end = src.indexOf('"', at + 7);
        return end < 0 ? "(no @Path)" : src.substring(at + 7, end);
    }
}
