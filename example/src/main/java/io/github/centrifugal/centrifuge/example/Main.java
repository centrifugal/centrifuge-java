package io.github.centrifugal.centrifuge.example;

import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.PresenceStatsResult;
import io.github.centrifugal.centrifuge.RefreshEvent;
import io.github.centrifugal.centrifuge.ReplyCallback;
import io.github.centrifugal.centrifuge.JoinEvent;
import io.github.centrifugal.centrifuge.LeaveEvent;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.PublishResult;
import io.github.centrifugal.centrifuge.ReplyError;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.ConnectEvent;
import io.github.centrifugal.centrifuge.DisconnectEvent;
import io.github.centrifugal.centrifuge.ErrorEvent;
import io.github.centrifugal.centrifuge.MessageEvent;
import io.github.centrifugal.centrifuge.PrivateSubEvent;
import io.github.centrifugal.centrifuge.PublishEvent;
import io.github.centrifugal.centrifuge.SubscribeErrorEvent;
import io.github.centrifugal.centrifuge.SubscribeSuccessEvent;
import io.github.centrifugal.centrifuge.TokenCallback;
import io.github.centrifugal.centrifuge.UnsubscribeEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LOGGER.info("Initialize Client client");

        EventListener listener = new EventListener() {
            @Override
            public void onConnect(Client client, ConnectEvent event) {
                System.out.println("connected");
            }

            @Override
            public void onDisconnect(Client client, DisconnectEvent event) {
                System.out.printf("disconnected %s, reconnect %s%n", event.getReason(), event.getReconnect());
            }

            @Override
            public void onError(Client client, ErrorEvent event) {
                System.out.println("There was a problem connecting!");
            }

            @Override
            public void onPrivateSub(Client client, PrivateSubEvent event, TokenCallback cb) {
                cb.Done("boom");
            }

            @Override
            public void onRefresh(Client client, RefreshEvent event, TokenCallback cb) {
                cb.Done("boom");
            }

            @Override
            public void onMessage(Client client, MessageEvent event) {
                String data = new String(event.getData(), UTF_8);
                System.out.println("message received: " + data);
            }
        };

        Client client = new Client(
                "ws://localhost:8000/connection/websocket?format=protobuf",
                new Options(),
                listener
        );
        client.setToken("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0c3VpdGVfand0In0.hPmHsVqvtY88PvK4EmJlcdwNuKFuy3BGaF7dMaKdPlw");
        client.connect();

        SubscriptionEventListener subListener = new SubscriptionEventListener() {
            @Override
            public void onSubscribeSuccess(Subscription sub, SubscribeSuccessEvent event) {
                System.out.println("subscribed to " + sub.getChannel());
            }
            @Override
            public void onSubscribeError(Subscription sub, SubscribeErrorEvent event) {
                System.out.println("subscribe error " + sub.getChannel() + " " + event.getMessage());
            }
            @Override
            public void onPublish(Subscription sub, PublishEvent event) {
                String data = new String(event.getData(), UTF_8);
                System.out.println("message from " + sub.getChannel() + " " + data);
            }
            @Override
            public void onUnsubscribe(Subscription sub, UnsubscribeEvent event) {
                System.out.println("unsubscribed " + sub.getChannel());
            }
            @Override
            public void onJoin(Subscription sub, JoinEvent event) {
                System.out.println("client " + event.getInfo().getClient() + " joined channel " + sub.getChannel());
            }
            @Override
            public void onLeave(Subscription sub, LeaveEvent event) {
                System.out.println("client " + event.getInfo().getClient() + " left channel " + sub.getChannel());
            }
        };

        Subscription sub;
        try {
            sub = client.subscribe("chat:index", subListener);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        String data="{\"input\": \"hi from Java\"}";

        // Publish to channel.
        client.publish("chat:index", data.getBytes(), new ReplyCallback<PublishResult>() {
            @Override
            public void onFailure(Throwable e) {
                System.out.println("publish error: " + e);
            }

            @Override
            public void onDone(ReplyError err, PublishResult reply) {
                if (err != null) {
                    System.out.println("error publish: " + err.getMessage());
                    return;
                }
                System.out.println("successfully published");
            }
        });

        // Or via subscription (will wait for subscribe success before publishing).
        sub.publish(data.getBytes(), new ReplyCallback<PublishResult>() {
            @Override
            public void onFailure(Throwable e) {
                System.out.println("sub publish error: " + e);
            }

            @Override
            public void onDone(ReplyError err, PublishResult res) {
                if (err != null) {
                    System.out.println("error publish: " + err.getMessage());
                    return;
                }
                System.out.println("successfully published");
            }
        });

        sub.presenceStats(new ReplyCallback<PresenceStatsResult>() {
            @Override
            public void onFailure(Throwable e) {
                System.out.println("presence stats error: " + e);
            }

            @Override
            public void onDone(ReplyError err, PresenceStatsResult res) {
                if (err != null) {
                    System.out.println("error presence stats: " + err.getMessage());
                    return;
                }
                System.out.println("Num clients connected: " + res.getNumClients());
            }
        });

        try {
            Thread.sleep(100000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        client.disconnect();
    }
}
