package io.lumnik.hub.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #1836: another hub node (re)applies or deletes an Entity; this node's registry follows without a restart. The table is
 * the truth, and a node asks it whether it changed (a fingerprint), reading the rows only when it did.
 */
class EntityRegistryRefreshTest {

    private static String yaml(String name, String extra) {
        return "kind: Entity\nname: " + name + "\nmatch: [code]\nsources:\n  - table: t_a\n    map: { code: code }\n" + extra;
    }

    /** A registry over a table the test edits; counts how many times the rows were read. */
    private static final class Node extends EntityRegistry {
        final List<Row> table = new ArrayList<>();
        int version = 1;
        int reads = 0;
        boolean unreadable;
        Runnable duringRead = () -> {};

        @SuppressWarnings("unchecked")
        Node() {
            Instance<EntityManifestParser> parser = Mockito.mock(Instance.class);
            Mockito.when(parser.get()).thenReturn(new EntityManifestParser());
            this.parserInstance = parser;
        }

        void write(String name, String yaml) {
            table.removeIf(r -> r.name().equals(name));
            table.add(new Row(name, yaml));
            version++;
        }

        void delete(String name) {
            table.removeIf(r -> r.name().equals(name));
            version++;
        }

        @Override String fingerprint() { return "v" + version; }
        @Override List<Row> readRows() {
            reads++;
            List<Row> rows = List.copyOf(table);
            duringRead.run();
            return unreadable ? rows.stream().map(r -> new Row(r.name(), "kind: Entity\nname: [not closed")).toList() : rows;
        }
    }

    @Test
    void aSourceAddedOnAnotherNodeIsSeenOnTheNextRefresh() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        assertThat(node.find("orders").sources()).hasSize(1);

        node.write("orders", yaml("Orders", "  - table: t_b\n    map: { code: code }\n"));
        node.refreshIfChanged();

        assertThat(node.find("orders").sources()).hasSize(2);
    }

    @Test
    void aShapeThatBecameUnservableIsSeen() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        assertThat(node.find("orders").resolution()).isNull();

        node.write("orders", "kind: Entity\nname: Orders\nsources:\n  - table: t_a\n    map: { code: code, mail: email }\n"
            + "resolution:\n  identity:\n    t_a: code\n  cascade:\n    - id: email\n      exact: { fields: [email] }\n");
        node.refreshIfChanged();

        assertThat(node.find("orders").resolution()).isNotNull();
    }

    @Test
    void anEntityDeletedOnAnotherNodeIsGone() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.write("kept", yaml("Kept", ""));
        node.refreshIfChanged();

        node.delete("orders");
        node.refreshIfChanged();

        assertThat(node.find("orders")).isNull();
        assertThat(node.find("kept")).isNotNull();
    }

    @Test
    void nothingChangedReadsNoRows() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        int readsAfterFirst = node.reads;

        node.refreshIfChanged();
        node.refreshIfChanged();

        assertThat(node.reads).isEqualTo(readsAfterFirst);
    }

    @Test
    void anUnreadableRowKeepsTheCopyThisNodeHasAndDoesNotStopTheOthers() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.write("other", yaml("Other", ""));
        node.refreshIfChanged();
        EntityManifest before = node.find("orders");

        node.write("orders", "kind: Entity\nname: [not closed");
        node.write("other", yaml("Other", "  - table: t_b\n    map: { code: code }\n"));
        node.refreshIfChanged();

        assertThat(node.find("orders")).as("the last readable definition stays").isSameAs(before);
        assertThat(node.find("other").sources()).as("the readable one is refreshed").hasSize(2);
    }

    /** A failure that was not the definition's own (the next tick reads it fine) must heal without another write. */
    @Test
    void anUnreadableRowIsReadAgainOnTheNextTick() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        node.unreadable = true;
        node.write("orders", yaml("Orders", "  - table: t_b\n    map: { code: code }\n"));
        node.refreshIfChanged();
        assertThat(node.find("orders").sources()).hasSize(1);

        node.unreadable = false;
        node.refreshIfChanged();

        assertThat(node.find("orders").sources()).hasSize(2);
    }

    /** A refresh that read before an apply on this node and swapped after it must not take the apply back. */
    @Test
    void aRefreshDoesNotTakeBackAnApplyThisNodeMadeWhileItRead() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        EntityManifest applied = new EntityManifestParser().parse(yaml("Orders", "  - table: t_b\n    map: { code: code }\n"));
        node.write("orders", yaml("Orders", "  - table: t_c\n    map: { code: code }\n"));
        node.duringRead = () -> node.put(applied);

        node.refreshIfChanged();

        assertThat(node.find("orders")).isSameAs(applied);
    }

    /**
     * Sergey's review of #1850: a write landing AFTER the generation check but BEFORE the swap would still be taken
     * back. The write below is started from inside that window, on its own thread: it must wait for the swap and then
     * win, for a put as for a remove.
     */
    @Test
    void aLocalWriteLandingBetweenTheCheckAndTheSwapWaitsAndWins() throws Exception {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.write("gone", yaml("Gone", ""));
        node.refreshIfChanged();
        EntityManifest applied = new EntityManifestParser().parse(yaml("Orders", "  - table: t_b\n    map: { code: code }\n"));
        node.write("orders", yaml("Orders", "  - table: t_c\n    map: { code: code }\n"));
        List<Thread> writers = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean finishedInsideTheWindow = new java.util.concurrent.atomic.AtomicBoolean();
        node.afterCheck = () -> {
            Thread put = new Thread(() -> node.put(applied));
            Thread remove = new Thread(() -> node.remove("gone"));
            writers.add(put);
            writers.add(remove);
            put.start();
            remove.start();
            for (Thread t : writers) {   // each is either blocked on the registry's lock or, wrongly, already through it
                long until = System.nanoTime() + 2_000_000_000L;
                while (t.isAlive() && t.getState() != Thread.State.BLOCKED && System.nanoTime() < until) Thread.onSpinWait();
                if (!t.isAlive()) finishedInsideTheWindow.set(true);   // got through while the swap was pending
            }
        };

        node.refreshIfChanged();
        for (Thread t : writers) t.join();

        assertThat(finishedInsideTheWindow).as("no writer completes before the swap has").isFalse();
        assertThat(node.find("orders")).as("the apply made while the swap ran is not taken back").isSameAs(applied);
        assertThat(node.find("gone")).as("nor is the delete").isNull();
    }

    /** A row spelled in capitals that cannot be read keeps the copy under the registry's own (lower-case) key. */
    @Test
    void anUnreadableRowKeepsItsCopyWhateverItsSpelling() {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        node.refreshIfChanged();
        EntityManifest before = node.find("orders");

        node.delete("orders");
        node.write("Orders", "kind: Entity\nname: [not closed");
        node.refreshIfChanged();

        assertThat(node.find("orders")).isSameAs(before);
    }

    /** The miss path swaps too: its prune must wait for whoever holds the registry's lock, like the refresh's. */
    @Test
    void theMissPathSwapsUnderTheSameLock() throws Exception {
        Node node = new Node();
        node.write("orders", yaml("Orders", ""));
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reload;
        synchronized (node.swapLock) {
            reload = new Thread(() -> { node.findReloadingIfMissing("orders"); done.set(true); });
            reload.start();
            Thread.sleep(300);
            assertThat(done).as("the reload cannot swap while the lock is held").isFalse();
        }
        reload.join(5000);

        assertThat(done).isTrue();
        assertThat(node.find("orders")).isNotNull();
    }
}
