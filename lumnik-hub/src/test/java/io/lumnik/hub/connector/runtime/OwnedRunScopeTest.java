package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.testsupport.LogProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedRunScopeTest {

    private final List<String> closed = new ArrayList<>();

    private final class Tracked implements AutoCloseable {
        private final String name;
        private final boolean failOnClose;
        Tracked(String name, boolean failOnClose) { this.name = name; this.failOnClose = failOnClose; }
        @Override public void close() {
            closed.add(name);
            if (failOnClose) throw new IllegalStateException("close of " + name + " failed");
        }
    }

    private Tracked tracked(String name) { return new Tracked(name, false); }

    @Test
    void the_same_key_gets_the_same_instance_and_the_factory_runs_once() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        int[] built = {0};

        Tracked first = scope.resource("sftp", () -> { built[0]++; return tracked("sftp"); });
        Tracked again = scope.resource("sftp", () -> { built[0]++; return tracked("sftp-2"); });

        assertThat(again).isSameAs(first);
        assertThat(built[0]).isEqualTo(1);
    }

    @Test
    void distinct_keys_are_distinct_resources() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        assertThat(scope.resource("a", () -> tracked("a"))).isNotSameAs(scope.resource("b", () -> tracked("b")));
    }

    @Test
    void close_runs_newest_first_and_only_once() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> tracked("a"));
        scope.resource("b", () -> tracked("b"));
        scope.resource("c", () -> tracked("c"));

        scope.close();
        scope.close();

        assertThat(closed).containsExactly("c", "b", "a");
    }

    @Test
    void an_invalidated_resource_is_closed_now_and_rebuilt_on_the_next_ask_as_the_newest() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        Tracked old = scope.resource("a", () -> tracked("a-old"));
        scope.resource("b", () -> tracked("b"));

        scope.invalidate("a");
        assertThat(closed).containsExactly("a-old");

        Tracked fresh = scope.resource("a", () -> tracked("a-new"));
        assertThat(fresh).isNotSameAs(old);

        scope.close();
        assertThat(closed).containsExactly("a-old", "a-new", "b");
    }

    @Test
    void invalidating_an_absent_key_is_a_no_op() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.invalidate("never-built");
        assertThat(closed).isEmpty();
    }

    @Test
    void a_failing_close_is_logged_and_does_not_stop_the_others_or_escape() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> tracked("a"));
        scope.resource("b", () -> new Tracked("b", true));
        scope.resource("c", () -> tracked("c"));

        List<String> warnings = LogProbe.warnings(OwnedRunScope.class, scope::close);

        assertThat(closed).as("every resource got its close, the failing one in the middle included")
            .containsExactly("c", "b", "a");
        assertThat(warnings).as("the failure is visible: %s", warnings)
            .anySatisfy(w -> assertThat(w).contains("run-1").contains("b").contains("close of b failed"));
    }

    @Test
    void an_error_from_a_close_does_not_stop_the_others_and_is_rethrown_last() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> tracked("a"));
        scope.resource("b", () -> (AutoCloseable) () -> { closed.add("b"); throw new InternalError("boom"); });
        scope.resource("c", () -> tracked("c"));

        assertThatThrownBy(scope::close).isInstanceOf(InternalError.class).hasMessage("boom");
        assertThat(closed).as("c and a were still closed").containsExactly("c", "b", "a");
    }

    @Test
    void a_linkage_error_from_a_close_is_logged_like_an_exception_and_does_not_escape() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> tracked("a"));
        scope.resource("b", () -> (AutoCloseable) () -> { closed.add("b"); throw new NoClassDefFoundError("com/acme/Client"); });

        List<String> warnings = LogProbe.warnings(OwnedRunScope.class, scope::close);

        assertThat(closed).containsExactly("b", "a");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("run-1").contains("com/acme/Client"));
    }

    @Test
    void an_interrupt_met_while_closing_is_not_lost() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> (AutoCloseable) () -> { throw new InterruptedException("cancelled"); });
        scope.resource("b", () -> tracked("b"));

        scope.close();

        assertThat(Thread.interrupted()).as("the thread still carries the interrupt (and this clears it for the next test)").isTrue();
        assertThat(closed).as("the others were closed all the same").containsExactly("b");
    }

    @Test
    void a_second_error_from_a_close_is_kept_as_suppressed_on_the_first() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.resource("a", () -> (AutoCloseable) () -> { throw new InternalError("from a"); });
        scope.resource("b", () -> (AutoCloseable) () -> { throw new InternalError("from b"); });

        assertThatThrownBy(scope::close)
            .isInstanceOf(InternalError.class).hasMessage("from b")
            .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("from a"));
    }

    /**
     * The runs execute on virtual threads, and on JDK 21 a virtual thread blocked inside a synchronized block pins its
     * carrier. A close that takes seconds (a Kafka consumer, an SSH disconnect) must therefore not hold the scope's
     * lock: another caller of the scope is not made to wait for it.
     */
    @Test
    void a_slow_close_does_not_hold_the_lock_other_callers_need() throws Exception {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        CountDownLatch inClose = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scope.resource("slow", () -> (AutoCloseable) () -> { inClose.countDown(); release.await(10, TimeUnit.SECONDS); });
        Thread closer = new Thread(scope::close);
        closer.start();
        try {
            assertThat(inClose.await(5, TimeUnit.SECONDS)).as("the close is under way").isTrue();

            CompletableFuture<Void> other = CompletableFuture.runAsync(() -> scope.invalidate("other"));

            other.get(2, TimeUnit.SECONDS);   // times out while the closing thread holds the lock
        } finally {
            release.countDown();
            closer.join(5_000);
        }
    }

    @Test
    void a_failing_invalidate_close_is_logged_and_the_key_is_still_forgotten() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        Tracked bad = scope.resource("a", () -> new Tracked("a", true));

        List<String> warnings = LogProbe.warnings(OwnedRunScope.class, () -> scope.invalidate("a"));

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("close of a failed"));
        assertThat(scope.resource("a", () -> tracked("a-new"))).isNotSameAs(bad);
    }

    @Test
    void a_factory_that_throws_registers_nothing_and_the_next_ask_may_try_again() {
        OwnedRunScope scope = new OwnedRunScope("run-1");

        assertThatThrownBy(() -> scope.resource("a", () -> { throw new IllegalArgumentException("no route"); }))
            .isInstanceOf(IllegalArgumentException.class);
        Tracked built = scope.resource("a", () -> tracked("a"));

        scope.close();
        assertThat(closed).containsExactly("a");
        assertThat(built).isNotNull();
    }

    @Test
    void a_scope_that_is_closed_refuses_new_resources_loudly() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        scope.close();

        assertThatThrownBy(() -> scope.resource("a", () -> tracked("a")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("run-1");
    }

    @Test
    void a_null_key_or_a_null_resource_is_a_bug_named_at_once() {
        OwnedRunScope scope = new OwnedRunScope("run-1");
        assertThatThrownBy(() -> scope.resource(null, () -> tracked("a"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scope.resource("a", () -> null)).isInstanceOf(NullPointerException.class);
    }

    /** A missing cancel flag is refused where it is passed, not when a reader first asks for it mid-fetch (#1444). */
    @Test
    void a_null_cancel_flag_is_refused_at_construction() {
        assertThatThrownBy(() -> new OwnedRunScope("run", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("a run scope needs its run's cancel flag");
    }
}
