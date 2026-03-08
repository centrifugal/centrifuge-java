package io.github.centrifugal.centrifuge;

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
}
