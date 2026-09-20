package io.lumnik.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public static method as a métier-scope AUTHORIZATION decision — a door that calls it
 * is asking whether the caller holds a scope, and a caller outside their grant gets refused.
 *
 * <p>{@code ScopeBoundaryRule.decidingCalls} reflects over annotated methods, not every public
 * static method on the decider. A decider also carries public static methods that are NOT
 * decisions — {@code ScopeAccess.normalized} strips and collapses client input, {@code
 * ScopeGrants.nameProblem} validates a scope NAME's shape, {@code ScopeGrants.grantedForLog}
 * renders a log line — and a door that calls only one of those decides nothing, yet each is
 * public and static for the same reason a real decision is. Sweeping all of them in let a door
 * that called only {@code ScopeAccess.normalized(req.scope())} pass the sweep while refusing no
 * one (round 1 review on #508). The annotation still derives the list from the primitive rather
 * than copying method names into the guard: adding a decision means marking it here, next to the
 * method, not editing a test elsewhere.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ScopeDecision {
}
