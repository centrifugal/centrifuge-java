package io.github.centrifugal.centrifuge;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.net.Proxy;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * Configuration for a {@link Client} instance.
 */
public class Options {
    public String getToken() {
        return token;
    }

    /**
     * Set connection token. If your tokens expire and you want SDK to automatically
     * refresh tokens then set ConnectionTokenGetter (see below).
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     *  Connection token. This is a token you have to receive from your application backend.
     */
    private String token = "";

    public ConnectionTokenGetter getTokenGetter() {
        return tokenGetter;
    }

    /**
     * Set a method to extract new connection token upon expiration.
     */
    public void setTokenGetter(ConnectionTokenGetter tokenGetter) {
        this.tokenGetter = tokenGetter;
    }

    private ConnectionTokenGetter tokenGetter;

    public String getName() {
        return name;
    }

    /**
     * Set client name - name of this client. This should not be unique per client – it
     * identifies client application name actually, so name should have a limited
     * number of possible values. By default this client uses "java" as a name.
     */
    public void setName(String name) {
        this.name = name;
    }

    private String name = "java";

    public String getVersion() {
        return version;
    }

    /**
     * Set client version - version of application. This may be used for observability
     * on the server (for example in analytics).
     */
    public void setVersion(String version) {
        this.version = version;
    }

    private String version = "";

    public byte[] getData() {
        return data;
    }

    /**
     * Set custom connection data. This data will be delivered to server in Connect command.
     * For Centrifugo this may be useful in case of using connect proxy.
     */
    public void setData(byte[] data) {
        this.data = data;
    }

    private byte[] data;

    /**
     * Set custom headers for WebSocket Upgrade request.
     */
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

    /**
     * Set custom timeout for requests in milliseconds. By default, 5000 is used.
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    private int timeout = 5000;

    public int getMinReconnectDelay() {
        return minReconnectDelay;
    }

    /**
     * Set minimal time before reconnect attempt in milliseconds. By default, 500 is used.
     */
    public void setMinReconnectDelay(int minReconnectDelay) {
        this.minReconnectDelay = minReconnectDelay;
    }

    private int minReconnectDelay = 500;

    public int getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    /**
     * Set max time between reconnect attempts in milliseconds. By default, 20000 is used.
     */
    public void setMaxReconnectDelay(int maxReconnectDelay) {
        this.maxReconnectDelay = maxReconnectDelay;
    }

    private int maxReconnectDelay = 20000;

    public int getMaxServerPingDelay() {
        return maxServerPingDelay;
    }

    /**
     * Set max time of ping delay from server in milliseconds. By default, 10000 is used.
     */
    public void setMaxServerPingDelay(int maxServerPingDelay) {
        this.maxServerPingDelay = maxServerPingDelay;
    }

    private int maxServerPingDelay = 10000;

    public OkHttpClient getOkHttpClient() {
        return this.okHttpClient;
    }

    /**
     * Set OkHttpClient. Can be used to configure custom network settings (DNS, proxy, SSLSocketFactory etc.).
     */
    public void setOkHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    private OkHttpClient okHttpClient;

    /**
     * Set proxy to use.
     */
    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public Proxy getProxy() {
        return proxy;
    }

    private Proxy proxy;

    private String proxyLogin;
    private String proxyPassword;

    /**
     * Set proxy credentials.
     *
     * @deprecated set up proxy in OkHttpClient and pass it to {@link #setOkHttpClient}
     */
    @Deprecated
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

    public Dns getDns() {
        return this.dns;
    }

    /**
     * Set custom DNS resolver.
     *
     * @deprecated set DNS in OkHttpClient and pass it to {@link #setOkHttpClient}
     */
    @Deprecated
    public void setDns(Dns dns) {
        this.dns = dns;
    }

    private Dns dns;
  
    /**
     * Set custom SSLSocketFactory
     *
     * @deprecated set up SSLSocketFactory in OkHttpClient and pass it to {@link #setOkHttpClient}
     */
    public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
    }

    /**
     * Set custom SSLSocketFactory and X509TrustManager
     *
     * @deprecated set up SSLSocketFactory and X509TrustManager in OkHttpClient and pass it to {@link #setOkHttpClient}
     */
    public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory, X509TrustManager trustManager) {
        this.sslSocketFactory = sslSocketFactory;
        this.trustManager = trustManager;
    }

    public SSLSocketFactory getSSLSocketFactory() {
        return this.sslSocketFactory;
    }

    private SSLSocketFactory sslSocketFactory;
    private X509TrustManager trustManager;

    public X509TrustManager getTrustManager() {
        return trustManager;
    }
}
