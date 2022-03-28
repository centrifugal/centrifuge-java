package io.github.centrifugal.centrifuge;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final String url;

    Options getOpts() {
        return opts;
    }

    private final Options opts;
    private String token = "";
    private String name = "java";
    private String version = "";
    private com.google.protobuf.ByteString connectData;
    private final EventListener listener;
    private String client;
    private final Map<Integer, CompletableFuture<Protocol.Reply>> futures = new ConcurrentHashMap<>();
    private final Map<Integer, Protocol.Command> connectCommands = new ConcurrentHashMap<>();
    private final Map<Integer, Protocol.Command> connectAsyncCommands = new ConcurrentHashMap<>();

    ConnectionState getState() {
        return state;
    }

    private ConnectionState state = ConnectionState.NEW;
    private final Map<String, Subscription> subs = new ConcurrentHashMap<>();
    private final Map<String, ServerSubscription> serverSubs = new ConcurrentHashMap<>();
    private Boolean connecting = false;
    private Boolean disconnecting = false;
    private final Backoff backoff;
    private Boolean needReconnect = true;
    private Boolean closing;

    ExecutorService getExecutor() {
        return executor;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> refreshTask;
    private String disconnectReason = "";
    private int disconnectCode = 0;
    private boolean clientDisconnect = false;

    private static final int NORMAL_CLOSURE_STATUS = 1000;

    /**
     * Set connection JWT. This is a token you have to receive from your application backend.
     *
     * @param token is a token for a client authentication.
     */
    public void setToken(String token) {
        this.executor.submit(() -> {
            Client.this.token = token;
        });
    }

    /**
     * Set client name.
     *
     * @param name sets name of this client. This should not be unique per client – it
     *             identifies client application name actually, so name should have a limited
     *             number of possible values. By default this client uses "java" as a name.
     */
    public void setName(String name) {
        this.executor.submit(() -> {
            Client.this.name = name;
        });
    }

    /**
     * Set client version.
     *
     * @param version means version of client.
     */
    public void setVersion(String version) {
        this.executor.submit(() -> {
            Client.this.version = version;
        });
    }

    /**
     * Set connect data allows to send custom data to server inside Connect command.
     *
     * @param data to be sent.
     */
    public void setConnectData(byte[] data) {
        this.executor.submit(() -> {
            Client.this.connectData = com.google.protobuf.ByteString.copyFrom(data);
        });
    }

    /**
     * Creates a new instance of Client. Client allows to allocate new Subscriptions to channels,
     * automatically manages reconnects.
     *
     * @param url
     * @param opts
     * @param listener
     */
    public Client(final String url, final Options opts, final EventListener listener) {
        this.url = url;
        this.opts = opts;
        this.listener = listener;
        this.backoff = new Backoff();
    }

    private int _id = 0;

    private int getNextId() {
        return ++_id;
    }

    public void connect() {
        this.executor.submit(() -> {
            if (Client.this.state == ConnectionState.CONNECTED || Client.this.connecting) {
                return;
            }
            Client.this._connect();
        });
    }

    private void _connect() {
        this.connecting = true;

        Headers.Builder headers = new Headers.Builder();
        if (this.opts.getHeaders() != null) {
            for (Map.Entry<String, String> entry : this.opts.getHeaders().entrySet()) {
                headers.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(this.url)
                .headers(headers.build())
                .addHeader("Sec-WebSocket-Protocol", "centrifuge-protobuf")
                .build();

        if (this.ws != null) {
            this.ws.cancel();
        }

        OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();

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
                Client.this.executor.submit(Client.this::handleConnectionOpen);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                super.onMessage(webSocket, bytes);
                Client.this.executor.submit(() -> Client.this.handleConnectionMessage(bytes.toByteArray()));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                super.onClosing(webSocket, code, reason);
                webSocket.close(NORMAL_CLOSURE_STATUS, null);
                System.out.println("Closing : " + code + " / " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                super.onClosed(webSocket, code, reason);
                Client.this.executor.submit(() -> {
                    if (Client.this.clientDisconnect) {
                        Client.this.handleConnectionClose(Client.this.disconnectCode, Client.this.disconnectReason, Client.this.needReconnect);
                        Client.this.clientDisconnect = false;
                    }
                    boolean reconnect = code < 3500 || code >= 5000 || (code >= 4000 && code < 4500);
                    int disconnectCode = code;
                    String disconnectReason = reason;
                    if (disconnectCode < 3000) {
                        disconnectCode = 4;
                        disconnectReason = "connection closed";
                    }
                    Client.this.handleConnectionClose(disconnectCode, disconnectReason, reconnect);
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                Client.this.executor.submit(() -> Client.this.handleConnectionError(t));
            }
        });
    }

    private void handleConnectionOpen() {
        try {
            this.sendConnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleConnectionMessage(byte[] bytes) {
        if (this.disconnecting) {
            return;
        }
        InputStream stream = new ByteArrayInputStream(bytes);
        try {
            while (stream.available() > 0) {
                Protocol.Reply reply = Protocol.Reply.parseDelimitedFrom(stream);
                this.processReply(reply);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Disconnect from server and do not reconnect.
     */
    public void disconnect() {
        this.executor.submit(this::cleanDisconnect);
    }

    private void cleanDisconnect() {
        Client.this._disconnect(0, "clean disconnect", false);
    }

    /**
     * Close  disconnects client from server and cleans up client resources including
     * shutdown of internal executors. Client is not usable after calling this method.
     * If you only need to temporary disconnect from server use disconnect method.
     *
     * @param awaitMilliseconds is time in milliseconds to wait for executor
     *                          termination (0 means no waiting).
     *
     * @return boolean that indicates whether executor terminated in awaitMilliseconds. For
     * zero awaitMilliseconds close always returns false.
     */
    public boolean close(long awaitMilliseconds) throws InterruptedException {
        this.executor.submit(() -> {
            this.closing = true;
            this.cleanDisconnect();
        });
        this.scheduler.shutdownNow();
        this.reconnectExecutor.shutdownNow();
        if (awaitMilliseconds > 0) {
            // Keep this the last, executor will be closed in connection close handler.
            return this.executor.awaitTermination(awaitMilliseconds, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    private void _disconnect(int code, String reason, Boolean needReconnect) {
        this.disconnecting = true;
        this.clientDisconnect = true;
        if (this.opts.getProtocolVersion() != ProtocolVersion.V1) {
            this.disconnectCode = code;
        }
        this.disconnectReason = reason;
        this.needReconnect = needReconnect;
        this.ws.close(NORMAL_CLOSURE_STATUS, "cya");
    }

    private void handleConnectionClose(int code, String reason, Boolean shouldReconnect) {
        this.needReconnect = shouldReconnect;

        ConnectionState previousState = this.state;

        if (this.pingTask != null) {
            this.pingTask.cancel(true);
        }
        if (this.refreshTask != null) {
            this.refreshTask.cancel(true);
        }
        this.state = ConnectionState.DISCONNECTED;
        this.disconnecting = false;

        synchronized (this.subs) {
            for (Map.Entry<String, Subscription> entry : this.subs.entrySet()) {
                Subscription sub = entry.getValue();
                SubscriptionState previousSubState = sub.getState();
                sub.moveToUnsubscribed();
                if (!shouldReconnect) {
                    sub.setNeedRecover(false);
                }
                if (previousSubState == SubscriptionState.SUBSCRIBED) {
                    sub.getListener().onUnsubscribe(sub, new UnsubscribeEvent());
                }
            }
        }

        if (previousState != ConnectionState.DISCONNECTED) {
            DisconnectEvent event = new DisconnectEvent();
            event.setReason(reason);
            event.setReconnect(shouldReconnect);
            event.setCode(code);
            for (Map.Entry<Integer, CompletableFuture<Protocol.Reply>> entry : this.futures.entrySet()) {
                CompletableFuture f = entry.getValue();
                f.completeExceptionally(new IOException());
            }
            for (Map.Entry<String, ServerSubscription> entry : this.serverSubs.entrySet()) {
                this.listener.onUnsubscribe(this, new ServerUnsubscribeEvent(entry.getKey()));
            }
            this.listener.onDisconnect(this, event);
        }
        if (this.needReconnect) {
            this.scheduleReconnect();
        }
        if (this.closing) {
            this.executor.shutdown();
            this.closing = false;
        }
    }

    private void handleConnectionError(Throwable t) {
        this.listener.onError(this, new ErrorEvent(t));
        this.handleConnectionClose(1, "connection error", true);
    }

    private void scheduleReconnect() {
        this.reconnectExecutor.submit(() -> {
            try {
                Thread.sleep(Client.this.backoff.duration());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            Client.this.executor.submit(() -> {
                if (!Client.this.needReconnect) {
                    return;
                }
                Client.this._connect();
            });
        });
    }

    private void sendSubscribeSynchronized(String channel, boolean recover, StreamPosition streamPosition, String token) {

        Protocol.SubscribeRequest req = null;

        if (recover) {
            req = Protocol.SubscribeRequest.newBuilder()
                    .setEpoch(streamPosition.getEpoch())
                    .setOffset(streamPosition.getOffset())
                    .setChannel(channel)
                    .setRecover(true)
                    .setToken(token)
                    .build();
        } else {
            req = Protocol.SubscribeRequest.newBuilder()
                    .setChannel(channel)
                    .setToken(token)
                    .build();
        }

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.SUBSCRIBE)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setSubscribe(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.handleSubscribeReply(channel, reply, recover);
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.futures.remove(cmd.getId());
                Client.this._disconnect(10, "subscribe timeout", true);
            });
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    private void sendSubscribe(Subscription sub) {
        String channel = sub.getChannel();

        boolean isRecover = false;
        StreamPosition streamPosition = new StreamPosition();

        if (sub.getNeedRecover() && sub.isRecoverable()) {
            isRecover = true;
            if (sub.getLastOffset() > 0) {
                streamPosition.setOffset(sub.getLastOffset());
            }
            streamPosition.setEpoch(sub.getLastEpoch());
        }

        if (sub.getChannel().startsWith(this.opts.getPrivateChannelPrefix())) {
            PrivateSubEvent privateSubEvent = new PrivateSubEvent();
            privateSubEvent.setChannel(sub.getChannel());
            privateSubEvent.setClient(this.client);
            boolean finalIsRecover = isRecover;
            this.listener.onPrivateSub(this, privateSubEvent, new TokenCallback() {
                @Override
                public void Fail(Throwable e) {
                    Client.this.executor.submit(() -> {
                        if (!Client.this.client.equals(privateSubEvent.getClient())) {
                            return;
                        }
                        Client.this._disconnect(8, "subscribe error", true);
                    });
                }

                @Override
                public void Done(String token) {
                    if (Client.this.state != ConnectionState.CONNECTED) {
                        return;
                    }
                    Client.this.sendSubscribeSynchronized(channel, finalIsRecover, streamPosition, token);
                }
            });
        } else {
            this.sendSubscribeSynchronized(channel, isRecover, streamPosition,"");
        }
    }

    void unsubscribe(String channel) {
        this.executor.submit(() -> Client.this.sendUnsubscribeSynchronized(channel));
    }

    private void sendUnsubscribeSynchronized(String channel) {
        Protocol.UnsubscribeRequest req = Protocol.UnsubscribeRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.Command.MethodType.UNSUBSCRIBE)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.handleUnsubscribeReply(channel, reply);
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.futures.remove(cmd.getId());
            e.printStackTrace();
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
     * Create new subscription to channel with certain SubscriptionEventListener
     *
     * @param channel
     * @param listener
     * @return
     * @throws DuplicateSubscriptionException
     */
    public Subscription newSubscription(String channel, SubscriptionEventListener listener) throws DuplicateSubscriptionException {
        Subscription sub;
        synchronized (this.subs) {
            if (this.subs.get(channel) != null) {
                throw new DuplicateSubscriptionException();
            }
            sub = new Subscription(Client.this, channel, listener);
            this.subs.put(channel, sub);
        }
        return sub;
    }

    /**
     * Try to get Subscription from internal client registry. Can return null if Subscription
     * does not exist yet.
     *
     * @param channel
     * @return
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
     * @param sub
     */
    public void removeSubscription(Subscription sub) {
        synchronized (this.subs) {
            sub.unsubscribe();
            if (this.subs.get(sub.getChannel()) != null) {
                this.subs.remove(sub.getChannel());
            }
        }
    }

    void subscribe(Subscription sub) {
        this.executor.submit(() -> {
            if (Client.this.state != ConnectionState.CONNECTED) {
                // Subscription registered and will start subscribing as soon as
                // client will be connected.
                return;
            }
            Client.this.sendSubscribe(sub);
        });
    }

    private void handleSubscribeReply(String channel, Protocol.Reply reply, boolean recover) {
        Subscription sub = this.getSub(channel);
        if (reply.getError().getCode() != 0) {
            if (sub != null) {
                ReplyError err = new ReplyError();
                err.setCode(reply.getError().getCode());
                err.setMessage(reply.getError().getMessage());
                sub.moveToSubscribeError(err);
            }
            return;
        }
        try {
            if (sub != null) {
                Protocol.SubscribeResult result;
                if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                    result = Protocol.SubscribeResult.parseFrom(reply.getResult().toByteArray());
                } else {
                    result = reply.getSubscribe();
                }
                sub.moveToSubscribeSuccess(result, recover);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }

    private void handleUnsubscribeReply(String channel, Protocol.Reply reply) {
    }

    private void _sendPing() {
        if (this.state != ConnectionState.CONNECTED) {
            return;
        }

        Protocol.PingRequest req = Protocol.PingRequest.newBuilder().build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.PING)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setPing(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.futures.remove(cmd.getId());
                Client.this._disconnect(11, "no ping", true);
            });
            return null;
        });

        boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
        if (!sent) {
            f.completeExceptionally(new IOException());
        }
    }

    private void sendPing() {
        this.executor.submit(Client.this::_sendPing);
    }

    private void handleConnectReply(Protocol.Reply reply) {
        if (reply.getError().getCode() != 0) {
            // TODO: handle error.
            return;
        }
        try {
            Protocol.ConnectResult result;
            if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                result = Protocol.ConnectResult.parseFrom(reply.getResult().toByteArray());
            } else {
                result = reply.getConnect();
            }
            ConnectEvent event = new ConnectEvent();
            event.setClient(result.getClient());
            event.setData(result.getData().toByteArray());
            this.state = ConnectionState.CONNECTED;
            this.connecting = false;
            this.client = result.getClient();
            this.listener.onConnect(this, event);
            synchronized (this.subs) {
                for (Map.Entry<String, Subscription> entry : this.subs.entrySet()) {
                    Subscription sub = entry.getValue();
                    if (sub.getNeedResubscribe()) {
                        this.sendSubscribe(sub);
                    }
                }
            }
            for (Map.Entry<String, Protocol.SubscribeResult> entry : result.getSubsMap().entrySet()) {
                Protocol.SubscribeResult subResult = entry.getValue();
                String channel = entry.getKey();
                ServerSubscription serverSub;
                Boolean isResubscribe = false;
                if (this.serverSubs.containsKey(channel)) {
                    serverSub = this.serverSubs.get(channel);
                    isResubscribe = true;
                } else {
                    serverSub = new ServerSubscription(subResult.getRecoverable(), subResult.getOffset(), subResult.getEpoch());
                    this.serverSubs.put(channel, serverSub);
                }
                serverSub.setRecoverable(subResult.getRecoverable());
                serverSub.setLastEpoch(subResult.getEpoch());
                this.listener.onSubscribe(this, new ServerSubscribeEvent(channel, isResubscribe, subResult.getRecovered()));
                if (subResult.getPublicationsCount() > 0) {
                    for (Protocol.Publication publication : subResult.getPublicationsList()) {
                        ServerPublishEvent publishEvent = new ServerPublishEvent();
                        publishEvent.setChannel(channel);
                        publishEvent.setData(publication.getData().toByteArray());
                        ClientInfo info = ClientInfo.fromProtocolClientInfo(publication.getInfo());
                        publishEvent.setInfo(info);
                        publishEvent.setOffset(publication.getOffset());
                        this.listener.onPublish(this, publishEvent);
                        if (publication.getOffset() > 0) {
                            serverSub.setLastOffset(publication.getOffset());
                        }
                    }
                } else {
                    serverSub.setLastOffset(subResult.getOffset());
                }
            }
            this.backoff.reset();

            for (Map.Entry<Integer, Protocol.Command> entry : this.connectCommands.entrySet()) {
                // TODO: send in one frame?
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
                // TODO: send in one frame?
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

            this.pingTask = this.scheduler.scheduleAtFixedRate(Client.this::sendPing, this.opts.getPingInterval(), this.opts.getPingInterval(), TimeUnit.MILLISECONDS);

            if (result.getExpires()) {
                int ttl = result.getTtl();
                this.refreshTask = this.scheduler.schedule(Client.this::sendRefresh, ttl, TimeUnit.SECONDS);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }

    private void sendRefresh() {
        this.executor.submit(() -> {
            Client.this.listener.onRefresh(Client.this, new RefreshEvent(), new TokenCallback() {
                @Override
                public void Fail(Throwable e) {
                    // TODO: handle error.
                }

                @Override
                public void Done(String token) {
                    Client.this.executor.submit(() -> {
                        if (token.equals("")) {
                            return;
                        }
                        if (Client.this.state != ConnectionState.CONNECTED) {
                            return;
                        }
                        refreshSynchronized(token, new ReplyCallback<Protocol.RefreshResult>() {
                            @Override
                            public void onFailure(Throwable e) {
                                // TODO: handle error.
                            }

                            @Override
                            public void onDone(ReplyError error, Protocol.RefreshResult result) {
                                if (error != null) {
                                    // TODO: handle error.
                                    return;
                                }
                                if (result.getExpires()) {
                                    int ttl = result.getTtl();
                                    Client.this.refreshTask = Client.this.scheduler.schedule(Client.this::sendRefresh, ttl, TimeUnit.SECONDS);
                                }
                            }
                        });
                    });
                }
            });
        });
    }

    private void sendConnect() {
        Protocol.ConnectRequest.Builder build = Protocol.ConnectRequest.newBuilder();
        if (this.token.length() > 0) build.setToken(this.token);
        if (this.name.length() > 0) build.setName(this.name);
        if (this.version.length() > 0) build.setVersion(this.version);
        if (this.connectData != null) build.setData(this.connectData);
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

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.CONNECT)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setConnect(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.handleConnectReply(reply);
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.futures.remove(cmd.getId());
            Client.this._disconnect(6, "connect error", true);
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    private void processReply(Protocol.Reply reply) {
        if (reply.getId() > 0) {
            CompletableFuture<Protocol.Reply> cf = this.futures.get(reply.getId());
            if (cf != null) cf.complete(reply);
        } else {
            if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                this.handleAsyncReplyV1(reply);
            } else {
                this.handleAsyncReplyV2(reply);
            }
        }
    }

    private void handlePub(String channel, Protocol.Publication pub) {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(pub.getInfo());
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            PublishEvent event = new PublishEvent();
            event.setData(pub.getData().toByteArray());
            event.setInfo(info);
            event.setOffset(pub.getOffset());
            sub.getListener().onPublish(sub, event);
            if (pub.getOffset() > 0) {
                sub.setLastOffset(pub.getOffset());
            }
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                ServerPublishEvent event = new ServerPublishEvent();
                event.setChannel(channel);
                event.setData(pub.getData().toByteArray());
                event.setInfo(info);
                event.setOffset(pub.getOffset());
                this.listener.onPublish(this, event);
                if (pub.getOffset() > 0) {
                    serverSub.setLastOffset(pub.getOffset());
                }
            }
        }
    }

    private void handleSubscribe(String channel, Protocol.Subscribe sub) {
        ServerSubscription serverSub = new ServerSubscription(sub.getRecoverable(), sub.getOffset(), sub.getEpoch());
        this.serverSubs.put(channel, serverSub);
        serverSub.setRecoverable(sub.getRecoverable());
        serverSub.setLastEpoch(sub.getEpoch());
        this.listener.onSubscribe(this, new ServerSubscribeEvent(channel, false, false));
        serverSub.setLastOffset(sub.getOffset());
    }

    private void handleUnsubscribe(String channel) {
        Subscription sub = this.getSub(channel);
        if (sub != null) {
            sub.unsubscribeNoResubscribe();
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                this.listener.onUnsubscribe(this, new ServerUnsubscribeEvent(channel));
                this.serverSubs.remove(channel);
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

    private void handleAsyncReplyV1(Protocol.Reply reply) {
        try {
            Protocol.Push push = Protocol.Push.parseFrom(reply.getResult());
            String channel = push.getChannel();
            if (push.getType() == Protocol.Push.PushType.PUBLICATION) {
                Protocol.Publication pub = Protocol.Publication.parseFrom(push.getData());
                this.handlePub(channel, pub);
            } else if (push.getType() == Protocol.Push.PushType.SUBSCRIBE) {
                Protocol.Subscribe sub = Protocol.Subscribe.parseFrom(push.getData());
                this.handleSubscribe(channel, sub);
            } else if (push.getType() == Protocol.Push.PushType.JOIN) {
                Protocol.Join join = Protocol.Join.parseFrom(push.getData());
                this.handleJoin(channel, join);
            } else if (push.getType() == Protocol.Push.PushType.LEAVE) {
                Protocol.Leave leave = Protocol.Leave.parseFrom(push.getData());
                this.handleLeave(channel, leave);
            } else if (push.getType() == Protocol.Push.PushType.UNSUBSCRIBE) {
                Protocol.Unsubscribe.parseFrom(push.getData());
                this.handleUnsubscribe(channel);
            } else if (push.getType() == Protocol.Push.PushType.MESSAGE) {
                Protocol.Message msg = Protocol.Message.parseFrom(push.getData());
                this.handleMessage(msg);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }

    private void handleAsyncReplyV2(Protocol.Reply reply) {
        Protocol.Push push = reply.getPush();
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
            this.handleUnsubscribe(channel);
        } else if (push.hasMessage()) {
            this.handleMessage(push.getMessage());
        }
    }

    /**
     * Send asynchronous message with data to server. Callback successfully completes if data
     * written to connection. No reply from server expected in this case.
     *
     * @param data
     * @param cb
     */
    public void send(byte[] data, CompletionCallback cb) {
        this.executor.submit(() -> Client.this.sendSynchronized(data, cb));
    }

    private void sendSynchronized(byte[] data, CompletionCallback cb) {
        Protocol.SendRequest req = Protocol.SendRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.SEND)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setSend(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            cb.onDone();
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        if (this.state != ConnectionState.CONNECTED) {
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
        if (this.state != ConnectionState.CONNECTED) {
            this.connectCommands.put(cmd.getId(), cmd);
        } else {
            boolean sent = this.ws.send(ByteString.of(this.serializeCommand(cmd)));
            if (!sent) {
                f.completeExceptionally(new IOException());
            }
        }
    }

    private ReplyError getReplyError(Protocol.Reply reply) {
        ReplyError err = new ReplyError();
        err.setCode(reply.getError().getCode());
        err.setMessage(reply.getError().getMessage());
        return err;
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
     * Send RPC to server, process result in callback.
     *
     * @param data
     * @param cb
     */
    public void rpc(byte[] data, ReplyCallback<RPCResult> cb) {
        this.executor.submit(() -> Client.this.rpcSynchronized(null, data, cb));
    }

    /**
     * Send RPC with method to server, process result in callback.
     *
     * @param method
     * @param data
     * @param cb
     */
    public void rpc(String method, byte[] data, ReplyCallback<RPCResult> cb) {
        this.executor.submit(() -> Client.this.rpcSynchronized(method, data, cb));
    }

    private void rpcSynchronized(String method, byte[] data, ReplyCallback<RPCResult> cb) {
        Protocol.RPCRequest.Builder builder = Protocol.RPCRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data));

        if (method != null) {
            builder.setMethod(method);
        }

        Protocol.RPCRequest req = builder.build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.RPC)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setRpc(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.RPCResult rpcResult;
                    if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                        rpcResult = Protocol.RPCResult.parseFrom(reply.getResult().toByteArray());
                    } else {
                        rpcResult = reply.getRpc();
                    }
                    RPCResult result = new RPCResult();
                    result.setData(rpcResult.getData().toByteArray());
                    cb.onDone(null, result);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    /**
     * Publish data to channel without being subscribed to it. Publish option should be
     * enabled in Centrifuge/Centrifugo server configuration.
     *
     * @param channel
     * @param data
     * @param cb
     */
    public void publish(String channel, byte[] data, ReplyCallback<PublishResult> cb) {
        this.executor.submit(() -> Client.this.publishSynchronized(channel, data, cb));
    }

    private void publishSynchronized(String channel, byte[] data, ReplyCallback<PublishResult> cb) {
        Protocol.PublishRequest req = Protocol.PublishRequest.newBuilder()
                .setChannel(channel)
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.PUBLISH)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setPublish(req)
                    .build();
        }

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
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    /**
     * History can get channel publication history (useful for server-side subscriptions).
     *
     * @param channel
     * @param opts
     * @param cb
     */
    public void history(String channel, HistoryOptions opts, ReplyCallback<HistoryResult> cb) {
        this.executor.submit(() -> Client.this.historySynchronized(channel, opts, cb));
    }

    private void historySynchronized(String channel, HistoryOptions opts, ReplyCallback<HistoryResult> cb) {
        Protocol.HistoryRequest.Builder builder = Protocol.HistoryRequest.newBuilder()
                .setChannel(channel)
                .setReverse(opts.getReverse())
                .setLimit(opts.getLimit());
        if (opts.getSince() != null) {
            builder.setSince(opts.getSince().toProto());
        }
        Protocol.HistoryRequest req = builder.build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.HISTORY)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setHistory(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.HistoryResult replyResult;
                    if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                        replyResult = Protocol.HistoryResult.parseFrom(reply.getResult().toByteArray());
                    } else {
                        replyResult = reply.getHistory();
                    }
                    HistoryResult result = new HistoryResult();
                    List<Protocol.Publication> protoPubs = replyResult.getPublicationsList();
                    List<Publication> pubs = new ArrayList<>();
                    for (int i = 0; i < protoPubs.size(); i++) {
                        Protocol.Publication protoPub = protoPubs.get(i);
                        Publication pub = new Publication();
                        pub.setData(protoPub.getData().toByteArray());
                        pub.setOffset(protoPub.getOffset());
                        pubs.add(pub);
                    }
                    result.setPublications(pubs);
                    result.setOffset(replyResult.getOffset());
                    result.setEpoch(replyResult.getEpoch());
                    cb.onDone(null, result);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    public void presence(String channel, ReplyCallback<PresenceResult> cb) {
        this.executor.submit(() -> Client.this.presenceSynchronized(channel, cb));
    }

    private void presenceSynchronized(String channel, ReplyCallback<PresenceResult> cb) {
        Protocol.PresenceRequest req = Protocol.PresenceRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.PRESENCE)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setPresence(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.PresenceResult replyResult;
                    if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                        replyResult = Protocol.PresenceResult.parseFrom(reply.getResult().toByteArray());
                    } else {
                        replyResult = reply.getPresence();
                    }
                    PresenceResult result = new PresenceResult();
                    Map<String, Protocol.ClientInfo> protoPresence = replyResult.getPresenceMap();
                    Map<String, ClientInfo> presence = new HashMap<>();
                    for (Map.Entry<String, Protocol.ClientInfo> entry : protoPresence.entrySet()) {
                        Protocol.ClientInfo protoClientInfo = entry.getValue();
                        presence.put(entry.getKey(), ClientInfo.fromProtocolClientInfo(protoClientInfo));
                    }
                    result.setPresence(presence);
                    cb.onDone(null, result);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    public void presenceStats(String channel, ReplyCallback<PresenceStatsResult> cb) {
        this.executor.submit(() -> Client.this.presenceStatsSynchronized(channel, cb));
    }

    private void presenceStatsSynchronized(String channel, ReplyCallback<PresenceStatsResult> cb) {
        Protocol.PresenceStatsRequest req = Protocol.PresenceStatsRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.PRESENCE_STATS)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setPresenceStats(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.PresenceStatsResult replyResult;
                    if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                        replyResult = Protocol.PresenceStatsResult.parseFrom(reply.getResult().toByteArray());
                    } else {
                        replyResult = reply.getPresenceStats();
                    }
                    PresenceStatsResult result = new PresenceStatsResult();
                    result.setNumClients(replyResult.getNumClients());
                    result.setNumUsers(replyResult.getNumUsers());
                    cb.onDone(null, result);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    private void refreshSynchronized(String token, ReplyCallback<Protocol.RefreshResult> cb) {
        Protocol.RefreshRequest req = Protocol.RefreshRequest.newBuilder()
                .setToken(token)
                .build();

        Protocol.Command cmd;

        if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setMethod(Protocol.Command.MethodType.REFRESH)
                    .setParams(req.toByteString())
                    .build();
        } else {
            cmd = Protocol.Command.newBuilder()
                    .setId(this.getNextId())
                    .setRefresh(req)
                    .build();
        }

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.RefreshResult replyResult;
                    if (this.opts.getProtocolVersion() == ProtocolVersion.V1) {
                        replyResult = Protocol.RefreshResult.parseFrom(reply.getResult().toByteArray());
                    } else {
                        replyResult = reply.getRefresh();
                    }
                    cb.onDone(null, replyResult);
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }
            }
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }
}
