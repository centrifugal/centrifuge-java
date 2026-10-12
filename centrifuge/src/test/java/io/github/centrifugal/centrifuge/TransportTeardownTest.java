package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The client tears its transport down itself and doesn't wait for the socket's close
 * callback, and a WebSocket handshake is bounded by the client timeout.
 */
public class TransportTeardownTest {

    private Client client;
    private FakeCentrifugoServer server;
    private ServerSocket silent;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close(1000);
        }
        if (server != null) {
            server.stop();
        }
        if (silent != null) {
            silent.close();
        }
        for (Socket s : accepted) {
            s.close();
        }
    }

    private int connectCommands() {
        int n = 0;
        for (Protocol.Command cmd : server.received()) {
            if (cmd.hasConnect()) {
                n++;
            }
        }
        return n;
    }

    /**
     * A dead link: the server stops sending pings and never answers the client's close
     * frame. After "no ping" the client must reconnect within its backoff, not after
     * OkHttp gives up waiting for the peer's close frame (60s).
     */
    @Test
    public void testReconnectAfterNoPingDoesNotWaitForPeerClose() throws Exception {
        server = new FakeCentrifugoServer();
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(1).build();
        // A dead link: the peer never answers the client's close frame.
        server.answerClose = false;
        server.start();
        Options opts = new Options();
        opts.setMaxServerPingDelay(300);
        opts.setMinReconnectDelay(100);
        opts.setMaxReconnectDelay(200);
        LinkedBlockingQueue<Long> noPing = new LinkedBlockingQueue<>();
        client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                if (e.getCode() == Client.CONNECTING_NO_PING) {
                    noPing.add(System.nanoTime());
                }
            }
        });
        client.connect();
        assertNotNull("no ping detected", noPing.poll(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (connectCommands() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("reconnected within 5s of no ping, connect commands: " + connectCommands(), connectCommands() >= 2);
    }

    /**
     * After "no ping" on a link that went dead (nothing gets through any more, so the close
     * frame never reaches the peer), the client doesn't keep the dead connection's socket open
     * next to the new one: OkHttp would keep it for up to 60s waiting for the peer's close frame.
     */
    @Test
    public void testDeadConnectionSocketIsReleased() throws Exception {
        server = new FakeCentrifugoServer();
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").setPing(1).build();
        server.start();
        java.net.URI upstream = java.net.URI.create(server.url());
        CountingRelay relay = new CountingRelay(upstream.getHost(), upstream.getPort());
        try {
            Options opts = new Options();
            opts.setMaxServerPingDelay(300);
            opts.setMinReconnectDelay(100);
            opts.setMaxReconnectDelay(200);
            LinkedBlockingQueue<Integer> connecting = new LinkedBlockingQueue<>();
            CountDownLatch firstConnected = new CountDownLatch(1);
            CountDownLatch secondConnected = new CountDownLatch(2);
            client = new Client("ws://127.0.0.1:" + relay.port() + "/connection/websocket", opts, new EventListener() {
                @Override
                public void onConnecting(Client c, ConnectingEvent e) {
                    connecting.add(e.getCode());
                }

                @Override
                public void onConnected(Client c, ConnectedEvent e) {
                    firstConnected.countDown();
                    secondConnected.countDown();
                    // From the second connection on, the server pings rarely: one no-ping only.
                    server.connectResult = Protocol.ConnectResult.newBuilder()
                            .setClient("fake-client").setVersion("0.0.0").setPing(25).build();
                }
            });
            client.connect();
            assertTrue("connected", firstConnected.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // The link of the first connection goes dead.
            relay.blackholeExisting();
            Integer code;
            do {
                code = connecting.poll(100, TimeUnit.MILLISECONDS);
            } while ((code == null || code != Client.CONNECTING_NO_PING) && System.nanoTime() < deadline);
            assertEquals("no ping", Integer.valueOf(Client.CONNECTING_NO_PING), code);
            assertTrue("reconnected", secondConnected.await(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (relay.open() != 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("only the new connection's socket is open", 1, relay.open());
        } finally {
            relay.close();
        }
    }

    /** A TCP relay that counts the client connections it currently relays. */
    private static final class CountingRelay {
        private final ServerSocket listener = new ServerSocket(0);
        private final java.util.concurrent.atomic.AtomicInteger open = new java.util.concurrent.atomic.AtomicInteger();
        private final List<java.util.concurrent.atomic.AtomicBoolean> holes = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Existing connections drop everything from now on; new connections work. */
        void blackholeExisting() {
            for (java.util.concurrent.atomic.AtomicBoolean hole : holes) {
                hole.set(true);
            }
        }

        CountingRelay(String host, int port) throws IOException {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket client = listener.accept();
                        Socket upstream = new Socket(host, port);
                        open.incrementAndGet();
                        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                        Runnable done = () -> {
                            if (closed.compareAndSet(false, true)) {
                                open.decrementAndGet();
                                try {
                                    client.close();
                                } catch (IOException ignored) {
                                }
                                try {
                                    upstream.close();
                                } catch (IOException ignored) {
                                }
                            }
                        };
                        java.util.concurrent.atomic.AtomicBoolean hole = new java.util.concurrent.atomic.AtomicBoolean();
                        holes.add(hole);
                        pump(client, upstream, done, hole);
                        pump(upstream, client, done, hole);
                    }
                } catch (IOException ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static void pump(Socket from, Socket to, Runnable done, java.util.concurrent.atomic.AtomicBoolean hole) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try {
                    InputStream in = from.getInputStream();
                    java.io.OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        // Blackholed: keep reading (to see the client close), forward nothing.
                        if (!hole.get()) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {
                }
                done.run();
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        int open() {
            return open.get();
        }

        void close() throws IOException {
            listener.close();
        }
    }

    private int startSilentServer() throws IOException {
        silent = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    accepted.add(silent.accept());
                }
            } catch (IOException ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return silent.getLocalPort();
    }

    /**
     * A server that accepts TCP but never answers the upgrade: the attempt fails after the
     * client timeout with an error, and the client tries again. Before, only OkHttp's read
     * timeout (10s) ended it, whatever the client timeout.
     */
    @Test
    public void testHungHandshakeFailsAfterClientTimeout() throws Exception {
        int port = startSilentServer();
        Options opts = new Options();
        opts.setTimeout(500);
        opts.setMinReconnectDelay(100);
        opts.setMaxReconnectDelay(200);
        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        client = new Client("ws://127.0.0.1:" + port + "/connection/websocket", opts, new EventListener() {
            @Override
            public void onError(Client c, ErrorEvent e) {
                errors.add(e.getError());
            }
        });
        long start = System.nanoTime();
        client.connect();
        Throwable error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull("an error within 3s", error);
        assertTrue("connect timeout, got " + error, error instanceof TimeoutException);
        assertTrue("after the client timeout", System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(450));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (accepted.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("a new attempt, sockets accepted: " + accepted.size(), accepted.size() >= 2);
        assertEquals(ClientState.CONNECTING, client.getState());
    }

    /** disconnect() during a hung handshake closes its socket at once. */
    @Test
    public void testDisconnectAbortsHungHandshake() throws Exception {
        int port = startSilentServer();
        Options opts = new Options();
        opts.setTimeout(5000);
        CountDownLatch disconnected = new CountDownLatch(1);
        client = new Client("ws://127.0.0.1:" + port + "/connection/websocket", opts, new EventListener() {
            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                disconnected.countDown();
            }
        });
        client.connect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (accepted.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("handshake started", 1, accepted.size());
        client.disconnect();
        assertTrue(disconnected.await(3, TimeUnit.SECONDS));
        Socket s = accepted.get(0);
        s.setSoTimeout(1500);
        InputStream in = s.getInputStream();
        long closedAfter = -1;
        long start = System.nanoTime();
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) {
                // The request bytes of the handshake; read until the client closes.
            }
            closedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (java.net.SocketTimeoutException e) {
            // Still open after 1.5s.
        } catch (IOException e) {
            closedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        assertTrue("the client closed the handshake socket within 1.5s of disconnect()", closedAfter >= 0);
    }
}
