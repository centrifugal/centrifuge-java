package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A connect command that never gets a reply fails either by timing out or by the
 * connection going away. Both outcomes are handled by the connect future's
 * failure handler, which must run on the client executor like every other state
 * transition — the timeout fires on the CompletableFuture delay scheduler thread —
 * and must not report a connect error once the client has already left CONNECTING.
 */
public class ConnectFailureHandlingTest {

    private FakeCentrifugoServer server;

    @Before
    public void setUp() throws IOException {
        server = new FakeCentrifugoServer();
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    // Blocks until every task queued on the client executor so far has run.
    private static void drainExecutor(Client client) throws Exception {
        client.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testConnectTimeoutHandledOnExecutorThread() throws Exception {
        // Never answer connect, so the command times out.
        server.deferCommand = cmd -> cmd.hasConnect();

        AtomicReference<Thread> executorThread = new AtomicReference<>();
        AtomicReference<Thread> errorThread = new AtomicReference<>();
        CountDownLatch errored = new CountDownLatch(1);

        Options opts = new Options();
        opts.setTimeout(300);
        // Keep the follow-up reconnect out of the way of the assertions.
        opts.setMinReconnectDelay(60000);
        opts.setMaxReconnectDelay(60000);

        Client client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                executorThread.compareAndSet(null, Thread.currentThread());
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                errorThread.compareAndSet(null, Thread.currentThread());
                errored.countDown();
            }
        });
        client.connect();
        try {
            assertTrue("connect timeout reported", errored.await(5, TimeUnit.SECONDS));
            assertSame("connect timeout must be handled on the client executor thread",
                    executorThread.get(), errorThread.get());
        } finally {
            client.close(1000);
        }
    }

    @Test
    public void testNoConnectErrorWhenDisconnectCalledDuringConnect() throws Exception {
        CountDownLatch connectSent = new CountDownLatch(1);
        server.deferCommand = cmd -> {
            if (cmd.hasConnect()) {
                connectSent.countDown();
                return true;
            }
            return false;
        };

        List<ErrorEvent> errors = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);

        Client client = new Client(server.url(), new Options(), new EventListener() {
            @Override
            public void onError(Client c, ErrorEvent e) {
                errors.add(e);
            }

            @Override
            public void onDisconnected(Client c, DisconnectedEvent e) {
                disconnected.countDown();
            }
        });
        client.connect();
        try {
            assertTrue("connect sent", connectSent.await(5, TimeUnit.SECONDS));

            client.disconnect();
            assertTrue("disconnected", disconnected.await(5, TimeUnit.SECONDS));
            drainExecutor(client);

            assertEquals("disconnect() during connect must not report a connect error: " + errors,
                    0, errors.size());
        } finally {
            client.close(1000);
        }
    }
}
