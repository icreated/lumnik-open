package io.lumnik.hub.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A TCP relay between the hub and a destination server (SFTP, S3 — it reads no protocol) that can
 * hold an upload mid-stream: once {@link #holdAfter armed}, the client→server byte that crosses
 * the threshold is not forwarded until {@link #release()}. The run writing through it parks
 * inside the destination's own write, with its artifact half-sent, which is the one moment a test
 * needs to observe "in flight, final artifact not yet visible" — and to break the run at, from
 * another side (the database), deterministically rather than by racing a timer.
 *
 * <p>Pass-through until armed. Server→client traffic is never held, so a held client still
 * receives what the server already sent.
 */
public final class GatedRelay implements AutoCloseable {

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicLong upstream = new AtomicLong();
    private final AtomicLong downstream = new AtomicLong();
    private final List<Pump> pumps = new CopyOnWriteArrayList<>();
    private volatile long threshold = Long.MAX_VALUE;
    private volatile CountDownLatch held = new CountDownLatch(1);
    private volatile CountDownLatch released = new CountDownLatch(0);

    public GatedRelay(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("gated-relay-accept").start(this::accept);
    }

    /** The local port the destination should be configured with. */
    public int port() {
        return server.getLocalPort();
    }

    /**
     * Holds client→server traffic once {@code bytes} more have been forwarded from now, until
     * {@link #release()}. Re-arming replaces a previous hold.
     */
    public synchronized void holdAfter(long bytes) {
        held = new CountDownLatch(1);
        released = new CountDownLatch(1);
        threshold = upstream.get() + bytes;
    }

    /** Waits until a client write is being held; false on timeout. */
    public boolean awaitHeld(Duration timeout) throws InterruptedException {
        return held.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Lets held traffic through and returns to pass-through. */
    public synchronized void release() {
        threshold = Long.MAX_VALUE;
        released.countDown();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket target = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(target);
                Thread.ofVirtual().start(() -> pump(client, target, true));
                Thread.ofVirtual().start(() -> pump(target, client, false));
            } catch (IOException e) {
                if (!server.isClosed()) throw new IllegalStateException("gated relay accept", e);
            }
        }
    }

    /**
     * What the relay was doing when a wait on it gave up: whether a hold was reached, the bytes
     * that crossed each way, and for every pump the step it is in and for how long. A relay that
     * is stuck writing to the server points at the server; one stuck reading, at the client.
     */
    public String describe() {
        if (pumps.isEmpty()) return "relay: no connection was ever accepted";
        long now = System.nanoTime();
        return "relay: held=" + (held.getCount() == 0) + " upstream=" + upstream.get()
            + " downstream=" + downstream.get() + "\n"
            + pumps.stream().map(p -> "  " + p.describe(now)).collect(Collectors.joining("\n"));
    }

    /** One direction of one connection; its step is written by the pump thread, read by {@link #describe}. */
    private static final class Pump {
        private final String direction;
        private volatile String step = "reading";
        private volatile long since = System.nanoTime();
        private volatile long bytes;

        Pump(String direction) {
            this.direction = direction;
        }

        void step(String step) {
            this.step = step;
            this.since = System.nanoTime();
        }

        String describe(long now) {
            return direction + ": " + step + " for " + Duration.ofNanos(now - since).toMillis()
                + " ms, " + bytes + " bytes";
        }
    }

    private void pump(Socket from, Socket to, boolean clientToServer) {
        Pump me = new Pump(clientToServer ? "client->server" : "server->client");
        pumps.add(me);
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while (true) {
                me.step("reading");
                if ((n = in.read(buf)) < 0) break;
                me.bytes += n;
                if (!clientToServer) downstream.addAndGet(n);
                if (clientToServer && upstream.addAndGet(n) >= threshold) {
                    CountDownLatch gate = released;
                    held.countDown();
                    me.step("held");
                    gate.await();
                }
                me.step("writing");
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // A peer went away: close both sides, as a real network path would.
        } finally {
            me.step("closed");
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    @Override
    public void close() throws IOException {
        release();
        server.close();
        sockets.forEach(GatedRelay::closeQuietly);
    }
}
