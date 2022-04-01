package io.github.centrifugal.centrifuge.demo;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.ConnectEvent;
import io.github.centrifugal.centrifuge.DisconnectEvent;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent;
import io.github.centrifugal.centrifuge.SubscribeEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.UnsubscribeEvent;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView tv = findViewById(R.id.text);

        EventListener listener = new EventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onConnect(Client client, ConnectEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Connected with client ID " + event.getClient()));
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onDisconnect(Client client, DisconnectEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Disconnected: " + event.getReason()));
            }
        };

        Client client = new Client(
                "ws://192.168.1.35:8000/connection/websocket?cf_protocol_version=v2",
                new Options(),
                listener
        );
        client.setToken("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0c3VpdGVfand0In0.hPmHsVqvtY88PvK4EmJlcdwNuKFuy3BGaF7dMaKdPlw");
        client.connect();

        SubscriptionEventListener subListener = new SubscriptionEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onSubscribe(Subscription sub, SubscribeEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Subscribed to " + sub.getChannel()));
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onSubscribeError(Subscription sub, SubscriptionErrorEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Subscribe error " + sub.getChannel() + ": " + event.getMessage()));
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onPublication(Subscription sub, PublicationEvent event) {
                String data = new String(event.getData(), UTF_8);
                MainActivity.this.runOnUiThread(() -> tv.setText("Message from " + sub.getChannel() + ": " + data));
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onUnsubscribe(Subscription sub, UnsubscribeEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Unsubscribed from " + sub.getChannel()));
            }
        };

        Subscription sub;
        try {
            sub = client.newSubscription("chat:index", subListener);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        sub.subscribe();
    }
}
