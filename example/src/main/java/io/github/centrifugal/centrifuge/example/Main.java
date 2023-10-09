package io.github.centrifugal.centrifuge.example;

import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.ConnectingEvent;
import io.github.centrifugal.centrifuge.DuplicateSubscriptionException;
import io.github.centrifugal.centrifuge.HistoryOptions;
import io.github.centrifugal.centrifuge.JoinEvent;
import io.github.centrifugal.centrifuge.LeaveEvent;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.ServerJoinEvent;
import io.github.centrifugal.centrifuge.ServerLeaveEvent;
import io.github.centrifugal.centrifuge.ServerPublicationEvent;
import io.github.centrifugal.centrifuge.ServerSubscribedEvent;
import io.github.centrifugal.centrifuge.ServerSubscribingEvent;
import io.github.centrifugal.centrifuge.ServerUnsubscribedEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.ConnectedEvent;
import io.github.centrifugal.centrifuge.DisconnectedEvent;
import io.github.centrifugal.centrifuge.ErrorEvent;
import io.github.centrifugal.centrifuge.MessageEvent;
import io.github.centrifugal.centrifuge.SubscribingEvent;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent;
import io.github.centrifugal.centrifuge.SubscribedEvent;
import io.github.centrifugal.centrifuge.SubscriptionOptions;
import io.github.centrifugal.centrifuge.UnsubscribedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LOGGER.info("Initialize Client client");

        EventListener listener = new EventListener() {
            @Override
            public void onConnected(Client client, ConnectedEvent event) {
                System.out.printf("connected with client id %s%n", event.getClient());
            }
            @Override
            public void onConnecting(Client client, ConnectingEvent event) {
                System.out.printf("connecting: %s%n", event.getReason());
            }
            @Override
            public void onDisconnected(Client client, DisconnectedEvent event) {
                System.out.printf("disconnected %d %s%n", event.getCode(), event.getReason());
            }
            @Override
            public void onError(Client client, ErrorEvent event) {
                System.out.printf("connection error: %s%n", event.getError().toString());
            }
            @Override
            public void onMessage(Client client, MessageEvent event) {
                String data = new String(event.getData(), UTF_8);
                System.out.println("message received: " + data);
            }
            @Override
            public void onSubscribed(Client client, ServerSubscribedEvent event) {
                System.out.println("server side subscribed: " + event.getChannel() + ", recovered " + event.getRecovered());
            }
            @Override
            public void onSubscribing(Client client, ServerSubscribingEvent event) {
                System.out.println("server side subscribing: " + event.getChannel());
            }
            @Override
            public void onUnsubscribed(Client client, ServerUnsubscribedEvent event) {
                System.out.println("server side unsubscribed: " + event.getChannel());
            }
            @Override
            public void onPublication(Client client, ServerPublicationEvent event) {
                String data = new String(event.getData(), UTF_8);
                System.out.println("server side publication: " + event.getChannel() + ": " + data);
            }
            @Override
            public void onJoin(Client client, ServerJoinEvent event) {
                System.out.println("server side join: " + event.getChannel() + " from client " + event.getInfo().getClient());
            }
            @Override
            public void onLeave(Client client, ServerLeaveEvent event) {
                System.out.println("server side leave: " + event.getChannel() + " from client " + event.getInfo().getClient());
            }
        };

        Options opts = new Options();
//        opts.setToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMSIsImV4cCI6MTY1OTQ0MTY4MiwiaWF0IjoxNjU4ODM2ODgyfQ.tyd2_TVq29WZuhh5OBni6dq7Lqry2s8z2PHZavplr7A");
//        opts.setTokenGetter(new ConnectionTokenGetter() {
//            @Override
//            public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
//                // At this place you must request the token from the backend in real app.
//                cb.Done(null, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMSIsImV4cCI6MTY2MDA3MDYzMiwiaWF0IjoxNjU5NDY1ODMyfQ.EWBmBsvbUsOublFJeG0fAMQz_RnX3ZQwd5E00ldyyh0");
//            }
//        });

        Client client = new Client(
                "ws://localhost:8000/connection/websocket",
                opts,
                listener
        );
        client.connect();

        SubscriptionEventListener subListener = new SubscriptionEventListener() {
            @Override
            public void onSubscribed(Subscription sub, SubscribedEvent event) {
                System.out.println("subscribed to " + sub.getChannel() + ", recovered " + event.getRecovered());
                String data="{\"input\": \"I just subscribed to channel\"}";
                sub.publish(data.getBytes(), (err, res) -> {
                    if (err != null) {
                        System.out.println("error publish: " + err.getMessage());
                        return;
                    }
                    System.out.println("successfully published");
                });
            }
            @Override
            public void onSubscribing(Subscription sub, SubscribingEvent event) {
                System.out.printf("subscribing: %s%n", event.getReason());
            }
            @Override
            public void onUnsubscribed(Subscription sub, UnsubscribedEvent event) {
                System.out.println("unsubscribed " + sub.getChannel() + ", reason: " + event.getReason());
            }
            @Override
            public void onError(Subscription sub, SubscriptionErrorEvent event) {
                System.out.println("subscription error " + sub.getChannel() + " " + event.getError().toString());
            }
            @Override
            public void onPublication(Subscription sub, PublicationEvent event) {
                String data = new String(event.getData(), UTF_8);
                System.out.println("message from " + sub.getChannel() + " " + data);
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
            sub = client.newSubscription("chat:index", new SubscriptionOptions(), subListener);
        } catch (DuplicateSubscriptionException e) {
            e.printStackTrace();
            return;
        }
        sub.subscribe();

        String data="{\"input\": \"hi from Java\"}";

        // Publish to channel (won't wait until client subscribed to channel).
        // This message most probably won't be received by this client.
        // You need to wait for subscription success to reliably receive
        // publication - see publishing over sub object below.
        client.publish("chat:index", data.getBytes(), (err, res) -> {
            if (err != null) {
                System.out.println("error publish: " + err);
                return;
            }
            System.out.println("successfully published");
        });

        // Publish via subscription (will wait for subscribe success before publishing).
        sub.publish(data.getBytes(), (err, res) -> {
            if (err != null) {
                System.out.println("error publish: " + err);
                return;
            }
            System.out.println("successfully published");
        });

        sub.presenceStats((err, res) -> {
            if (res == null || err != null) {
                System.out.println("error presence stats: " + err);
                return;
            }
            System.out.println("Num clients connected: " + res.getNumClients());
        });

        sub.history(new HistoryOptions.Builder().withLimit(-1).build(), (err, res) -> {
            if (res == null || err != null) {
                System.out.println("error history: " + err);
                return;
            }
            System.out.println("Num history publication: " + res.getPublications().size());
            System.out.println("Top stream offset: " + res.getOffset());
        });

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        sub.unsubscribe();

        // Say Client that we finished with Subscription. It will be removed from
        // the internal Subscription map so you can create new Subscription to the
        // channel later calling newSubscription method again.
        client.removeSubscription(sub);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // Disconnect from server.
        client.disconnect();

        try {
            boolean ok = client.close(5000);
            if (!ok) {
                System.out.println("client was not gracefully closed");
            } else {
                System.out.println("client gracefully closed");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
