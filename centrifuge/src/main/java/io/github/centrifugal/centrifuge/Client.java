package io.github.centrifugal.centrifuge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.centrifugal.centrifuge.internal.backoff.Backoff;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

import java8.util.concurrent.CompletableFuture;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;


public class Client {
    private WebSocket ws;
    private final String endpoint;
    private final Options opts;
    private String token;
    private com.google.protobuf.ByteString data;
    private final EventListener listener;
    private final Map<Integer, CompletableFuture<Protocol.Reply>> futures = new ConcurrentHashMap<>();
    private final Map<Integer, Protocol.Command> connectCommands = new ConcurrentHashMap<>();
    private final Map<Integer, Protocol.Command> connectAsyncCommands = new ConcurrentHashMap<>();

    private volatile ClientState state = ClientState.DISCONNECTED;
    private final Map<String, Subscription> subs = new ConcurrentHashMap<>();
    private final Map<String, ServerSubscription> serverSubs = new ConcurrentHashMap<>();
    private final Backoff backoff;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private int pingInterval;
    private boolean sendPong;
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> reconnectTask;
    private int reconnectAttempts = 0;
    private boolean refreshRequired = false;

    Options getOpts() {
        return opts;
    }

    ExecutorService getExecutor() {
        return executor;
    }

    ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private static final int MESSAGE_SIZE_LIMIT_EXCEEDED_STATUS = 1009;

    static final int DISCONNECTED_DISCONNECT_CALLED = 0;
    static final int DISCONNECTED_UNAUTHORIZED = 1;
    static final int DISCONNECTED_BAD_PROTOCOL = 2;
    static final int DISCONNECTED_MESSAGE_SIZE_LIMIT = 3;

    static final int CONNECTING_CONNECT_CALLED = 0;
    static final int CONNECTING_TRANSPORT_CLOSED = 1;
    static final int CONNECTING_NO_PING = 2;
    static final int CONNECTING_SUBSCRIBE_TIMEOUT = 3;
    static final int CONNECTING_UNSUBSCRIBE_ERROR = 4;

    static final int SUBSCRIBING_SUBSCRIBE_CALLED = 0;
    static final int SUBSCRIBING_TRANSPORT_CLOSED = 1;

    static final int UNSUBSCRIBED_UNSUBSCRIBE_CALLED = 0;
    static final int UNSUBSCRIBED_UNAUTHORIZED = 1;
    static final int UNSUBSCRIBED_CLIENT_CLOSED = 2;

    /**
     * Creates a new instance of Client. Client allows to allocate new Subscriptions to channels,
     * automatically manages reconnects and re-subscriptions on temporary failures.
     *
     * @param endpoint: connection endpoint.
     * @param opts:     client options.
     * @param listener: client event handler.
     */
    public Client(final String endpoint, final Options opts, final EventListener listener) {
        this.endpoint = endpoint;
        this.opts = opts;
        this.listener = listener;
        this.backoff = new Backoff();
        this.token = opts.getToken();
        if (opts.getData() != null) {
            this.data = com.google.protobuf.ByteString.copyFrom(opts.getData());
        }
    }

    void setState(ClientState state) {
        this.state = state;
    }

    public ClientState getState() {
        return this.state;
    }

    private int _id = 0;

    private int getNextId() {
        return ++_id;
    }

    /**
     * Start connecting to a server.
     */
    public void connect() {
        this.executor.submit(() -> {
            if (Client.this.getState() == ClientState.CONNECTED || Client.this.getState() == ClientState.CONNECTING) {
                return;
            }
            Client.this.reconnectAttempts = 0;
            Client.this.setState(ClientState.CONNECTING);
            ConnectingEvent event = new ConnectingEvent(CONNECTING_CONNECT_CALLED, "connect called");
            Client.this.listener.onConnecting(Client.this, event);
            Client.this._connect();
        });
    }

    /**
     * Disconnect from server and do not reconnect.
     */
    public void disconnect() {
        this.executor.submit(() -> {
            Client.this.processDisconnect(DISCONNECTED_DISCONNECT_CALLED, "disconnect called", false);
        });
    }

    /**
     * setToken allows updating connection token.
     */
    public void setToken(String token) {
        this.executor.submit(() -> {
            Client.this.token = token;
        });
    }

