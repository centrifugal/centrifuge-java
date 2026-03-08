package io.github.centrifugal.centrifuge;

import org.junit.Test;

import static org.junit.Assert.*;

public class OptionsTest {

    @Test
    public void testDefaultValues() {
        Options opts = new Options();
        assertEquals("", opts.getToken());
        assertEquals("java", opts.getName());
        assertEquals("", opts.getVersion());
        assertNull(opts.getData());
        assertNull(opts.getHeaders());
        assertEquals(5000, opts.getTimeout());
        assertEquals(500, opts.getMinReconnectDelay());
        assertEquals(20000, opts.getMaxReconnectDelay());
        assertEquals(10000, opts.getMaxServerPingDelay());
        assertNull(opts.getOkHttpClient());
        assertNull(opts.getProxy());
        assertNull(opts.getProxyLogin());
        assertNull(opts.getProxyPassword());
        assertNull(opts.getDns());
        assertNull(opts.getSSLSocketFactory());
        assertNull(opts.getTrustManager());
    }

    @Test
    public void testSetToken() {
        Options opts = new Options();
        opts.setToken("test-token");
        assertEquals("test-token", opts.getToken());
    }

    @Test
    public void testSetName() {
        Options opts = new Options();
        opts.setName("custom-client");
        assertEquals("custom-client", opts.getName());
    }

    @Test
    public void testSetVersion() {
        Options opts = new Options();
        opts.setVersion("1.0.0");
        assertEquals("1.0.0", opts.getVersion());
    }

    @Test
    public void testSetData() {
        Options opts = new Options();
        byte[] data = new byte[]{1, 2, 3};
        opts.setData(data);
        assertArrayEquals(data, opts.getData());
    }

    @Test
    public void testSetTimeout() {
        Options opts = new Options();
        opts.setTimeout(10000);
        assertEquals(10000, opts.getTimeout());
    }

    @Test
    public void testSetReconnectDelays() {
        Options opts = new Options();
        opts.setMinReconnectDelay(1000);
        opts.setMaxReconnectDelay(30000);
        assertEquals(1000, opts.getMinReconnectDelay());
        assertEquals(30000, opts.getMaxReconnectDelay());
    }

    @Test
    public void testSetMaxServerPingDelay() {
        Options opts = new Options();
        opts.setMaxServerPingDelay(15000);
        assertEquals(15000, opts.getMaxServerPingDelay());
    }

    @Test
    public void testSetProxyCredentials() {
        Options opts = new Options();
        opts.setProxyCredentials("user", "pass");
        assertEquals("user", opts.getProxyLogin());
        assertEquals("pass", opts.getProxyPassword());
    }
}
