package io.lumnik.hub.secret;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The scopes of whatever reads secrets on this thread: a connector's tags, an export's scope (#2015). It reads a secret
 * only when it carries every scope of the secret — a scopeless secret serves the scopeless readers only — and
 * {@link PgcryptoSecretResolver} asks, before it decrypts anything.
 *
 * <p>Set by each door that builds a reader from a connector or an export. Like the tenant, it is never guessed: a
 * read with no audience throws instead of serving the scopeless case, so a door added later without one fails in its
 * first test rather than reading every secret of the tenant.
 */
public final class SecretAudience {

    private static final ThreadLocal<List<String>> SCOPES = new ThreadLocal<>();

    private SecretAudience() {}

    /** Runs {@code work} as a reader carrying {@code scopes}, then puts back what was there. */
    public static <T> T get(Collection<String> scopes, Supplier<T> work) {
        List<String> previous = enter(scopes);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /** {@link #get}, for work that throws. */
    public static <T> T call(Collection<String> scopes, Callable<T> work) throws Exception {
        List<String> previous = enter(scopes);
        try {
            return work.call();
        } finally {
            restore(previous);
        }
    }

    /** The reader's scopes; {@link IllegalStateException} when none was set — a mis-wiring, never "no scope". */
    static List<String> current() {
        List<String> scopes = SCOPES.get();
        if (scopes == null) {
            throw new IllegalStateException("a secret was read with no SecretAudience: the door building this reader"
                + " must name the scopes of its connector or export");
        }
        return scopes;
    }

    /** Sorted and distinct: the same scopes are one reader in the read trail, and a refusal names them in one order. */
    private static List<String> enter(Collection<String> scopes) {
        List<String> previous = SCOPES.get();
        SCOPES.set(scopes.stream().distinct().sorted().toList());
        return previous;
    }

    private static void restore(List<String> previous) {
        if (previous == null) SCOPES.remove(); else SCOPES.set(previous);
    }
}
