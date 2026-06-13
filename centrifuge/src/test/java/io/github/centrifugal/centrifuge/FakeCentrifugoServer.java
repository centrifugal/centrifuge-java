package io.github.centrifugal.centrifuge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/**
 * In-process Centrifugo fake server for tests, speaking the protobuf protocol
 * over a WebSocket (backed by {@link MockWebServer}). It is intentionally
 * protocol-level and generic: it provides sensible defaults for the
 * connect/subscribe/unsubscribe handshake, captures received commands for
 * assertions, and exposes hooks + raw push senders so new scenarios can be added
 * WITHOUT touching the client under test or this helper.
 *
 * <p>This exists because some features (channel compaction, and in future others)
 * are Centrifugo PRO only and can't be exercised against the OSS docker-compose
 * server the other suites use; and because a fake gives deterministic control of
 * timing, errors and reconnects.
 *
 * <p>How to extend (most&rarr;least common):
 * <pre>
 *   // Customize a subscribe reply:
 *   server.onSubscribe = (channel, req) -&gt; Protocol.SubscribeResult.newBuilder().setRecoverable(true).build();
 *   // Negotiate channel compaction (assign a numeric id when the client offers it):
 *   server.onSubscribe = (channel, req) -&gt; {
 *       Protocol.SubscribeResult.Builder r = Protocol.SubscribeResult.newBuilder();
 *       if ((req.getFlag() &amp; 1) != 0) r.setId(42);
 *       return r.build();
 *   };
 *   // Push to a subscription:
 *   server.publishId(42, data);            // by numeric id (compaction)
 *   server.publishChannel("news", data);   // by channel name
 *   // Fully control any command reply (return null to fall through):
 *   server.onCommand = cmd -&gt; cmd.hasRpc() ? errorReply(cmd.getId()) : null;
 *   // Send anything the protocol allows:
 *   server.sendPush(Protocol.Push.newBuilder().setDisconnect(...).build());
 *   // Drive a reconnect:
 *   server.closeConnection();
 *   // Assert on what the client sent:
 *   server.received() / server.lastSubscribe()
 * </pre>
 */
public final class FakeCentrifugoServer {

    private final MockWebServer server = new MockWebServer();
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private final List<Protocol.Command> received = Collections.synchronizedList(new ArrayList<>());

    /** connect reply fields. Override to set expires/ttl/data/etc. */
    public Protocol.ConnectResult connectResult = Protocol.ConnectResult.newBuilder()
            .setClient("fake-client").setVersion("0.0.0").setPing(25).build();

    /**
     * Full override for any command — return a Reply to send, or null to fall
     * through to default handling.
     */
    public Function<Protocol.Command, Protocol.Reply> onCommand;

    /** Customize the subscribe result per channel (default: empty result). */
    public BiFunction<String, Protocol.SubscribeRequest, Protocol.SubscribeResult> onSubscribe;

