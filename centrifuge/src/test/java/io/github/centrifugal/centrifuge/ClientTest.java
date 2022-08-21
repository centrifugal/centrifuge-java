package io.github.centrifugal.centrifuge;

import org.junit.Test;
import static org.junit.Assert.*;


public class ClientTest {
    @Test
    public void testInitialization() {
        Client client = new Client("", new Options(), null);
        assertEquals(client.getState(), ClientState.DISCONNECTED);
    }
}