    /**
     * Close disconnects client from server and cleans up client resources including
     * shutdown of internal executors. Client is not usable after calling this method.
     * If you only need to temporary disconnect from server use .disconnect() method.
     *
     * @param awaitMilliseconds is time in milliseconds to wait for executor
     *                          termination (0 means no waiting).
     * @return boolean that indicates whether executor terminated in awaitMilliseconds. For
     * zero awaitMilliseconds close always returns false.
     */
    public boolean close(long awaitMilliseconds) throws InterruptedException {
        this.disconnect();
        this.executor.shutdown();
        this.scheduler.shutdownNow();
        if (awaitMilliseconds > 0) {
            // Keep this the last, executor will be closed in connection close handler.
            return this.executor.awaitTermination(awaitMilliseconds, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    void processDisconnect(int code, String reason, Boolean shouldReconnect) {
        if (this.getState() == ClientState.DISCONNECTED || this.getState() == ClientState.CLOSED) {
            return;
        }

        ClientState previousState = this.getState();

        if (this.pingTask != null) {
            this.pingTask.cancel(true);
            this.pingTask = null;
        }
        if (this.refreshTask != null) {
            this.refreshTask.cancel(true);
            this.refreshTask = null;
        }
        if (this.reconnectTask != null) {
            this.reconnectTask.cancel(true);
            this.reconnectTask = null;
        }
        boolean needEvent;
        if (shouldReconnect) {
            needEvent = previousState != ClientState.CONNECTING;
            this.setState(ClientState.CONNECTING);
        } else {
            needEvent = previousState != ClientState.DISCONNECTED;
            this.setState(ClientState.DISCONNECTED);
        }

        synchronized (this.subs) {
            for (Map.Entry<String, Subscription> entry : this.subs.entrySet()) {
                Subscription sub = entry.getValue();
                if (sub.getState() == SubscriptionState.UNSUBSCRIBED) {
                    continue;
                }
                sub.moveToSubscribing(SUBSCRIBING_TRANSPORT_CLOSED, "transport closed");
            }
        }

        for (Map.Entry<Integer, CompletableFuture<Protocol.Reply>> entry : this.futures.entrySet()) {
            CompletableFuture<Protocol.Reply> f = entry.getValue();
            f.completeExceptionally(new IOException());
        }

        if (previousState == ClientState.CONNECTED) {
            for (Map.Entry<String, ServerSubscription> entry : this.serverSubs.entrySet()) {
                this.listener.onSubscribing(this, new ServerSubscribingEvent(entry.getKey()));
            }
        }

        if (needEvent) {
            if (shouldReconnect) {
                ConnectingEvent event = new ConnectingEvent(code, reason);
                this.listener.onConnecting(this, event);
            } else {
                DisconnectedEvent event = new DisconnectedEvent(code, reason);
                this.listener.onDisconnected(this, event);
            }
        }

        this.ws.close(NORMAL_CLOSURE_STATUS, null);
    }

    private void _connect() {
        Headers.Builder headers = new Headers.Builder();
        if (this.opts.getHeaders() != null) {
            for (Map.Entry<String, String> entry : this.opts.getHeaders().entrySet()) {
                headers.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(this.endpoint)
                .headers(headers.build())
                .addHeader("Sec-WebSocket-Protocol", "centrifuge-protobuf")
                .build();

        if (this.ws != null) {
            this.ws.cancel();
        }

        OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();

        Dns dns = opts.getDns();
        if (dns != null) {
            okHttpBuilder.dns(dns::resolve);
        }

        if (opts.getProxy() != null) {
            okHttpBuilder.proxy(opts.getProxy());
            if (this.opts.getProxyLogin() != null && this.opts.getProxyPassword() != null) {
                okHttpBuilder.proxyAuthenticator((route, response) -> {
                    String credentials = Credentials.basic(opts.getProxyLogin(), opts.getProxyPassword());

                    return response.request()
                            .newBuilder()
                            .header("Proxy-Authorization", credentials)
                            .build();
                });
            }
        }

        this.ws = (okHttpBuilder.build()).newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                super.onOpen(webSocket, response);
                try {
                    Client.this.executor.submit(() -> {
                        try {
                            Client.this.handleConnectionOpen();
                        } catch (Exception e) {
                            // Should never happen.
                            e.printStackTrace();
                            Client.this.listener.onError(Client.this, new ErrorEvent(new UnclassifiedError(e)));
                            Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (open)", false);
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                super.onMessage(webSocket, bytes);
                try {
                    Client.this.executor.submit(() -> {
                        if (Client.this.getState() != ClientState.CONNECTING && Client.this.getState() != ClientState.CONNECTED) {
                            return;
                        }
                        InputStream stream = new ByteArrayInputStream(bytes.toByteArray());
                        while (true) {
                            Protocol.Reply reply;
                            try {
                                if (stream.available() <= 0) {
                                    break;
                                }
                                reply = Protocol.Reply.parseDelimitedFrom(stream);
                            } catch (IOException e) {
                                // Should never happen. Corrupted server protocol data?
                                e.printStackTrace();
                                Client.this.listener.onError(Client.this, new ErrorEvent(new UnclassifiedError(e)));
                                Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (proto)", false);
                                break;
                            }
                            try {
                                Client.this.processReply(reply);
                            } catch (Exception e) {
                                // Should never happen. Most probably indicates an unexpected exception coming from the user-level code.
                                // Theoretically may indicate a bug of SDK also – stack trace will help here.
                                e.printStackTrace();
                                Client.this.listener.onError(Client.this, new ErrorEvent(new UnclassifiedError(e)));
                                Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (message)", false);
                                break;
                            }
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                super.onClosing(webSocket, code, reason);
                webSocket.close(NORMAL_CLOSURE_STATUS, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                super.onClosed(webSocket, code, reason);
                try {
                    Client.this.executor.submit(() -> {
                        boolean reconnect = code < 3500 || code >= 5000 || (code >= 4000 && code < 4500);
                        int disconnectCode = code;
                        String disconnectReason = reason;
                        if (disconnectCode < 3000) {
                            if (disconnectCode == MESSAGE_SIZE_LIMIT_EXCEEDED_STATUS) {
                                disconnectCode = DISCONNECTED_MESSAGE_SIZE_LIMIT;
                                disconnectReason = "message size limit";
                            } else {
                                disconnectCode = CONNECTING_TRANSPORT_CLOSED;
                                disconnectReason = "transport closed";
                            }
                        }
                        if (Client.this.getState() != ClientState.DISCONNECTED) {
                            Client.this.processDisconnect(disconnectCode, disconnectReason, reconnect);
                        }
                        if (Client.this.getState() == ClientState.CONNECTING) {
                            Client.this.scheduleReconnect();
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                try {
                    Client.this.executor.submit(() -> {
                        Integer responseCode = (response != null) ? response.code() : null;
                        listener.onError(Client.this, new ErrorEvent(t, responseCode));
                        Client.this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
                        if (Client.this.getState() == ClientState.CONNECTING) {
                            // We need to schedule reconnect from here, since onClosed won't be called
                            // after onFailure.
                            Client.this.scheduleReconnect();
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }
        });
    }

    private void handleConnectionOpen() {
        if (this.getState() != ClientState.CONNECTING) {
            return;
        }
        if (this.refreshRequired) {
            if (this.data == null && this.opts.getDataGetter() != null) {
                ConnectionDataEvent connectionDataEvent = new ConnectionDataEvent();
                if (this.opts.getDataGetter() == null) {
                    this.listener.onError(Client.this, new ErrorEvent(new ConfigurationError(new Exception("dataGetter function should be provided in Client options to handle token refresh, see Options.setTokenGetter"))));
                    this.processDisconnect(DISCONNECTED_UNAUTHORIZED, "unauthorized", false);
                    return;
                }
                this.opts.getDataGetter().getConnectionData(connectionDataEvent, (err, data) -> this.executor.submit(() -> {
                    if (Client.this.getState() != ClientState.CONNECTING) {
                        return;
                    }
                    if (err != null) {
                        if (err instanceof UnauthorizedException) {
                            Client.this.failUnauthorized();
                            return;
                        }
                        Client.this.listener.onError(Client.this, new ErrorEvent(new TokenError(err)));
                        this.ws.close(NORMAL_CLOSURE_STATUS, "");
                        return;
                    }
                    if (data == null) {
                        Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (data)", false);
                        return;
                    }
                    Client.this.data = com.google.protobuf.ByteString.copyFrom(data);
                    Client.this.refreshRequired = false;
                    this.sendConnect();
                }));

            } else if (this.token.equals("") && this.opts.getTokenGetter() != null) {
                ConnectionTokenEvent connectionTokenEvent = new ConnectionTokenEvent();
                if (this.opts.getTokenGetter() == null) {
                    this.listener.onError(Client.this, new ErrorEvent(new ConfigurationError(new Exception("dataGetter function should be provided in Client options to handle token refresh, see Options.setTokenGetter"))));
                    this.processDisconnect(DISCONNECTED_UNAUTHORIZED, "unauthorized", false);
                    return;
                }
                this.opts.getTokenGetter().getConnectionToken(connectionTokenEvent, (err, token) -> this.executor.submit(() -> {
                    if (Client.this.getState() != ClientState.CONNECTING) {
                        return;
                    }
                    if (err != null) {
                        if (err instanceof UnauthorizedException) {
                            Client.this.failUnauthorized();
                            return;
                        }
                        Client.this.listener.onError(Client.this, new ErrorEvent(new TokenError(err)));
                        this.ws.close(NORMAL_CLOSURE_STATUS, "");
                        return;
                    }
                    if (token == null) {
                        Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (data)", false);
                        return;
                    }
                    Client.this.token = token;
                    Client.this.refreshRequired = false;
                    this.sendConnect();
                }));
            } else {
                this.sendConnect();
            }
        } else {
            this.sendConnect();
        }
    }

    private void handleConnectionError(Throwable t) {
        this.listener.onError(this, new ErrorEvent(t));
    }

    private void startReconnecting() {
        Client.this.executor.submit(() -> {
            if (Client.this.getState() != ClientState.CONNECTING) {
                return;
            }
            Client.this._connect();
        });
    }

    private void scheduleReconnect() {
        if (this.getState() != ClientState.CONNECTING) {
            return;
        }
        this.reconnectTask = this.scheduler.schedule(
                Client.this::startReconnecting,
                Client.this.backoff.duration(this.reconnectAttempts, this.opts.getMinReconnectDelay(), this.opts.getMaxReconnectDelay()),
                TimeUnit.MILLISECONDS
        );
        this.reconnectAttempts++;
    }

    private void sendSubscribeSynchronized(String channel, Protocol.SubscribeRequest req) {
        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setSubscribe(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            if (Client.this.getState() != ClientState.CONNECTED) {
                return;
            }
            try {
                this.handleSubscribeReply(channel, reply);
            } catch (Exception e) {
                // Should never happen.
                e.printStackTrace();
            }
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                if (Client.this.getState() != ClientState.CONNECTED) {
                    return;
                }
                Client.this.futures.remove(cmd.getId());
                Client.this.processDisconnect(CONNECTING_SUBSCRIBE_TIMEOUT, "subscribe timeout", true);
            });
            return null;
        });
        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    void sendSubscribe(Subscription sub, Protocol.SubscribeRequest req) {
        if (this.getState() != ClientState.CONNECTED) {
            // Subscription registered and will start subscribing as soon as
            // client will be connected.
            return;
        }
        this.sendSubscribeSynchronized(sub.getChannel(), req);
    }

    void sendUnsubscribe(String channel) {
        this.executor.submit(() -> Client.this.sendUnsubscribeSynchronized(channel));
    }

    private void sendUnsubscribeSynchronized(String channel) {
        if (this.getState() != ClientState.CONNECTED) {
            return;
        }
        Protocol.UnsubscribeRequest req = Protocol.UnsubscribeRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setUnsubscribe(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            // No need to handle reply for now.
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.futures.remove(cmd.getId());
            this.processDisconnect(CONNECTING_UNSUBSCRIBE_ERROR, "unsubscribe error", true);
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    private byte[] serializeCommand(Protocol.Command cmd) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            cmd.writeDelimitedTo(stream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return stream.toByteArray();
    }

    private Subscription getSub(String channel) {
        return this.subs.get(channel);
    }

    private ServerSubscription getServerSub(String channel) {
        return this.serverSubs.get(channel);
    }

    /**
     * Create new subscription to channel with SubscriptionOptions and SubscriptionEventListener
     *
     * @param channel:  to create Subscription for.
     * @param options:  to pass SubscriptionOptions, e.g. token.
     * @param listener: to pass event handler.
     * @return Subscription.
     * @throws DuplicateSubscriptionException if Subscription already exists in internal registry.
     */
    public Subscription newSubscription(String channel, SubscriptionOptions options, SubscriptionEventListener listener) throws DuplicateSubscriptionException {
        Subscription sub;
        synchronized (this.subs) {
            if (this.subs.get(channel) != null) {
                throw new DuplicateSubscriptionException();
            }
            sub = new Subscription(Client.this, channel, listener, options);
            this.subs.put(channel, sub);
        }
        return sub;
    }

    /**
     * Create new subscription to channel with SubscriptionEventListener, see also
     * overloaded newSubscription which allows setting SubscriptionOptions
     *
     * @param channel:  to create Subscription for.
     * @param listener: to pass event handler.
     * @return Subscription.
     * @throws DuplicateSubscriptionException if Subscription already exists in internal registry.
     */
    public Subscription newSubscription(String channel, SubscriptionEventListener listener) throws DuplicateSubscriptionException {
        return newSubscription(channel, new SubscriptionOptions(), listener);
    }

    /**
     * Try to get Subscription from internal client registry. Can return null if Subscription
     * does not exist yet.
     *
     * @param channel: channel to get subscription for.
     * @return Subscription
     */
    public Subscription getSubscription(String channel) {
        Subscription sub;
        synchronized (this.subs) {
            sub = this.getSub(channel);
        }
        return sub;
    }

    /**
     * Say Client that Subscription should be removed from internal registry. Subscription will be
     * automatically unsubscribed before removing.
     *
     * @param sub: Subscription to remove from registry.
     */
    public void removeSubscription(Subscription sub) {
        synchronized (this.subs) {
            sub.unsubscribe();
            if (this.subs.get(sub.getChannel()) != null) {
                this.subs.remove(sub.getChannel());
            }
        }
    }

    private void handleSubscribeReply(String channel, Protocol.Reply reply) throws Exception {
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            Protocol.SubscribeResult result;
            if (reply.getError().getCode() != 0) {
                ReplyError err = new ReplyError(reply.getError().getCode(), reply.getError().getMessage(), reply.getError().getTemporary());
                sub.subscribeError(err);
                return;
            }
            result = reply.getSubscribe();
            sub.moveToSubscribed(result);
        }
    }

    private void _waitServerPing() {
        if (this.getState() != ClientState.CONNECTED) {
            return;
        }
        Client.this.processDisconnect(CONNECTING_NO_PING, "no ping", true);
    }

    private void waitServerPing() {
        this.executor.submit(Client.this::_waitServerPing);
    }

    private void handleConnectReply(Protocol.Reply reply) {
        if (this.getState() != ClientState.CONNECTING) {
            return;
        }
        if (reply.getError().getCode() != 0) {
            this.handleConnectionError(new ReplyError(reply.getError().getCode(), reply.getError().getMessage(), reply.getError().getTemporary()));
            if (reply.getError().getCode() == 109) { // Token expired.
                this.refreshRequired = true;
                this.data = null;
                this.ws.close(NORMAL_CLOSURE_STATUS, "");
            } else if (reply.getError().getTemporary()) {
                this.ws.close(NORMAL_CLOSURE_STATUS, "");
            } else {
                this.processDisconnect(reply.getError().getCode(), reply.getError().getMessage(), false);
            }
            return;
        }
        Protocol.ConnectResult result = reply.getConnect();
        ConnectedEvent event = new ConnectedEvent();
        event.setClient(result.getClient());
        event.setData(result.getData().toByteArray());
        this.setState(ClientState.CONNECTED);
        this.listener.onConnected(this, event);
        this.pingInterval = result.getPing() * 1000;
        this.sendPong = result.getPong();

        synchronized (this.subs) {
            for (Map.Entry<String, Subscription> entry : this.subs.entrySet()) {
                Subscription sub = entry.getValue();
                sub.resubscribeIfNecessary();
            }
        }

        for (Map.Entry<String, Protocol.SubscribeResult> entry : result.getSubsMap().entrySet()) {
            Protocol.SubscribeResult subResult = entry.getValue();
            String channel = entry.getKey();
            ServerSubscription serverSub;
            if (this.serverSubs.containsKey(channel)) {
                serverSub = this.serverSubs.get(channel);
            } else {
                serverSub = new ServerSubscription(subResult.getRecoverable(), subResult.getOffset(), subResult.getEpoch());
                this.serverSubs.put(channel, serverSub);
            }
            serverSub.setRecoverable(subResult.getRecoverable());
            serverSub.setLastEpoch(subResult.getEpoch());

            byte[] data = null;
            if (subResult.getData() != null) {
                data = subResult.getData().toByteArray();
            }

            this.listener.onSubscribed(this, new ServerSubscribedEvent(channel, subResult.getWasRecovering(), subResult.getRecovered(), subResult.getPositioned(), subResult.getRecoverable(), subResult.getPositioned() || subResult.getRecoverable() ? new StreamPosition(subResult.getOffset(), subResult.getEpoch()) : null, data));
            if (subResult.getPublicationsCount() > 0) {
                for (Protocol.Publication publication : subResult.getPublicationsList()) {
                    ServerPublicationEvent publicationEvent = new ServerPublicationEvent();
                    publicationEvent.setChannel(channel);
                    publicationEvent.setData(publication.getData().toByteArray());
                    publicationEvent.setTags(publication.getTagsMap());
                    ClientInfo info = ClientInfo.fromProtocolClientInfo(publication.getInfo());
                    publicationEvent.setInfo(info);
                    publicationEvent.setOffset(publication.getOffset());
                    if (publication.getOffset() > 0) {
                        serverSub.setLastOffset(publication.getOffset());
                    }
                    this.listener.onPublication(this, publicationEvent);
                }
            } else {
                serverSub.setLastOffset(subResult.getOffset());
            }
        }

        Iterator<Map.Entry<String, ServerSubscription>> it = this.serverSubs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ServerSubscription> entry = it.next();
            if (!result.getSubsMap().containsKey(entry.getKey())) {
                this.listener.onUnsubscribed(this, new ServerUnsubscribedEvent(entry.getKey()));
                it.remove();
            }
        }

        this.reconnectAttempts = 0;

        for (Map.Entry<Integer, Protocol.Command> entry : this.connectCommands.entrySet()) {
            Protocol.Command cmd = entry.getValue();
            boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
            if (!sent) {
                CompletableFuture<Protocol.Reply> f = this.futures.get(cmd.getId());
                if (f != null) {
                    f.completeExceptionally(new IOException());
                }
            }
        }

        this.connectCommands.clear();

        for (Map.Entry<Integer, Protocol.Command> entry : this.connectAsyncCommands.entrySet()) {
            Protocol.Command cmd = entry.getValue();
            CompletableFuture<Protocol.Reply> f = this.futures.get(cmd.getId());
            boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
            if (!sent) {
                if (f != null) {
                    f.completeExceptionally(new IOException());
                }
            } else {
                if (f != null) {
                    f.complete(null);
                }
            }
        }
        this.connectAsyncCommands.clear();

        this.pingTask = this.scheduler.schedule(Client.this::waitServerPing, this.pingInterval + this.opts.getMaxServerPingDelay(), TimeUnit.MILLISECONDS);

        if (result.getExpires()) {
            int ttl = result.getTtl();
            this.refreshTask = this.scheduler.schedule(Client.this::sendRefresh, ttl, TimeUnit.SECONDS);
        }
    }

    private void sendRefresh() {

        if (this.opts.getDataGetter() != null) {
            this.executor.submit(() -> Client.this.opts.getDataGetter().getConnectionData(new ConnectionDataEvent(), (err, data) -> Client.this.executor.submit(() -> {
                if (Client.this.getState() != ClientState.CONNECTED) {
                    return;
                }
                if (err != null) {
                    if (err instanceof UnauthorizedException) {
                        Client.this.failUnauthorized();
                        return;
                    }
                    Client.this.listener.onError(Client.this, new ErrorEvent(new TokenError(err)));
                    Client.this.refreshTask = Client.this.scheduler.schedule(
                            Client.this::sendRefresh,
                            Client.this.backoff.duration(0, 10000, 20000),
                            TimeUnit.MILLISECONDS
                    );
                    return;
                }
                if (data == null || data.length == 0) {
                    this.failUnauthorized();
                    return;
                }
                Client.this.data = com.google.protobuf.ByteString.copyFrom(data);
                refreshSynchronized(data, null, (error, result) -> {
                    if (Client.this.getState() != ClientState.CONNECTED) {
                        return;
                    }
                    if (error != null) {
                        Client.this.listener.onError(Client.this, new ErrorEvent(new RefreshError(error)));
                        if (error instanceof ReplyError) {
                            ReplyError e;
                            e = (ReplyError) error;
                            if (e.isTemporary()) {
                                Client.this.refreshTask = Client.this.scheduler.schedule(
                                        Client.this::sendRefresh,
                                        Client.this.backoff.duration(0, 10000, 20000),
                                        TimeUnit.MILLISECONDS
                                );
                            } else {
                                Client.this.processDisconnect(e.getCode(), e.getMessage(), false);
                            }
                            return;
                        } else {
                            Client.this.refreshTask = Client.this.scheduler.schedule(
                                    Client.this::sendRefresh,
                                    Client.this.backoff.duration(0, 10000, 20000),
                                    TimeUnit.MILLISECONDS
                            );
                        }
                        return;
                    }
                    if (result.getExpires()) {
                        int ttl = result.getTtl();
                        Client.this.refreshTask = Client.this.scheduler.schedule(Client.this::sendRefresh, ttl, TimeUnit.SECONDS);
                    }
                });
            })));
        } else if (this.opts.getTokenGetter() != null) {
            this.executor.submit(() -> Client.this.opts.getTokenGetter().getConnectionToken(new ConnectionTokenEvent(), (err, token) -> Client.this.executor.submit(() -> {
                if (Client.this.getState() != ClientState.CONNECTED) {
                    return;
                }
                if (err != null) {
                    if (err instanceof UnauthorizedException) {
                        Client.this.failUnauthorized();
                        return;
                    }
                    Client.this.listener.onError(Client.this, new ErrorEvent(new TokenError(err)));
                    Client.this.refreshTask = Client.this.scheduler.schedule(
                            Client.this::sendRefresh,
                            Client.this.backoff.duration(0, 10000, 20000),
                            TimeUnit.MILLISECONDS
                    );
                    return;
                }
                if (token == null || token.equals("")) {
                    this.failUnauthorized();
                    return;
                }
                Client.this.token = token;
                refreshSynchronized(null, token, (error, result) -> {
                    if (Client.this.getState() != ClientState.CONNECTED) {
                        return;
                    }
                    if (error != null) {
                        Client.this.listener.onError(Client.this, new ErrorEvent(new RefreshError(error)));
                        if (error instanceof ReplyError) {
                            ReplyError e;
                            e = (ReplyError) error;
                            if (e.isTemporary()) {
                                Client.this.refreshTask = Client.this.scheduler.schedule(
                                        Client.this::sendRefresh,
                                        Client.this.backoff.duration(0, 10000, 20000),
                                        TimeUnit.MILLISECONDS
                                );
                            } else {
                                Client.this.processDisconnect(e.getCode(), e.getMessage(), false);
                            }
                            return;
                        } else {
                            Client.this.refreshTask = Client.this.scheduler.schedule(
                                    Client.this::sendRefresh,
                                    Client.this.backoff.duration(0, 10000, 20000),
                                    TimeUnit.MILLISECONDS
                            );
                        }
                        return;
                    }
                    if (result.getExpires()) {
                        int ttl = result.getTtl();
                        Client.this.refreshTask = Client.this.scheduler.schedule(Client.this::sendRefresh, ttl, TimeUnit.SECONDS);
                    }
                });
            })));
        }
    }

    private void sendConnect() {
        Protocol.ConnectRequest.Builder build = Protocol.ConnectRequest.newBuilder();
        if (this.token.length() > 0) build.setToken(this.token);
        if (this.opts.getName().length() > 0) build.setName(this.opts.getName());
        if (this.opts.getVersion().length() > 0) build.setVersion(this.opts.getVersion());
        if (this.data != null) build.setData(this.data);
        if (this.serverSubs.size() > 0) {
            for (Map.Entry<String, ServerSubscription> entry : this.serverSubs.entrySet()) {
                Protocol.SubscribeRequest.Builder subReqBuild = Protocol.SubscribeRequest.newBuilder();
                if (entry.getValue().getRecoverable()) {
                    subReqBuild.setEpoch(entry.getValue().getEpoch());
                    subReqBuild.setOffset(entry.getValue().getOffset());
                    subReqBuild.setRecover(true);
                }
                build.putSubs(entry.getKey(), subReqBuild.build());
            }
        }
        Protocol.ConnectRequest req = build.build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setConnect(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.futures.remove(cmd.getId());
            try {
                this.handleConnectReply(reply);
            } catch (Exception e) {
                // Should never happen.
                e.printStackTrace();
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.handleConnectionError(e);
            this.futures.remove(cmd.getId());
            this.ws.close(NORMAL_CLOSURE_STATUS, "");
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    private void failUnauthorized() {
        this.processDisconnect(DISCONNECTED_UNAUTHORIZED, "unauthorized", false);
    }

    private void processReply(Protocol.Reply reply) throws Exception {
        if (reply.getId() > 0) {
            CompletableFuture<Protocol.Reply> cf = this.futures.get(reply.getId());
            if (cf != null) cf.complete(reply);
        } else {
            if (reply.hasPush()) {
                this.handlePush(reply.getPush());
            } else {
                this.handlePing();
            }
        }
    }

    void handlePub(String channel, Protocol.Publication pub) throws Exception {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(pub.getInfo());
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            sub.handlePublication(pub);
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                ServerPublicationEvent event = new ServerPublicationEvent();
                event.setChannel(channel);
                event.setData(pub.getData().toByteArray());
                event.setInfo(info);
                event.setOffset(pub.getOffset());
                event.setTags(pub.getTagsMap());
                if (pub.getOffset() > 0) {
                    serverSub.setLastOffset(pub.getOffset());
                }
                this.listener.onPublication(this, event);
            }
        }
    }

    private void handleSubscribe(String channel, Protocol.Subscribe sub) {
        ServerSubscription serverSub = new ServerSubscription(sub.getRecoverable(), sub.getOffset(), sub.getEpoch());
        this.serverSubs.put(channel, serverSub);
        serverSub.setRecoverable(sub.getRecoverable());
        serverSub.setLastEpoch(sub.getEpoch());
        serverSub.setLastOffset(sub.getOffset());
        byte[] data = null;
        if (sub.getData() != null) {
            data = sub.getData().toByteArray();
        }
        this.listener.onSubscribed(this, new ServerSubscribedEvent(channel, false, false, sub.getPositioned(), sub.getRecoverable(), sub.getPositioned() || sub.getRecoverable() ? new StreamPosition(sub.getOffset(), sub.getEpoch()) : null, data));
    }

    private void handleUnsubscribe(String channel, Protocol.Unsubscribe unsubscribe) {
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            if (unsubscribe.getCode() < 2500) {
                sub.moveToUnsubscribed(false, unsubscribe.getCode(), unsubscribe.getReason());
            } else {
                sub.moveToSubscribing(unsubscribe.getCode(), unsubscribe.getReason());
                sub.resubscribeIfNecessary();
            }
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                this.serverSubs.remove(channel);
                this.listener.onUnsubscribed(this, new ServerUnsubscribedEvent(channel));
            }
        }
    }

    private void handleJoin(String channel, Protocol.Join join) {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(join.getInfo());
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            JoinEvent event = new JoinEvent();
            event.setInfo(info);
            sub.getListener().onJoin(sub, event);
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                this.listener.onJoin(this, new ServerJoinEvent(channel, info));
            }
        }
    }

    private void handleLeave(String channel, Protocol.Leave leave) {
        LeaveEvent event = new LeaveEvent();
        ClientInfo info = ClientInfo.fromProtocolClientInfo(leave.getInfo());

        Subscription sub = this.getSub(channel);
        if (sub != null) {
            event.setInfo(info);
            sub.getListener().onLeave(sub, event);
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                this.listener.onLeave(this, new ServerLeaveEvent(channel, info));
            }
        }
    }

    private void handleMessage(Protocol.Message msg) {
        MessageEvent event = new MessageEvent();
        event.setData(msg.getData().toByteArray());
        this.listener.onMessage(this, event);
    }

    private void handleDisconnect(Protocol.Disconnect disconnect) {
        int code = disconnect.getCode();
        boolean reconnect = code < 3500 || code >= 5000 || (code >= 4000 && code < 4500);
        if (Client.this.getState() != ClientState.DISCONNECTED) {
            Client.this.processDisconnect(code, disconnect.getReason(), reconnect);
        }
    }

    private void handlePush(Protocol.Push push) throws Exception {
        String channel = push.getChannel();
        if (push.hasPub()) {
            this.handlePub(channel, push.getPub());
        } else if (push.hasSubscribe()) {
            this.handleSubscribe(channel, push.getSubscribe());
        } else if (push.hasJoin()) {
            this.handleJoin(channel, push.getJoin());
        } else if (push.hasLeave()) {
            this.handleLeave(channel, push.getLeave());
        } else if (push.hasUnsubscribe()) {
            this.handleUnsubscribe(channel, push.getUnsubscribe());
        } else if (push.hasMessage()) {
            this.handleMessage(push.getMessage());
        } else if (push.hasDisconnect()) {
            this.handleDisconnect(push.getDisconnect());
        }
    }

    private void handlePing() {
        if (this.pingTask != null) {
            this.pingTask.cancel(true);
        }
        this.pingTask = this.scheduler.schedule(Client.this::waitServerPing, this.pingInterval + this.opts.getMaxServerPingDelay(), TimeUnit.MILLISECONDS);
        if (this.sendPong) {
            // Empty command as a ping.
            Protocol.Command cmd = Protocol.Command.newBuilder().build();
            this.ws.send(ByteString.of(this.serializeCommand(cmd)));
        }
    }

    /**
     * Send asynchronous message with data to server. Callback successfully completes if data
     * written to connection. No reply from server expected in this case.
     *
     * @param data: custom data to publish.
     * @param cb:   will be called as soon as data sent to the connection or error happened.
     */
    public void send(byte[] data, CompletionCallback cb) {
        this.executor.submit(() -> Client.this.sendSynchronized(data, cb));
    }

    private void sendSynchronized(byte[] data, CompletionCallback cb) {
        Protocol.SendRequest req = Protocol.SendRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setSend(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            cb.onDone(null);
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                this.cleanCommandFuture(cmd);
                cb.onDone(e);
            });
            return null;
        });

        if (this.getState() != ClientState.CONNECTED) {
            this.connectAsyncCommands.put(cmd.getId(), cmd);
        } else {
            boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
            if (!sent) {
                f.completeExceptionally(new IOException());
            } else {
                f.complete(null);
            }
        }
    }

    private void enqueueCommandFuture(Protocol.Command cmd, CompletableFuture<Protocol.Reply> f) {
        this.futures.put(cmd.getId(), f);
        if (this.getState() != ClientState.CONNECTED) {
            this.connectCommands.put(cmd.getId(), cmd);
        } else {
            boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
            if (!sent) {
                f.completeExceptionally(new IOException());
            }
        }
    }

    ReplyError getReplyError(Protocol.Reply reply) {
        return new ReplyError(reply.getError().getCode(), reply.getError().getMessage(), reply.getError().getTemporary());
    }

    private void cleanCommandFuture(Protocol.Command cmd) {
        this.futures.remove(cmd.getId());
        if (this.connectCommands.get(cmd.getId()) != null) {
            this.connectCommands.remove(cmd.getId());
        }
        if (Client.this.connectAsyncCommands.get(cmd.getId()) != null) {
            Client.this.connectAsyncCommands.remove(cmd.getId());
        }
    }

    /**
     * Send RPC with method to server, process result in callback.
     *
     * @param method: of RPC call.
     * @param data:   a custom payload for RPC call.
     * @param cb:     will be called as soon as rpc response received or error happened.
     */
    public void rpc(String method, byte[] data, ResultCallback<RPCResult> cb) {
        this.executor.submit(() -> Client.this.rpcSynchronized(method, data, cb));
    }

    private void rpcSynchronized(String method, byte[] data, ResultCallback<RPCResult> cb) {
        Protocol.RPCRequest req = Protocol.RPCRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .setMethod(method)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setRpc(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.RPCResult rpcResult = reply.getRpc();
                RPCResult result = new RPCResult();
                result.setData(rpcResult.getData().toByteArray());
                cb.onDone(null, result);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    /**
     * Publish data to channel without being subscribed to it. Publish option should be
     * enabled in Centrifuge/Centrifugo server configuration.
     *
     * @param channel: to publish into.
     * @param data:    to publish.
     * @param cb:      will be called as soon as publish response received or error happened.
     */
    public void publish(String channel, byte[] data, ResultCallback<PublishResult> cb) {
        this.executor.submit(() -> Client.this.publishSynchronized(channel, data, cb));
    }

    private void publishSynchronized(String channel, byte[] data, ResultCallback<PublishResult> cb) {
        Protocol.PublishRequest req = Protocol.PublishRequest.newBuilder()
                .setChannel(channel)
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setPublish(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                PublishResult result = new PublishResult();
                cb.onDone(null, result);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    /**
     * History can get channel publication history (useful for server-side subscriptions).
     *
     * @param channel: to get history for.
     * @param opts:    for history request.
     * @param cb:      will be called as soon as response receive or error happened.
     */
    public void history(String channel, HistoryOptions opts, ResultCallback<HistoryResult> cb) {
        this.executor.submit(() -> Client.this.historySynchronized(channel, opts, cb));
    }

    private void historySynchronized(String channel, HistoryOptions opts, ResultCallback<HistoryResult> cb) {
        Protocol.HistoryRequest.Builder builder = Protocol.HistoryRequest.newBuilder()
                .setChannel(channel)
                .setReverse(opts.getReverse())
                .setLimit(opts.getLimit());
        if (opts.getSince() != null) {
            builder.setSince(opts.getSince().toProto());
        }
        Protocol.HistoryRequest req = builder.build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setHistory(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.HistoryResult replyResult = reply.getHistory();
                HistoryResult result = new HistoryResult();
                List<Protocol.Publication> protoPubs = replyResult.getPublicationsList();
                List<Publication> pubs = new ArrayList<>();
                for (int i = 0; i < protoPubs.size(); i++) {
                    Protocol.Publication protoPub = protoPubs.get(i);
                    Publication pub = new Publication();
                    pub.setData(protoPub.getData().toByteArray());
                    pub.setOffset(protoPub.getOffset());
                    pub.setInfo(ClientInfo.fromProtocolClientInfo(protoPub.getInfo()));
                    pubs.add(pub);
                }
                result.setPublications(pubs);
                result.setOffset(replyResult.getOffset());
                result.setEpoch(replyResult.getEpoch());
                cb.onDone(null, result);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    public void presence(String channel, ResultCallback<PresenceResult> cb) {
        this.executor.submit(() -> Client.this.presenceSynchronized(channel, cb));
    }

    private void presenceSynchronized(String channel, ResultCallback<PresenceResult> cb) {
        Protocol.PresenceRequest req = Protocol.PresenceRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setPresence(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.PresenceResult replyResult = reply.getPresence();
                Map<String, Protocol.ClientInfo> protoPresence = replyResult.getPresenceMap();
                Map<String, ClientInfo> presence = new HashMap<>();
                for (Map.Entry<String, Protocol.ClientInfo> entry : protoPresence.entrySet()) {
                    Protocol.ClientInfo protoClientInfo = entry.getValue();
                    presence.put(entry.getKey(), ClientInfo.fromProtocolClientInfo(protoClientInfo));
                }
                PresenceResult result = new PresenceResult(presence);
                cb.onDone(null, result);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    public void presenceStats(String channel, ResultCallback<PresenceStatsResult> cb) {
        this.executor.submit(() -> Client.this.presenceStatsSynchronized(channel, cb));
    }

    private void presenceStatsSynchronized(String channel, ResultCallback<PresenceStatsResult> cb) {
        Protocol.PresenceStatsRequest req = Protocol.PresenceStatsRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setPresenceStats(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.PresenceStatsResult replyResult = reply.getPresenceStats();
                PresenceStatsResult result = new PresenceStatsResult();
                result.setNumClients(replyResult.getNumClients());
                result.setNumUsers(replyResult.getNumUsers());
                cb.onDone(null, result);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    private void refreshSynchronized(byte[] data, String token, ResultCallback<Protocol.RefreshResult> cb) {
        Protocol.RefreshRequest.Builder req = Protocol.RefreshRequest.newBuilder();

        if (data != null) {
            req.setTokenBytes(com.google.protobuf.ByteString.copyFrom(data));
        }

        if (token != null) {
            req.setToken(token);
        }

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setRefresh(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.RefreshResult replyResult = reply.getRefresh();
                cb.onDone(null, replyResult);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    void subRefreshSynchronized(String channel, String token, ResultCallback<Protocol.SubRefreshResult> cb) {
        Protocol.SubRefreshRequest req = Protocol.SubRefreshRequest.newBuilder()
                .setToken(token)
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setSubRefresh(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.SubRefreshResult replyResult = reply.getSubRefresh();
                cb.onDone(null, replyResult);
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }
}
