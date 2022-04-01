package io.github.centrifugal.centrifuge;

import java.net.Proxy;
import java.util.Map;

/**
 * Configuration for a {@link Client} instance.
 */
public class Options {
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /* Connection token. This is a token you have to receive from your application backend. */
    private String token = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

     /**
     * Set client name - name of this client. This should not be unique per client – it
     * identifies client application name actually, so name should have a limited
     * number of possible values. By default this client uses "java" as a name.
     */
    private String name = "java";

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    private String version = "";

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    /* Connect data to send to a server inside Connect command. */
    private byte[] data;

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

    public int getMinReconnectDelay() {
        return minReconnectDelay;
    }

    public void setMinReconnectDelay(int minReconnectDelay) {
        this.minReconnectDelay = minReconnectDelay;
    }

    private int minReconnectDelay = 500;

    public int getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public void setMaxReconnectDelay(int maxReconnectDelay) {
        this.maxReconnectDelay = maxReconnectDelay;
    }

    private int maxReconnectDelay = 20000;

    private int timeout = 5000;

    public int getMaxServerPingDelay() {
        return maxServerPingDelay;
    }

    public void setMaxServerPingDelay(int maxServerPingDelay) {
        this.maxServerPingDelay = maxServerPingDelay;
    }

    private int maxServerPingDelay = 10000;

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public Proxy getProxy() {
        return proxy;
    }

    private Proxy proxy;

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
