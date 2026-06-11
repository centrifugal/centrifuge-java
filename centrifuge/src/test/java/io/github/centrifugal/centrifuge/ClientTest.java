package io.github.centrifugal.centrifuge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientTest {

    @Test
    public void testInitialization() {
        Client client = new Client("", new Options(), null);
        assertEquals(client.getState(), ClientState.DISCONNECTED);
    }

    @Test
    public void testInitializationWithEndpoint() {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        assertEquals(ClientState.DISCONNECTED, client.getState());
    }

    @Test
    public void testInitializationWithOptions() {
        Options opts = new Options();
        opts.setToken("test-token");
        opts.setName("test-client");
        opts.setVersion("1.0.0");
        opts.setTimeout(10000);
        Client client = new Client("ws://localhost:8000/connection/websocket", opts, null);
        assertEquals(ClientState.DISCONNECTED, client.getState());
    }

    @Test
    public void testNewSubscription() throws DuplicateSubscriptionException {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        Subscription sub = client.newSubscription("test-channel", new SubscriptionOptions(), null);
        assertNotNull(sub);
        assertEquals(SubscriptionState.UNSUBSCRIBED, sub.getState());
    }

    @Test(expected = DuplicateSubscriptionException.class)
    public void testNewSubscriptionDuplicateChannel() throws DuplicateSubscriptionException {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        client.newSubscription("test-channel", new SubscriptionOptions(), null);
        client.newSubscription("test-channel", new SubscriptionOptions(), null);
    }

    @Test
    public void testGetSubscription() throws DuplicateSubscriptionException {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        Subscription sub = client.newSubscription("test-channel", new SubscriptionOptions(), null);
        assertNotNull(sub);
        Subscription retrieved = client.getSubscription("test-channel");
        assertSame(sub, retrieved);
    }

    @Test
    public void testGetSubscriptionNotFound() {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        Subscription retrieved = client.getSubscription("nonexistent-channel");
        assertNull(retrieved);
    }

    @Test
    public void testRemoveSubscription() throws DuplicateSubscriptionException {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        Subscription sub = client.newSubscription("test-channel", new SubscriptionOptions(), null);
        client.removeSubscription(sub);
        assertNull(client.getSubscription("test-channel"));
    }

    @Test
    public void testNewSubscriptionAfterRemoval() throws DuplicateSubscriptionException {
        Client client = new Client("ws://localhost:8000/connection/websocket", new Options(), null);
        Subscription sub = client.newSubscription("test-channel", new SubscriptionOptions(), null);
        client.removeSubscription(sub);
        // Should be able to create a new subscription after removal.
        Subscription sub2 = client.newSubscription("test-channel", new SubscriptionOptions(), null);
        assertNotNull(sub2);
    }

    @Test(timeout = 10_000)
    public void testUserSuppliedOkHttpClientNotDisposedOnReconnect() throws Exception {
        OkHttpClient userClient = new OkHttpClient();

        Options opts = new Options();
        opts.setOkHttpClient(userClient);
        opts.setMinReconnectDelay(10);
        opts.setMaxReconnectDelay(20);

        // Port 1 is never bound: connects are refused immediately, which drives
        // the reconnect path (and its transport disposal) without any server.
        CountDownLatch twoFailedAttempts = new CountDownLatch(2);
        Client client = new Client("ws://127.0.0.1:1/connection/websocket", opts, new EventListener() {
            @Override
            public void onError(Client c, ErrorEvent e) {
                twoFailedAttempts.countDown();
            }
        });
        client.connect();
        assertTrue("expected at least two failed connect attempts",
                twoFailedAttempts.await(8, TimeUnit.SECONDS));

        // Transports are derived from the user's client via newBuilder(), which
        // shares its Dispatcher and ConnectionPool. The reconnect path must not
        // dispose them (#87): with the bug, the shared executor is already shut
        // down by the second attempt, every call on the user's client fails with
        // "executor rejected" and the SDK itself can never reconnect.
        assertFalse("user client's dispatcher executor must stay usable",
                userClient.dispatcher().executorService().isShutdown());

        client.close(1000);
        assertFalse("close() must not dispose a user-supplied client's executor",
                userClient.dispatcher().executorService().isShutdown());
    }
}
