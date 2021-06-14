package io.github.centrifugal.centrifuge;

import java.net.Proxy;
import java.util.Map;

/**
 * Configuration for a {@link Client} instance.
 */
public class Options {
    private static final int DEFAULT_TIMEOUT = 5000;
    private static final int DEFAULT_PING_INTERVAL = 25000;

    private int timeout = DEFAULT_TIMEOUT;
    private int pingInterval = DEFAULT_PING_INTERVAL;

    public String getPrivateChannelPrefix() {
        return privateChannelPrefix;
    }

    public void setPrivateChannelPrefix(String privateChannelPrefix) {
        this.privateChannelPrefix = privateChannelPrefix;
    }

    private String privateChannelPrefix = "$";

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    private Map<String, String> headers;

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(int pingInterval) {
        this.pingInterval = pingInterval;
    }

    private Proxy proxy;

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public Proxy getProxy() {
        return proxy;
    }

    private String proxyLogin;
    private String proxyPassword;

    public void setProxyCredentials(String login, String password) {
        this.proxyLogin = login;
        this.proxyPassword = password;
    }

    public String getProxyLogin() {
        return proxyLogin;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }
}
