package io.github.centrifugal.centrifuge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocketListener;
import okio.ByteString;

import io.github.centrifugal.centrifuge.internal.backoff.Backoff;
import io.github.centrifugal.centrifuge.internal.proto.Protocol;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;

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

import java8.util.concurrent.CompletableFuture;

public class Client {
    private WebSocket ws;
    private String url;

    Options getOpts() {
        return opts;
    }

    private Options opts;
    private String token = "";
    private EventListener listener;
    private String client;
    private Map<Integer, CompletableFuture<Protocol.Reply>> futures = new ConcurrentHashMap<>();
    private Map<Integer, Protocol.Command> connectCommands = new ConcurrentHashMap<>();
    private Map<Integer, Protocol.Command> connectAsyncCommands = new ConcurrentHashMap<>();

    ConnectionState getState() {
        return state;
    }

    private ConnectionState state = ConnectionState.NEW;
    private final Map<String, Subscription> subs = new ConcurrentHashMap<>();
    private Boolean connecting = false;
    private Boolean disconnecting = false;
    private Backoff backoff;
    private Boolean needReconnect = true;

    ExecutorService getExecutor() {
        return executor;
    }

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture pingTask;
    private ScheduledFuture refreshTask;
    private String disconnectReason = "";

    private static final int NORMAL_CLOSURE_STATUS = 1000;

    public void setToken(String token) {
        this.executor.submit(() -> {
            Client.this.token = token;
        });
    }

