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
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

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
    // Whether the current ws completed its handshake (executor-owned).
    private boolean wsOpen;
    // Fails the current ws if it doesn't open within the timeout (executor-owned).
    private ScheduledFuture<?> openTimeoutTask;
    private OkHttpClient httpClient;
    // Whether httpClient was built from scratch (no user-supplied base client),
    // meaning its Dispatcher and ConnectionPool are ours to dispose. Recorded
    // at build time so a later mutation of Options.okHttpClient cannot
    // misclassify ownership of an already-built transport (#87).
    private boolean httpClientOwned;
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
    // Channel compaction: numeric channel ID → subscription, used to route pushes
    // that carry an ID instead of the string channel name. IDs are scoped to a
    // server session — the registry is dropped on disconnect and each subscription
    // re-registers from its subscribe reply. Only touched on the executor thread,
    // but kept concurrent for consistency with subs (a leaf map, never iterated).
    private final Map<Long, Subscription> subsById = new ConcurrentHashMap<>();
    private final Map<String, ServerSubscription> serverSubs = new ConcurrentHashMap<>();
    private final Backoff backoff;

    // Runs every step of the client. An exception escaping a step (e.g. thrown by an app
    // event handler) is reported through onError instead of vanishing in the task's
    // Future, which left the rest of the step silently undone.
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>()) {
        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t == null && r instanceof java.util.concurrent.Future<?>) {
                java.util.concurrent.Future<?> future = (java.util.concurrent.Future<?>) r;
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        future.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        t = e.getCause();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (t != null) {
                Client.this.reportUncaught(t);
            }
        }
    };
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
    // A timer running at least this late means the process didn't run meanwhile (e.g.
    // it was suspended): data that arrived meanwhile gets LATE_TIMER_GRACE_MS to be
    // handled first, as centrifuge-js lateCallTimer / lateCallGrace.
    static final long LATE_TIMER_MS = 1000;
    static final long LATE_TIMER_GRACE_MS = 1000;
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

    // Subscription feature flags — bitmask sent in SubscribeRequest flag field.
    //
    // Channel compaction asks the server to replace the string channel name with a
    // short numeric ID in subscription pushes (bandwidth optimization). Safe to
    // request unconditionally: servers that don't support or don't allow it ignore
    // the bit and keep sending the full channel name.
    static final long SUBSCRIPTION_FLAG_CHANNEL_COMPACTION = 1;
    static final long SUBSCRIPTION_FLAG_REJECT_UNRECOVERED = 2;

    // Server error code returned when recovery from the provided position is
    // impossible (only sent when SUBSCRIPTION_FLAG_REJECT_UNRECOVERED was requested).
    static final int ERROR_CODE_UNRECOVERABLE_POSITION = 112;

    // Server-sent "state invalidated" codes. The server determines that the
    // client's cached state and/or token are no longer valid and asks the client
    // to drop them and re-sync.
    //
    // 2502 arrives in an Unsubscribe push for a single subscription (the client
    // clears the subscription state and resubscribes, since it's >= 2500). 3014
    // arrives for the whole connection (the client clears the connection token to
    // force a fresh one via getToken, invalidates all subscriptions, reconnects).
    static final int UNSUBSCRIBED_STATE_INVALIDATED = 2502;
    static final int DISCONNECTED_STATE_INVALIDATED = 3014;

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

    /** Reports an exception that escaped a step, e.g. thrown by an event handler. */
    void reportUncaught(Throwable t) {
        try {
            this.listener.onError(this, new ErrorEvent(new UnclassifiedError(t)));
        } catch (Throwable e) {
            // The error handler threw too: nothing left to report to.
            e.printStackTrace();
        }
    }

    /**
     * An exception while handling data from the server (a reply or a push, including an
     * app handler run for it): reported, then the client stops visibly with "bad
     * protocol", as for a frame that fails to dispatch. Before, exceptions inside reply
     * handling were only printed, leaving e.g. a connected client without its
     * resubscribes and ping timer.
     */
    private void handleDispatchError(Throwable e) {
        e.printStackTrace();
        this.reportUncaught(e);
        this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (message)", false);
    }

    /**
     * Calls an app token getter. A getter that throws instead of calling back is treated
     * as calling back with that error; only the first callback counts.
     */
    static void fetchConnectionToken(ConnectionTokenGetter getter, ConnectionTokenEvent event, TokenCallback cb) {
        TokenCallback once = onceToken(cb);
        try {
            getter.getConnectionToken(event, once);
        } catch (Throwable t) {
            once.Done(t, null);
        }
    }

    static void fetchSubscriptionToken(SubscriptionTokenGetter getter, SubscriptionTokenEvent event, TokenCallback cb) {
        TokenCallback once = onceToken(cb);
        try {
            getter.getSubscriptionToken(event, once);
        } catch (Throwable t) {
            once.Done(t, null);
        }
    }

    static void fetchSubscriptionState(SubscriptionStateGetter getter, SubscriptionGetStateEvent event, StateCallback cb) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        StateCallback once = (e, position) -> {
            if (done.compareAndSet(false, true)) {
                cb.Done(e, position);
            }
        };
        try {
            getter.getSubscriptionState(event, once);
        } catch (Throwable t) {
            once.Done(t, null);
        }
    }

    private static TokenCallback onceToken(TokenCallback cb) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return (e, token) -> {
            if (done.compareAndSet(false, true)) {
                cb.Done(e, token);
            }
        };
    }

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
            try {
                new Request.Builder().url(Client.this.endpoint);
            } catch (IllegalArgumentException e) {
                // No retry can fix an endpoint OkHttp can't open: report it and stay
                // disconnected. Before, the client stayed connecting with nothing
                // scheduled.
                Client.this.listener.onError(Client.this, new ErrorEvent(new ConfigurationError(e)));
                return;
            }
            Client.this.reconnectAttempts = 0;
            Client.this.setState(ClientState.CONNECTING);
            ConnectingEvent event = new ConnectingEvent(CONNECTING_CONNECT_CALLED, "connect called");
            try {
                Client.this.listener.onConnecting(Client.this, event);
            } finally {
                // A handler that throws doesn't stop the connect; one that called
                // disconnect() queued it after this step.
                Client.this._connect();
            }
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
            if (Client.this.opts.getTokenGetter() == null) {
                // The token expired (109) and the app set a new one: without a token
                // getter there is no other way to get it, so the next attempt uses it
                // instead of failing on a configuration error. With a getter, the next
                // attempt still fetches a new token: the one set meanwhile may be the
                // expired one (as centrifuge-js).
                Client.this.refreshRequired = false;
            }
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
        try {
            this.executor.submit(() -> {
                // Tear down the connection synchronously on the executor thread
                // so any future-cleanup tasks scheduled by processDisconnect
                // (e.g. orTimeout/exceptionally re-submits) get queued before
                // we mark the executor shutdown.
                WebSocket last = Client.this.ws;
                Client.this.processDisconnect(DISCONNECTED_DISCONNECT_CALLED, "client closed", false);
                Client.this.setState(ClientState.CLOSED);
                if (last != null) {
                    // The teardown sent a close frame; don't wait for the peer.
                    last.cancel();
                }
                // Release dispatcher threads + pooled connections on transports
                // we built from scratch. When the user supplied a base client,
                // transports derived from it via newBuilder() share the user's
                // Dispatcher and ConnectionPool — we never own those (#87).
                if (Client.this.httpClient != null && Client.this.httpClientOwned) {
                    Client.this.httpClient.dispatcher().executorService().shutdown();
                    Client.this.httpClient.connectionPool().evictAll();
                }
                Client.this.scheduler.shutdownNow();
                // Initiate executor shutdown as the final step. New submits
                // will be rejected, but already-queued cleanup tasks still run.
                Client.this.executor.shutdown();
            });
        } catch (RejectedExecutionException ignored) {
            // Executor already shut down (close called twice).
        }
        if (awaitMilliseconds > 0) {
            return this.executor.awaitTermination(awaitMilliseconds, TimeUnit.MILLISECONDS);
        }
        return false;
    }

    void processDisconnect(int code, String reason, Boolean shouldReconnect) {
        if (this.getState() == ClientState.DISCONNECTED || this.getState() == ClientState.CLOSED) {
            if (!shouldReconnect) {
                // Calls made while disconnected wait for a connection: disconnect() and
                // close() end that wait. Before, they waited for their timeout, and after
                // close() (whose scheduler no longer runs timers) forever.
                this.failPendingCalls();
            }
            return;
        }

        if (code == DISCONNECTED_STATE_INVALIDATED) {
            // State invalidated (delivered as a WebSocket close frame or a Disconnect
            // push, both funnel here): drop the connection token so the next connect
            // fetches a fresh one via the token getter, and invalidate every
            // subscription's cached state before reconnecting.
            // Without a token getter there is no new token to get: keep the token the
            // client has (or none) and reconnect with it. The server rejects it if it's
            // no longer valid, instead of the client stopping on a local configuration
            // error (as centrifuge-js).
            if (this.opts.getTokenGetter() != null) {
                this.token = "";
                this.refreshRequired = true;
            }
            for (Subscription sub : this.subs.values()) {
                sub.invalidateState();
            }
            // Server-side subscriptions have no invalidateState() of their own (they
            // are plain data, not stateful Subscription objects) - reset their cached
            // recovery position here so the next connect request (see sendConnect)
            // does not resend a now-invalid offset/epoch pair. The recoverable flag
            // is left untouched, matching invalidateState() above.
            for (ServerSubscription serverSub : this.serverSubs.values()) {
                serverSub.setLastOffset(0);
                serverSub.setLastEpoch("_");
            }
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

        // Drop the transport before any event: a handler may connect again. Its
        // callbacks are ignored from now on, and the reconnect below doesn't wait for
        // its close callback, which OkHttp delivers only once the peer answers the
        // close frame — up to 60s later on a dead or half-open link.
        this.closeTransport();

        // Channel compaction IDs are scoped to a server session — drop the routing
        // registry; each resubscribe re-registers a fresh ID. The per-subscription
        // pushId field is left as-is: moveToSubscribed re-registers it on the next
        // subscribe reply regardless of whether the server reuses the same ID.
        this.subsById.clear();

        try {
            this.finishTeardown(code, reason, shouldReconnect, previousState, needEvent);
        } finally {
            if (shouldReconnect) {
                // Also when a handler threw. A handler may have called disconnect()
                // meanwhile: scheduleReconnect checks the state.
                this.scheduleReconnect();
            }
        }
    }

    private void failPendingCalls() {
        // From a copy: a failure handler may register a new call.
        for (CompletableFuture<Protocol.Reply> f : new ArrayList<>(this.futures.values())) {
            f.completeExceptionally(new IOException());
        }
    }

    /** The part of processDisconnect that emits events: a handler may throw in it. */
    private void finishTeardown(int code, String reason, boolean shouldReconnect, ClientState previousState, boolean needEvent) {
        synchronized (this.subs) {
            for (Subscription sub : new ArrayList<>(this.subs.values())) {
                if (sub.getState() == SubscriptionState.UNSUBSCRIBED) {
                    continue;
                }
                try {
                    sub.moveToSubscribing(SUBSCRIBING_TRANSPORT_CLOSED, "transport closed");
                } catch (Throwable t) {
                    // A handler threw: the other subscriptions must still move.
                    this.reportUncaught(t);
                }
            }
        }

        this.failPendingCalls();
        // Drop the pending command registry along with the futures: no reply can
        // arrive for a connection that is gone, and command IDs are never reused.
        // Failure handlers do remove their own entry, but only after re-checking
        // that the client is still CONNECTED — which it no longer is at this point
        // (the state was set above), so subscribe/unsubscribe entries would stay
        // behind forever and accumulate one per in-flight command per reconnect.
        // Matches centrifuge-js, which resets _callbacks in _clearOutgoingRequests.
        this.futures.clear();

        if (previousState == ClientState.CONNECTED) {
            for (String channel : new ArrayList<>(this.serverSubs.keySet())) {
                try {
                    this.listener.onSubscribing(this, new ServerSubscribingEvent(channel));
                } catch (Throwable t) {
                    this.reportUncaught(t);
                }
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
    }

    /**
     * Closes the current transport and forgets it. An open socket gets a normal close
     * frame; a socket still in its handshake is cancelled, so its handshake doesn't
     * outlive the attempt.
     */
    private void closeTransport() {
        if (this.openTimeoutTask != null) {
            this.openTimeoutTask.cancel(false);
            this.openTimeoutTask = null;
        }
        WebSocket transport = this.ws;
        boolean open = this.wsOpen;
        this.ws = null;
        this.wsOpen = false;
        if (transport == null) {
            return;
        }
        if (open) {
            transport.close(NORMAL_CLOSURE_STATUS, null);
        } else {
            transport.cancel();
        }
    }

    /**
     * Fails the current attempt: an error event, then a teardown that reconnects with
     * backoff (as centrifuge-js failTransport).
     */
    private void failTransport(Throwable error) {
        this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
        this.listener.onError(this, new ErrorEvent(error));
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

        this.closeTransport();

        // If we previously built our own OkHttpClient from scratch, release its
        // dispatcher threads and pooled connections before building a new one.
        // When the user supplied a base client we derive each transport from it
        // via newBuilder(), which shares the user's Dispatcher and
        // ConnectionPool: shutting those down would kill the executor behind
        // the user's client and behind the very transport we are about to
        // build, permanently rejecting all of their calls and our future
        // reconnects (#87). Only transports built from scratch own their
        // resources, tracked via httpClientOwned at build time.
        OkHttpClient previousHttpClient =
                (this.httpClient != null && this.httpClientOwned) ? this.httpClient : null;

        OkHttpClient baseClient = opts.getOkHttpClient();
        OkHttpClient.Builder okHttpBuilder;
        if (baseClient != null) {
            okHttpBuilder = baseClient.newBuilder();
        } else {
            okHttpBuilder = new OkHttpClient.Builder();
        }

        Dns dns = opts.getDns();
        if (dns != null) {
            okHttpBuilder.dns(dns::resolve);
        }

        SSLSocketFactory socketFactory = opts.getSSLSocketFactory();
        if (socketFactory != null) {
            X509TrustManager trustManager = opts.getTrustManager();
            if (trustManager != null) {
                okHttpBuilder.sslSocketFactory(socketFactory, trustManager);
            } else {
                okHttpBuilder.setSslSocketFactoryOrNull$okhttp(socketFactory);
            }
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

        this.httpClient = okHttpBuilder.build();
        this.httpClientOwned = baseClient == null;
        this.ws = this.httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                super.onOpen(webSocket, response);
                try {
                    Client.this.executor.submit(() -> {
                        // Callbacks of a socket that has since been replaced (e.g.
                        // disconnect() then connect() cancels a socket still closing)
                        // belong to a connection the client already gave up on - they
                        // must not act on the new one. Checked on the executor, where
                        // ws is guaranteed to be assigned. Matches centrifuge-js,
                        // which ignores callbacks from a non-actual transport.
                        if (Client.this.ws != webSocket) {
                            // A replaced socket that completed its handshake late:
                            // nothing else will close it.
                            webSocket.close(NORMAL_CLOSURE_STATUS, null);
                            return;
                        }
                        Client.this.wsOpen = true;
                        if (Client.this.openTimeoutTask != null) {
                            Client.this.openTimeoutTask.cancel(false);
                            Client.this.openTimeoutTask = null;
                        }
                        try {
                            Client.this.handleConnectionOpen();
                        } catch (Throwable e) {
                            // Catch Throwable (not just Exception) so LinkageError /
                            // NoSuchMethodError caused by protobuf classpath conflicts
                            // (duplicate `protobuf-java` next to `protobuf-javalite`,
                            // R8 stripping a generated field, etc.) surfaces as an
                            // onError event instead of dying silently inside the
                            // executor — see issue #20.
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
                        if (Client.this.ws != webSocket) {
                            return;
                        }
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
                            } catch (Throwable e) {
                                // Catch Throwable so LinkageError / NoSuchMethodError
                                // raised by protobuf reflection (or any other Error
                                // surfacing from generated code) is reported via
                                // onError rather than silently swallowed by the
                                // executor — see issue #20.
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
                        if (Client.this.ws != webSocket) {
                            return;
                        }
                        boolean reconnect = code < 3500 || code >= 5000 || (code >= 4000 && code < 4500);
                        int disconnectCode = code;
                        String disconnectReason = reason;
                        if (disconnectCode < 3000) {
                            if (disconnectCode == MESSAGE_SIZE_LIMIT_EXCEEDED_STATUS) {
                                disconnectCode = DISCONNECTED_MESSAGE_SIZE_LIMIT;
                                disconnectReason = "message size limit exceeded";
                                // Reconnecting will hit the same limit, so this is terminal —
                                // matches centrifuge-js (transport_websocket onClose).
                                reconnect = false;
                            } else {
                                disconnectCode = CONNECTING_TRANSPORT_CLOSED;
                                disconnectReason = "transport closed";
                            }
                        }
                        if (Client.this.getState() != ClientState.DISCONNECTED) {
                            Client.this.processDisconnect(disconnectCode, disconnectReason, reconnect);
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
                        if (Client.this.ws != webSocket) {
                            return;
                        }
                        Integer responseCode = (response != null) ? response.code() : null;
                        listener.onError(Client.this, new ErrorEvent(t, responseCode));
                        Client.this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
                    });
                } catch (RejectedExecutionException ignored) {
                }
            }
        });

        if (previousHttpClient != null) {
            // Graceful shutdown: in-flight calls (including cancel of the
            // previous WebSocket) finish, then dispatcher threads exit and
            // pooled connections are released.
            previousHttpClient.dispatcher().executorService().shutdown();
            previousHttpClient.connectionPool().evictAll();
        }

        // Bound the handshake by the client timeout, as the connect reply is: OkHttp's
        // own limits (10s connect and read by default, or none in an app-supplied
        // OkHttpClient) are not the SDK's. A timer that runs late (the process was
        // suspended) first lets an open callback already queued run.
        final WebSocket openingWs = this.ws;
        final long openDue = System.nanoTime() / 1_000_000 + this.opts.getTimeout();
        this.openTimeoutTask = this.scheduler.schedule(() -> this.submitOrDrop(() -> {
            if (Client.this.ws != openingWs || Client.this.wsOpen) {
                return;
            }
            long late = System.nanoTime() / 1_000_000 - openDue;
            if (late >= LATE_TIMER_MS) {
                Client.this.openTimeoutTask = Client.this.scheduler.schedule(() -> Client.this.submitOrDrop(() -> {
                    if (Client.this.ws == openingWs && !Client.this.wsOpen) {
                        Client.this.failTransport(new java.util.concurrent.TimeoutException("connect timeout"));
                    }
                }), LATE_TIMER_GRACE_MS, TimeUnit.MILLISECONDS);
                return;
            }
            Client.this.failTransport(new java.util.concurrent.TimeoutException("connect timeout"));
        }), this.opts.getTimeout(), TimeUnit.MILLISECONDS);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    /**
     * Settles a call exactly once: with its result, or with an error — a failure of the
     * connection, or a TimeoutException after the client timeout. Both handlers run on the
     * executor.
     *
     * <p>The timer hops to the executor before failing the call, so a reply already received
     * and queued is handled first; a timer running far too late (the process was suspended)
     * first gives a reply still in the socket a grace period. Before, the
     * timeout was armed on the stage after the result handler, so a slow or throwing
     * handler got the timeout as a second callback, and it failed a call on
     * its own thread although the reply was already waiting.
     */
    <T> void settle(CompletableFuture<T> f, java8.util.function.Consumer<T> onResult,
                    java8.util.function.Function<Throwable, Void> onError) {
        final long due = nowMs() + this.opts.getTimeout();
        ScheduledFuture<?> timer;
        try {
            timer = this.scheduler.schedule(() -> this.submitOrDrop(() -> this.expire(f, due)),
                    this.opts.getTimeout(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            timer = null;
        }
        final ScheduledFuture<?> armed = timer;
        f.whenComplete((result, error) -> {
            if (armed != null) {
                armed.cancel(false);
            }
            try {
                if (error == null) {
                    onResult.accept(result);
                } else {
                    onError.apply(error instanceof java8.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                }
            } catch (Throwable t) {
                // E.g. an app callback that threw: reported, never called again.
                this.reportUncaught(t);
            }
        });
    }

    private <T> void expire(CompletableFuture<T> f, long due) {
        if (f.isDone()) {
            return;
        }
        if (nowMs() - due >= LATE_TIMER_MS) {
            try {
                this.scheduler.schedule(() -> this.submitOrDrop(() -> f.completeExceptionally(new java.util.concurrent.TimeoutException())),
                        LATE_TIMER_GRACE_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (RejectedExecutionException ignored) {
                // Closed: fail now.
            }
        }
        f.completeExceptionally(new java.util.concurrent.TimeoutException());
    }

    /** Submits to the executor, dropping the task once the client is closed. */
    private void submitOrDrop(Runnable task) {
        try {
            this.executor.submit(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void handleConnectionOpen() {
        if (this.getState() != ClientState.CONNECTING) {
            return;
        }
        if (this.refreshRequired || (this.token.equals("") && this.opts.getTokenGetter() != null)) {
            ConnectionTokenEvent connectionTokenEvent = new ConnectionTokenEvent();
            if (this.opts.getTokenGetter() == null) {
                this.listener.onError(Client.this, new ErrorEvent(new ConfigurationError(new Exception("tokenGetter function should be provided in Client options to handle token refresh, see Options.setTokenGetter"))));
                this.processDisconnect(DISCONNECTED_UNAUTHORIZED, "unauthorized", false);
                return;
            }
            final WebSocket connectionWs = this.ws;
            Client.fetchConnectionToken(this.opts.getTokenGetter(), connectionTokenEvent, (err, token) -> this.executor.submit(() -> {
                // If the connection has been replaced (reconnect, disconnect)
                // since this token request was issued, the callback is stale.
                if (Client.this.ws != connectionWs) {
                    return;
                }
                if (Client.this.getState() != ClientState.CONNECTING) {
                    return;
                }
                if (err != null) {
                    if (err instanceof UnauthorizedException) {
                        Client.this.failUnauthorized();
                        return;
                    }
                    Client.this.listener.onError(Client.this, new ErrorEvent(new TokenError(err)));
                    Client.this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
                    return;
                }
                if (token == null) {
                    Client.this.processDisconnect(DISCONNECTED_BAD_PROTOCOL, "bad protocol (token)", false);
                    return;
                }
                Client.this.token = token;
                Client.this.refreshRequired = false;
                this.sendConnect();
            }));
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
        if (this.reconnectTask != null) {
            this.reconnectTask.cancel(false);
        }
        this.reconnectTask = this.scheduler.schedule(
                Client.this::startReconnecting,
                Client.this.backoff.duration(this.reconnectAttempts, this.opts.getMinReconnectDelay(), this.opts.getMaxReconnectDelay()),
                TimeUnit.MILLISECONDS
        );
        this.reconnectAttempts++;
    }

    private void sendSubscribeSynchronized(Subscription sub, Protocol.SubscribeRequest req) {
        final long unsubscribeCount = sub.getUnsubscribeCount();
        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setSubscribe(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        this.settle(f, reply -> {
            if (Client.this.getState() != ClientState.CONNECTED) {
                return;
            }
            try {
                this.handleSubscribeReply(sub, unsubscribeCount, reply);
            } catch (Throwable e) {
                this.handleDispatchError(e);
            }
            this.futures.remove(cmd.getId());
        }, e -> {
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
        this.sendSubscribeSynchronized(sub, req);
    }

    /**
     * Writes the unsubscribe command at once. Called on the executor before the
     * unsubscribed event is emitted, so a subscribe() made after unsubscribe() — or
     * from its handler — always reaches the server after the unsubscribe. Deferring
     * the write to another executor task let an already queued subscribe() go first:
     * the server answered 105 (already subscribed) and the late unsubscribe then
     * removed the new subscription (as centrifuge-js writes first).
     */
    void sendUnsubscribe(String channel) {
        this.sendUnsubscribeSynchronized(channel);
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

        final WebSocket unsubscribeWs = this.ws;
        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        this.settle(f, reply -> {
            this.futures.remove(cmd.getId());
            if (reply.getError().getCode() != 0 && this.ws == unsubscribeWs && this.getState() == ClientState.CONNECTED) {
                // The server may still hold the subscription: its publications would
                // reach an unsubscribed subscription, and a new subscribe would get 105.
                // A new connection starts from a clean server state (as centrifuge-js).
                this.processDisconnect(CONNECTING_UNSUBSCRIBE_ERROR, "unsubscribe error", true);
            }
        }, e -> {
            this.executor.submit(() -> {
                if (Client.this.getState() != ClientState.CONNECTED) {
                    return;
                }
                Client.this.futures.remove(cmd.getId());
                Client.this.processDisconnect(CONNECTING_UNSUBSCRIBE_ERROR, "unsubscribe error", true);
            });
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

    // Update the channel compaction registry for sub: remove the old numeric ID
    // mapping (only if it still points to this subscription) and register the new
    // one. Either ID may be 0 meaning "no mapping". Called on the executor thread.
    void updateSubscriptionPushId(Subscription sub, long oldId, long newId) {
        if (oldId > 0) {
            // Conditional removal: don't evict another subscription's registration
            // after a cross-session ID collision.
            this.subsById.remove(oldId, sub);
        }
        if (newId > 0) {
            this.subsById.put(newId, sub);
        }
    }

    // Resolve a client-side subscription for a push: by numeric channel ID when
    // channel compaction is in use (the push then has no channel name), by channel
    // name otherwise.
    /**
     * The client-side subscription a push is for. While a server-side subscription holds
     * the channel and the client-side one isn't subscribed, the push belongs to the
     * server-side one: the server allows one subscription per channel (as
     * centrifuge-js _getSubForPush).
     */
    private Subscription getSubForPush(String channel, long id) {
        if (id > 0) {
            return this.subsById.get(id);
        }
        Subscription sub = this.subs.get(channel);
        if (sub != null && sub.getState() != SubscriptionState.SUBSCRIBED && this.serverSubs.containsKey(channel)) {
            return null;
        }
        return sub;
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
     * automatically unsubscribed before removing. The channel is free for a new subscription at
     * once. A Subscription this client doesn't hold (e.g. one removed before, whose channel has a
     * new subscription now) is ignored. A removed Subscription can't subscribe again.
     *
     * @param sub: Subscription to remove from registry.
     */
    public void removeSubscription(Subscription sub) {
        synchronized (this.subs) {
            // A subscription removed before must not remove a newer one of its channel
            // (as centrifuge-js).
            if (this.subs.get(sub.getChannel()) != sub) {
                return;
            }
            this.subs.remove(sub.getChannel());
            sub.markRemoved();
            sub.unsubscribe();
        }
    }

    private void handleSubscribeReply(Subscription sub, long unsubscribeCount, Protocol.Reply reply) throws Exception {
        if (this.getSub(sub.getChannel()) != sub || sub.getUnsubscribeCount() != unsubscribeCount) {
            // The subscription was unsubscribed (and maybe subscribed again) or removed
            // since this subscribe was sent: the reply belongs to a subscribe the server
            // was told to end, not to the attempt in progress now.
            return;
        }
        {
            if (sub.getState() != SubscriptionState.SUBSCRIBING) {
                // The subscription left SUBSCRIBING while the subscribe command was
                // still in flight — e.g. unsubscribe() called right after subscribe(),
                // or the channel's subscription was removed and re-created. The reply
                // belongs to an attempt nobody waits for anymore: applying it would
                // resurrect the subscription into SUBSCRIBED (emitting onSubscribed
                // after onUnsubscribed) even though the server was already told to
                // unsubscribe. Matches centrifuge-js, which guards both the success
                // and the error path with _isSubscribing().
                return;
            }
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
                this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
            } else if (reply.getError().getTemporary()) {
                this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
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
        if (this.opts.getTokenGetter() == null) {
            return;
        }
        this.executor.submit(() -> {
            final WebSocket refreshWs = Client.this.ws;
            Client.fetchConnectionToken(Client.this.opts.getTokenGetter(), new ConnectionTokenEvent(), (err, token) -> Client.this.executor.submit(() -> {
            // If the connection has been replaced since this refresh was
            // issued, the callback (and any token it carries) is stale.
            if (Client.this.ws != refreshWs) {
                return;
            }
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
            refreshSynchronized(token, (error, result) -> {
                if (Client.this.ws != refreshWs) {
                    return;
                }
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
        }));
        });
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

        final WebSocket connectWs = this.ws;
        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.futures.put(cmd.getId(), f);
        this.settle(f, reply -> {
            this.futures.remove(cmd.getId());
            try {
                this.handleConnectReply(reply);
            } catch (Throwable e) {
                this.handleDispatchError(e);
            }
        }, e -> {
            // A timeout completes the future on the CompletableFuture delay
            // scheduler thread - hop to the executor like other command failures.
            this.executor.submit(() -> {
                Client.this.futures.remove(cmd.getId());
                // Failed because the connection was already torn down (disconnect
                // called or transport replaced) - nothing left to report or close.
                // Matches centrifuge-js, whose _connectError returns unless connecting.
                if (Client.this.ws != connectWs || Client.this.getState() != ClientState.CONNECTING) {
                    return;
                }
                Client.this.handleConnectionError(e);
                Client.this.processDisconnect(CONNECTING_TRANSPORT_CLOSED, "transport closed", true);
            });
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
        this.handlePub(channel, pub, 0);
    }

    void handlePub(String channel, Protocol.Publication pub, long id) throws Exception {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(pub.getInfo());
        Subscription sub = this.getSubForPush(channel, id);
        if (sub != null) {
            if (sub.getState() != SubscriptionState.SUBSCRIBED) {
                // E.g. a publication already on its way when the app called
                // unsubscribe(), or one sent before a resubscribe: the app doesn't
                // expect it, and the stored position must not move past it.
                return;
            }
            sub.handlePublication(pub);
        } else if (id > 0) {
            // Compacted push with unknown ID (e.g. already unsubscribed) — drop.
            // Server-side subscriptions never negotiate compaction.
            return;
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
                    // As for client-side subscriptions.
                    String knownEpoch = serverSub.getEpoch();
                    if ((knownEpoch == null || knownEpoch.isEmpty()) && !pub.getEpoch().isEmpty()) {
                        serverSub.setLastEpoch(pub.getEpoch());
                    }
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
        Subscription sub = this.getSubForPush(channel, 0);
        if (sub != null) {
            if (sub.getState() != SubscriptionState.SUBSCRIBED) {
                // It ends a previous subscription: one the app unsubscribed, or one that
                // raced unsubscribe() and subscribe() of the app. The server unsubscribes
                // a subscription in progress only after replying to its subscribe, and
                // sends the push also when the subscription is already gone. A resubscribe
                // code must not subscribe again, nor invalidate the state of a subscribe
                // in progress (as centrifuge-js).
                return;
            }
            if (unsubscribe.getCode() < 2500) {
                sub.moveToUnsubscribed(false, unsubscribe.getCode(), unsubscribe.getReason());
            } else {
                if (unsubscribe.getCode() == UNSUBSCRIBED_STATE_INVALIDATED) {
                    // State invalidated: drop the subscription token and cached state so
                    // the resubscribe below obtains a fresh token and re-syncs.
                    sub.invalidateState();
                }
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

    private void handleJoin(String channel, Protocol.Join join, long id) {
        ClientInfo info = ClientInfo.fromProtocolClientInfo(join.getInfo());
        Subscription sub = this.getSubForPush(channel, id);
        if (sub != null) {
            if (sub.getState() != SubscriptionState.SUBSCRIBED) {
                return;
            }
            JoinEvent event = new JoinEvent();
            event.setInfo(info);
            sub.getListener().onJoin(sub, event);
        } else if (id > 0) {
            // Compacted push with unknown ID — drop.
            return;
        } else {
            ServerSubscription serverSub = this.getServerSub(channel);
            if (serverSub != null) {
                this.listener.onJoin(this, new ServerJoinEvent(channel, info));
            }
        }
    }

    private void handleLeave(String channel, Protocol.Leave leave, long id) {
        LeaveEvent event = new LeaveEvent();
        ClientInfo info = ClientInfo.fromProtocolClientInfo(leave.getInfo());

        Subscription sub = this.getSubForPush(channel, id);
        if (sub != null) {
            if (sub.getState() != SubscriptionState.SUBSCRIBED) {
                return;
            }
            event.setInfo(info);
            sub.getListener().onLeave(sub, event);
        } else if (id > 0) {
            // Compacted push with unknown ID — drop.
            return;
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
        // Channel compaction: pub/join/leave pushes may carry a numeric channel ID
        // instead of the channel name. Other push types always carry the channel.
        long id = push.getId();
        if (push.hasPub()) {
            this.handlePub(channel, push.getPub(), id);
        } else if (push.hasSubscribe()) {
            this.handleSubscribe(channel, push.getSubscribe());
        } else if (push.hasJoin()) {
            this.handleJoin(channel, push.getJoin(), id);
        } else if (push.hasLeave()) {
            this.handleLeave(channel, push.getLeave(), id);
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
        this.settle(f, reply -> {
            this.cleanCommandFuture(cmd);
            cb.onDone(null);
        }, e -> {
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
        this.settle(f, reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.RPCResult rpcResult = reply.getRpc();
                RPCResult result = new RPCResult();
                result.setData(rpcResult.getData().toByteArray());
                cb.onDone(null, result);
            }
        }, e -> {
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
        this.settle(f, reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                PublishResult result = new PublishResult();
                cb.onDone(null, result);
            }
        }, e -> {
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
        this.settle(f, reply -> {
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
        }, e -> {
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
        this.settle(f, reply -> {
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
        }, e -> {
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
        this.settle(f, reply -> {
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
        }, e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }

    private void refreshSynchronized(String token, ResultCallback<Protocol.RefreshResult> cb) {
        Protocol.RefreshRequest req = Protocol.RefreshRequest.newBuilder()
                .setToken(token)
                .build();

        Protocol.Command cmd = Protocol.Command.newBuilder()
                .setId(this.getNextId())
                .setRefresh(req)
                .build();

        CompletableFuture<Protocol.Reply> f = new CompletableFuture<>();
        this.settle(f, reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.RefreshResult replyResult = reply.getRefresh();
                cb.onDone(null, replyResult);
            }
        }, e -> {
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
        this.settle(f, reply -> {
            this.cleanCommandFuture(cmd);
            if (reply.getError().getCode() != 0) {
                cb.onDone(getReplyError(reply), null);
            } else {
                Protocol.SubRefreshResult replyResult = reply.getSubRefresh();
                cb.onDone(null, replyResult);
            }
        }, e -> {
            this.executor.submit(() -> {
                Client.this.cleanCommandFuture(cmd);
                cb.onDone(e, null);
            });
            return null;
        });

        this.enqueueCommandFuture(cmd, f);
    }
}
