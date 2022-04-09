package io.github.centrifugal.centrifuge.demo;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;
import android.widget.Toast;

import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.ConnectEvent;
import io.github.centrifugal.centrifuge.DisconnectEvent;
import io.github.centrifugal.centrifuge.ErrorEvent;
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

    private Client client;
    private boolean activityPaused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView tv = findViewById(R.id.text);

        Toast.makeText(this, "Create", Toast.LENGTH_SHORT).show();

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
            @SuppressLint("SetTextI18n")
            @Override
            public void onError(Client client, ErrorEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Client error  " + event.getError().toString()));
            }
        };

        Options options = new Options();
//        options.setToken("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0c3VpdGVfand0In0.hPmHsVqvtY88PvK4EmJlcdwNuKFuy3BGaF7dMaKdPlw");

        String endpoint = "ws://192.168.1.101:8000/connection/websocket?cf_protocol_version=v2";
        client = new Client(endpoint, options, listener);
        client.connect();

        SubscriptionEventListener subListener = new SubscriptionEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onSubscribe(Subscription sub, SubscribeEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Subscribed to " + sub.getChannel() + ", recovered: " + event.getRecovered().toString()));
            }
            @SuppressLint("SetTextI18n")
            @Override
            public void onError(Subscription sub, SubscriptionErrorEvent event) {
                MainActivity.this.runOnUiThread(() -> tv.setText("Subscribe error " + sub.getChannel() + ": " + event.getError().toString()));
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

    @Override
    protected void onPause() {
        super.onPause();
        activityPaused = true;
        client.disconnect();
        Toast.makeText(this, "Paused", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activityPaused) {
            client.connect();
            Toast.makeText(this, "Resumed", Toast.LENGTH_SHORT).show();
            activityPaused = false;
        }
    }

//    @Override
//    public void onBackPressed(){
//        moveTaskToBack(true);
//    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            client.close(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Toast.makeText(this, "Destroyed", Toast.LENGTH_SHORT).show();
    }
}