    /**
     * Creates a new instance of Client.
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
            for (Map.Entry<String,String> entry : this.opts.getHeaders().entrySet()) {
                headers.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(this.url)
                .headers(headers.build())
                .build();

        if (this.ws != null) {
            this.ws.cancel();
        }
        this.ws = (new OkHttpClient()).newWebSocket(request, new WebSocketListener() {
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
                    /* TODO: refactor this. */
                    if (!reason.equals("")) {
                        try {
                            JsonObject jsonObject = new JsonParser().parse(reason).getAsJsonObject();
                            String disconnectReason = jsonObject.get("reason").getAsString();
                            Boolean shouldReconnect = jsonObject.get("reconnect").getAsBoolean();
                            Client.this.handleConnectionClose(disconnectReason, shouldReconnect);
                            return;
                        } catch (JsonParseException e) {
                            Client.this.handleConnectionClose("connection closed", true);
                        }
                    }
                    if (!Client.this.disconnectReason.equals("")) {
                        JsonObject jsonObject = new JsonParser().parse(Client.this.disconnectReason).getAsJsonObject();
                        String disconnectReason = jsonObject.get("reason").getAsString();
                        Boolean shouldReconnect = jsonObject.get("reconnect").getAsBoolean();
                        Client.this.disconnectReason = "";
                        Client.this.handleConnectionClose(disconnectReason, shouldReconnect);
                        return;
                    }
                    Client.this.handleConnectionClose("connection closed", true);
                });
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                Client.this.executor.submit(Client.this::handleConnectionError);
            }
        });
    }

    private void handleConnectionOpen() {
        this.sendConnect();
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

    public void disconnect() {
        this.executor.submit(() -> {
            String disconnectReason = "{\"reason\": \"clean disconnect\", \"reconnect\": false}";
            Client.this._disconnect(disconnectReason, false);
        });
    }

    private void _disconnect(String disconnectReason, Boolean needReconnect) {
        this.disconnecting = true;
        this.needReconnect = needReconnect;
        this.disconnectReason = disconnectReason;
        this.ws.close(NORMAL_CLOSURE_STATUS, "cya");
    }

    private void handleConnectionClose(String reason, Boolean shouldReconnect) {
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

        for(Map.Entry<String, Subscription> entry: this.subs.entrySet()) {
            Subscription sub = entry.getValue();
            SubscriptionState previousSubState = sub.getState();
            sub.moveToUnsubscribed();
            if (previousSubState == SubscriptionState.SUBSCRIBED) {
                sub.getListener().onUnsubscribe(sub, new UnsubscribeEvent());
            }
        }

        if (previousState != ConnectionState.DISCONNECTED) {
            DisconnectEvent event = new DisconnectEvent();
            event.setReason(reason);
            event.setReconnect(shouldReconnect);
            for(Map.Entry<Integer, CompletableFuture<Protocol.Reply>> entry: this.futures.entrySet()) {
                CompletableFuture f = entry.getValue();
                f.completeExceptionally(new IOException());
            }
            this.listener.onDisconnect(this, event);
        }
        if (this.needReconnect) {
            this.scheduleReconnect();
        }
    }

    private void handleConnectionError() {
        this.listener.onError(this, new ErrorEvent());
        this.handleConnectionClose("connection error", true);
    }

    private void scheduleReconnect() {
        this.reconnectExecutor.submit(() -> {
            try {
                Thread.sleep(Client.this.backoff.duration());
            } catch(InterruptedException ex) {
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

    private void sendSubscribeSynchronized(String channel, String token) {
        Protocol.SubscribeRequest req = Protocol.SubscribeRequest.newBuilder()
                .setChannel(channel)
                .setToken(token)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.SUBSCRIBE)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.handleSubscribeReply(channel, reply);
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.futures.remove(cmd.getId());
                String disconnectReason = "{\"reason\": \"timeout\", \"reconnect\": true}";
                Client.this._disconnect(disconnectReason, true);
            });
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    void sendSubscribe(Subscription sub) {
        String channel = sub.getChannel();
        if (sub.getChannel().startsWith(this.opts.getPrivateChannelPrefix())) {
            PrivateSubEvent privateSubEvent = new PrivateSubEvent();
            privateSubEvent.setChannel(sub.getChannel());
            privateSubEvent.setClient(this.client);
            this.listener.onPrivateSub(this, privateSubEvent, new TokenCallback() {
                @Override
                public void Fail(Throwable e) {
                    Client.this.executor.submit(() -> {
                        if (!Client.this.client.equals(privateSubEvent.getClient())) {
                            return;
                        }
                        String disconnectReason = "{\"reason\": \"private subscribe error\", \"reconnect\": true}";
                        Client.this._disconnect(disconnectReason, true);
                    });
                }

                @Override
                public void Done(String token) {
                    if (Client.this.state != ConnectionState.CONNECTED) {
                        return;
                    }
                    Client.this.sendSubscribeSynchronized(channel, token);
                }
            });
        } else {
            this.sendSubscribeSynchronized(channel, "");
        }
    }

    void sendUnsubscribe(Subscription sub) {
        this.executor.submit(() -> Client.this.sendUnsubscribeSynchronized(sub));
    }

    private void sendUnsubscribeSynchronized(Subscription sub) {
        String channel = sub.getChannel();

        Protocol.UnsubscribeRequest req = Protocol.UnsubscribeRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.UNSUBSCRIBE)
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

    public Subscription subscribe(String channel, SubscriptionEventListener listener) throws DuplicateSubscriptionException {
        Subscription sub;
        synchronized (this.subs) {
	    Subscription fromMap = this.subs.get(channel);
            if (fromMap != null) {
                sub = fromMap;
            } else {
                sub = new Subscription(Client.this, channel, listener);
                this.subs.put(channel, sub);
	    }
        }
        this.executor.submit(() -> {
            if (Client.this.state != ConnectionState.CONNECTED) {
                // Subscription registered and will start subscribing as soon as
                // client will be connected.
                return;
            }
            Client.this.sendSubscribe(sub);
        });
        return sub;
    }

    private void handleSubscribeReply(String channel, Protocol.Reply reply) {
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
                Protocol.SubscribeResult result = Protocol.SubscribeResult.parseFrom(reply.getResult().toByteArray());
                sub.moveToSubscribeSuccess(result);
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

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.PING)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.executor.submit(() -> {
                Client.this.futures.remove(cmd.getId());
                String disconnectReason = "{\"reason\": \"no ping\", \"reconnect\": true}";
                Client.this._disconnect(disconnectReason, true);
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
            Protocol.ConnectResult result = Protocol.ConnectResult.parseFrom(reply.getResult().toByteArray());
            ConnectEvent event = new ConnectEvent();
            event.setClient(result.getClient());
            this.state = ConnectionState.CONNECTED;
            this.connecting = false;
            this.client = result.getClient();
            this.listener.onConnect(this, event);
            for(Map.Entry<String, Subscription> entry: this.subs.entrySet()) {
                Subscription sub = entry.getValue();
                if (sub.getNeedResubscribe()) {
                    this.sendSubscribe(sub);
                }
            }
            this.backoff.reset();

            for(Map.Entry<Integer, Protocol.Command> entry: this.connectCommands.entrySet()) {
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

            for(Map.Entry<Integer, Protocol.Command> entry: this.connectAsyncCommands.entrySet()) {
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
        Protocol.ConnectRequest req = Protocol.ConnectRequest.newBuilder()
                .setToken(this.token)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.CONNECT)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        f.thenAccept(reply -> {
            this.handleConnectReply(reply);
            this.futures.remove(cmd.getId());
        }).orTimeout(this.opts.getTimeout(), TimeUnit.MILLISECONDS).exceptionally(e -> {
            this.futures.remove(cmd.getId());
            String disconnectReason = "{\"reason\": \"connect error\", \"reconnect\": true}";
            Client.this._disconnect(disconnectReason, true);
            return null;
        });

        this.ws.send(ByteString.of(this.serializeCommand(cmd)));
    }

    private void processReply(Protocol.Reply reply) {
        if (reply.getId() > 0) {
            CompletableFuture<Protocol.Reply> cf = this.futures.get(reply.getId());
            if (cf != null) cf.complete(reply);
        } else {
            this.handleAsyncReply(reply);
        }
    }

    private void handleAsyncReply(Protocol.Reply reply) {
        try {
            Protocol.Push push = Protocol.Push.parseFrom(reply.getResult());
            String channel = push.getChannel();
            if (push.getType() == Protocol.PushType.PUBLICATION) {
                Protocol.Publication pub = Protocol.Publication.parseFrom(push.getData());
                Subscription sub = this.getSub(channel);
                if (sub != null) {
                    PublishEvent event = new PublishEvent();
                    event.setData(pub.getData().toByteArray());
                    sub.getListener().onPublish(sub, event);
                }
            } else if (push.getType() == Protocol.PushType.JOIN) {
                Protocol.Join join = Protocol.Join.parseFrom(push.getData());
                Subscription sub = this.getSub(channel);
                if (sub != null) {
                    JoinEvent event = new JoinEvent();
                    ClientInfo info = new ClientInfo();
                    info.setClient(join.getInfo().getClient());
                    info.setUser(join.getInfo().getUser());
                    info.setConnInfo(join.getInfo().getConnInfo().toByteArray());
                    info.setChanInfo(join.getInfo().getChanInfo().toByteArray());
                    event.setInfo(info);
                    sub.getListener().onJoin(sub, event);
                }
            } else if  (push.getType() == Protocol.PushType.LEAVE) {
                Protocol.Leave leave = Protocol.Leave.parseFrom(push.getData());
                Subscription sub = this.getSub(channel);
                if (sub != null) {
                    LeaveEvent event = new LeaveEvent();
                    ClientInfo info = new ClientInfo();
                    info.setClient(leave.getInfo().getClient());
                    info.setUser(leave.getInfo().getUser());
                    info.setConnInfo(leave.getInfo().getConnInfo().toByteArray());
                    info.setChanInfo(leave.getInfo().getChanInfo().toByteArray());
                    event.setInfo(info);
                    sub.getListener().onLeave(sub, event);
                }
            } else if (push.getType() == Protocol.PushType.UNSUB) {
                Protocol.Unsub.parseFrom(push.getData());
                Subscription sub = this.getSub(channel);
                if (sub != null) {
                    sub.unsubscribeNoResubscribe();
                }
            } else if (push.getType() == Protocol.PushType.MESSAGE) {
                Protocol.Message msg = Protocol.Message.parseFrom(push.getData());
                MessageEvent event = new MessageEvent();
                event.setData(msg.getData().toByteArray());
                this.listener.onMessage(this, event);
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }

    public void send(byte[] data, CompletionCallback cb) {
        this.executor.submit(() -> Client.this.sendSynchronized(data, cb));
    }

    private void sendSynchronized(byte[] data, CompletionCallback cb) {
        Protocol.SendRequest req = Protocol.SendRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.SEND)
                .setParams(req.toByteString())
                .build();

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

    private void enqueueCommandFuture(Protocol.Command cmd,  CompletableFuture<Protocol.Reply> f) {
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

    public void rpc(byte[] data, ReplyCallback<RPCResult> cb) {
        this.executor.submit(() -> Client.this.rpcSynchronized(data, cb));
    }

    private void rpcSynchronized(byte[] data, ReplyCallback<RPCResult> cb) {
        Protocol.RPCRequest req = Protocol.RPCRequest.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.RPC)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.RPCResult rpcResult = Protocol.RPCResult.parseFrom(reply.getResult().toByteArray());
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

    public void publish(String channel, byte[] data, ReplyCallback<PublishResult> cb) {
        this.executor.submit(() -> Client.this.publishSynchronized(channel, data, cb));
    }

    private void publishSynchronized(String channel, byte[] data, ReplyCallback<PublishResult> cb) {
        Protocol.PublishRequest req = Protocol.PublishRequest.newBuilder()
                .setChannel(channel)
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.PUBLISH)
                .setParams(req.toByteString())
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
                cb.onFailure(e);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    void history(String channel, ReplyCallback<HistoryResult> cb) {
        this.executor.submit(() -> Client.this.historySynchronized(channel, cb));
    }
    
    private void historySynchronized(String channel, ReplyCallback<HistoryResult> cb) {
        Protocol.HistoryRequest req = Protocol.HistoryRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.HISTORY)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.HistoryResult replyResult = Protocol.HistoryResult.parseFrom(reply.getResult().toByteArray());
                    HistoryResult result = new HistoryResult();
                    List<Protocol.Publication> protoPubs = replyResult.getPublicationsList();
                    List<Publication> pubs = new ArrayList<>();
                    for (int i=0; i<protoPubs.size(); i++) {
                        Protocol.Publication protoPub = protoPubs.get(i);
                        Publication pub = new Publication();
                        pub.setData(protoPub.getData().toByteArray());
                        pubs.add(pub);
                    }
                    result.setPublications(pubs);
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

    void presence(String channel, ReplyCallback<PresenceResult> cb) {
        this.executor.submit(() -> Client.this.presenceSynchronized(channel, cb));
    }

    private void presenceSynchronized(String channel, ReplyCallback<PresenceResult> cb) {
        Protocol.PresenceRequest req = Protocol.PresenceRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.PRESENCE)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.PresenceResult replyResult = Protocol.PresenceResult.parseFrom(reply.getResult().toByteArray());
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

    void presenceStats(String channel, ReplyCallback<PresenceStatsResult> cb) {
        this.executor.submit(() -> Client.this.presenceStatsSynchronized(channel, cb));
    }

    private void presenceStatsSynchronized(String channel, ReplyCallback<PresenceStatsResult> cb) {
        Protocol.PresenceStatsRequest req = Protocol.PresenceStatsRequest.newBuilder()
                .setChannel(channel)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.PRESENCE_STATS)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.PresenceStatsResult replyResult = Protocol.PresenceStatsResult.parseFrom(reply.getResult().toByteArray());
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

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setMethod(Protocol.MethodType.REFRESH)
                .setParams(req.toByteString())
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        f.thenAccept(reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                try {
                    Protocol.RefreshResult replyResult = Protocol.RefreshResult.parseFrom(reply.getResult().toByteArray());
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