    public void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // Every connection (initial + reconnects) gets a fresh upgrade.
                return new MockResponse().withWebSocketUpgrade(new ServerListener());
            }
        });
        server.start();
    }

    /** Stop the server and close the active connection. */
    public void stop() {
        WebSocket s = currentSocket.get();
        if (s != null) {
            s.close(1000, "bye");
        }
        try {
            server.shutdown();
        } catch (IOException e) {
            // MockWebServer can time out waiting for WebSocket reader threads to
            // wind down even after the client disconnected. Test assertions have
            // already run, so this teardown hiccup is harmless.
        }
    }

    public String url() {
        return "ws://" + server.getHostName() + ":" + server.getPort() + "/connection/websocket";
    }

    /** A copy of all commands received from the client, in order. */
    public List<Protocol.Command> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    /** The most recent subscribe request the client sent, or null. */
    public Protocol.SubscribeRequest lastSubscribe() {
        synchronized (received) {
            for (int i = received.size() - 1; i >= 0; i--) {
                if (received.get(i).hasSubscribe()) {
                    return received.get(i).getSubscribe();
                }
            }
        }
        return null;
    }

    /**
     * Close the active connection from the server side, triggering the client's
     * automatic reconnect.
     */
    public void closeConnection() {
        WebSocket s = currentSocket.get();
        if (s != null) {
            s.close(1000, "bye");
        }
    }

    private final class ServerListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            currentSocket.set(webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            InputStream stream = new ByteArrayInputStream(bytes.toByteArray());
            try {
                while (stream.available() > 0) {
                    Protocol.Command cmd = Protocol.Command.parseDelimitedFrom(stream);
                    if (cmd == null) {
                        break;
                    }
                    dispatch(webSocket, cmd);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void dispatch(WebSocket webSocket, Protocol.Command cmd) {
        received.add(cmd);

        if (onCommand != null) {
            Protocol.Reply reply = onCommand.apply(cmd);
            if (reply != null) {
                send(webSocket, reply);
                return;
            }
        }

        if (cmd.hasConnect()) {
            send(webSocket, Protocol.Reply.newBuilder()
                    .setId(cmd.getId())
                    .setConnect(connectResult)
                    .build());
        } else if (cmd.hasSubscribe()) {
            Protocol.SubscribeResult result = onSubscribe != null
                    ? onSubscribe.apply(cmd.getSubscribe().getChannel(), cmd.getSubscribe())
                    : Protocol.SubscribeResult.getDefaultInstance();
            send(webSocket, Protocol.Reply.newBuilder().setId(cmd.getId()).setSubscribe(result).build());
        } else if (cmd.hasUnsubscribe()) {
            send(webSocket, Protocol.Reply.newBuilder()
                    .setId(cmd.getId())
                    .setUnsubscribe(Protocol.UnsubscribeResult.newBuilder().build())
                    .build());
        } else if (cmd.getId() != 0) {
            // Reply to anything else with an empty result to avoid client timeouts.
            send(webSocket, Protocol.Reply.newBuilder().setId(cmd.getId()).build());
        }
    }

    // --- raw escape hatches ---------------------------------------------------

    private void send(WebSocket webSocket, Protocol.Reply reply) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            reply.writeDelimitedTo(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        webSocket.send(ByteString.of(out.toByteArray()));
    }

    /** Send a raw reply to the active connection. */
    public void sendReply(Protocol.Reply reply) {
        send(currentSocket.get(), reply);
    }

    /** Send a raw push (wrapped in a reply) to the active connection. */
    public void sendPush(Protocol.Push push) {
        sendReply(Protocol.Reply.newBuilder().setPush(push).build());
    }

    // --- typed push senders ---------------------------------------------------
    //
    // Channel compaction pushes carry a numeric id and no channel; otherwise the
    // channel name is used. The *Id variants address by numeric id, the *Channel
    // variants by channel name.

    public void publishId(long id, byte[] data) {
        sendPush(Protocol.Push.newBuilder().setId(id).setPub(pub(data)).build());
    }

    public void publishChannel(String channel, byte[] data) {
        sendPush(Protocol.Push.newBuilder().setChannel(channel).setPub(pub(data)).build());
    }

    public void joinId(long id, String client) {
        sendPush(Protocol.Push.newBuilder().setId(id).setJoin(join(client)).build());
    }

    public void leaveId(long id, String client) {
        sendPush(Protocol.Push.newBuilder().setId(id).setLeave(leave(client)).build());
    }

    public void unsubscribePush(String channel, int code, String reason) {
        sendPush(Protocol.Push.newBuilder().setChannel(channel)
                .setUnsubscribe(Protocol.Unsubscribe.newBuilder().setCode(code).setReason(reason).build())
                .build());
    }

    public void disconnectPush(int code, String reason) {
        sendPush(Protocol.Push.newBuilder()
                .setDisconnect(Protocol.Disconnect.newBuilder().setCode(code).setReason(reason).build())
                .build());
    }

    private static Protocol.Publication pub(byte[] data) {
        return Protocol.Publication.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data)).build();
    }

    private static Protocol.Join join(String client) {
        return Protocol.Join.newBuilder()
                .setInfo(Protocol.ClientInfo.newBuilder().setClient(client).build()).build();
    }

    private static Protocol.Leave leave(String client) {
        return Protocol.Leave.newBuilder()
                .setInfo(Protocol.ClientInfo.newBuilder().setClient(client).build()).build();
    }
}
