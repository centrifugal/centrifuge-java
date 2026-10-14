package io.github.centrifugal.centrifuge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A server with pings disabled sends no ping interval in the connect reply. The
 * client waited for pings anyway and ended every connection with "no ping" after
 * maxServerPingDelay, then reconnected, for good.
 */
public class ServerPingDisabledTest {
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

    @Test
    public void testConnectionStaysWithoutServerPings() throws Exception {
        server.connectResult = Protocol.ConnectResult.newBuilder()
                .setClient("fake-client").setVersion("0.0.0").build();
        Options opts = new Options();
        opts.setMaxServerPingDelay(300);
        opts.setMinReconnectDelay(50);
        opts.setMaxReconnectDelay(50);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicInteger connecting = new AtomicInteger();
        Client client = new Client(server.url(), opts, new EventListener() {
            @Override
            public void onConnecting(Client c, ConnectingEvent e) {
                connecting.incrementAndGet();
            }

            @Override
            public void onConnected(Client c, ConnectedEvent e) {
                connected.countDown();
            }
        });
        client.connect();
        try {
            assertTrue("connected", connected.await(5, TimeUnit.SECONDS));
            Thread.sleep(1000);
            assertEquals(ClientState.CONNECTED, client.getState());
            assertEquals("no reconnect without server pings", 1, connecting.get());
        } finally {
            client.close(1000);
        }
    }
}
