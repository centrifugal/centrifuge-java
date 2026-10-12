package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Each connect attempt builds a new WebSocket, but the previous one can still
 * report events afterwards: connect() cancels a socket whose close handshake
 * from disconnect() has not finished, which makes OkHttp call onFailure on it.
 * Those late callbacks belong to a connection the client already gave up and
 * must not tear down the connection that replaced it.
 */
public class StaleTransportCallbackTest {

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
    public void testOldTransportCallbacksIgnoredAfterDisconnectThenConnect() throws Exception {
        List<ErrorEvent> errors = new CopyOnWriteArrayList<>();
        AtomicInteger connecting = new AtomicInteger();
        AtomicReference<CountDownLatch> connected = new AtomicReference<>(new CountDownLatch(1));

        Options opts = new Options();
        // A torn-down connection would only come back after this delay, far
        // beyond the assertions below.
        opts.setMinReconnectDelay(60000);
        opts.setMaxReconnectDelay(60000);

        Client client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                connecting.incrementAndGet();
            }

            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.get().countDown();
            }

            @Override
            public void onError(Client c, ErrorEvent e) {
                errors.add(e);
            }
        });
        client.connect();
        try {
            assertTrue("connected", connected.get().await(5, TimeUnit.SECONDS));

            connected.set(new CountDownLatch(1));
            client.disconnect();
            client.connect();

            assertTrue("connected again after disconnect() + connect()",
                    connected.get().await(5, TimeUnit.SECONDS));
            // Give late callbacks of the replaced socket time to arrive.
            Thread.sleep(500);
            drainExecutor(client);

            assertEquals("callbacks of the replaced socket must not report errors: " + errors,
                    0, errors.size());
            assertEquals("only the two connect() calls should move the client to CONNECTING",
                    2, connecting.get());
            assertEquals(ClientState.CONNECTED, client.getState());
        } finally {
            client.close(1000);
        }
    }
}
