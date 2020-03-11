# centrifuge-java

This is a Websocket client for Centrifugo and Centrifuge library. Client uses Protobuf protocol for client-server communication. `centrifuge-java` runs all operations in its own threads and provides necessary callbacks so you don't need to worry about managing concurrency yourself.

## Status of library

This library is feature rich and supports almost all available Centrifuge/Centrifugo features (see matrix below). But it's very young and not tested in production application yet. Any help and feedback is very appreciated to make it production ready and update library status. Any report will give us an understanding that the library works, is useful and we should continue developing it. Please share your stories.

## Installation

Library available in Maven: https://search.maven.org/artifact/io.github.centrifugal/centrifuge-java

## Javadoc online

http://www.javadoc.io/doc/io.github.centrifugal/centrifuge-java

## Basic usage

Connect to server based on Centrifuge library:

```java
EventListener listener = new EventListener() {
    @Override
    public void onConnect(Client client, ConnectEvent event) {
        System.out.println("connected");
    }

    @Override
    public void onDisconnect(Client client, DisconnectEvent event) {
        System.out.printf("disconnected %s, reconnect %s%n", event.getReason(), event.getReconnect());
    }
};

Client client = new Client(
        "ws://localhost:8000/connection/websocket?format=protobuf",
        new Options(),
        listener
);
client.connect();
```

Note that *you must use* `?format=protobuf` in connection URL as this client communicates with Centrifugo/Centrifuge over Protobuf protocol.

Also in case of running in Android emulator don't forget to use proper connection address to Centrifuge/Centrifugo (as `localhost` is pointing to emulator vm and obviously your server instance is not available there).

To connect to Centrifugo you need to additionally set connection JWT:

```java
...
Client client = new Client(
        "ws://localhost:8000/connection/websocket?format=protobuf",
        new Options(),
        listener
);
client.setToken("YOUR CONNECTION JWT")
client.connect()
```

Now let's look at how to subscribe to channel and listen to messages published into it:

```java
EventListener listener = new EventListener() {
    @Override
    public void onConnect(Client client, ConnectEvent event) {
        System.out.println("connected");
    }

    @Override
    public void onDisconnect(Client client, DisconnectEvent event) {
        System.out.printf("disconnected %s, reconnect %s%n", event.getReason(), event.getReconnect());
    }
};

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
}

Client client = new Client(
        "ws://localhost:8000/connection/websocket?format=protobuf",
        new Options(),
        listener
);
// If using Centrifugo.
client.setToken("YOUR CONNECTION JWT")
client.connect()

Subscription sub;
try {
    sub = client.newSubscription("chat:index", subListener);
} catch (DuplicateSubscriptionException e) {
    e.printStackTrace();
    return;
}
sub.subscribe();
```

See more example code in [console Java example](https://github.com/centrifugal/centrifuge-java/blob/master/example/src/main/java/io/github/centrifugal/centrifuge/example/Main.java) or in [demo Android app](https://github.com/centrifugal/centrifuge-java/blob/master/demo/src/main/java/io/github/centrifugal/centrifuge/demo/MainActivity.java)

To use with Android don't forget to set INTERNET permission to `AndroidManifest.xml`:

```
<uses-permission android:name="android.permission.INTERNET" />
```

## CI status

[![Build Status](https://travis-ci.org/centrifugal/centrifuge-java.svg)](https://travis-ci.org/centrifugal/centrifuge-java)

## Feature matrix

- [ ] connect to server using JSON protocol format
- [x] connect to server using Protobuf protocol format
- [x] connect with JWT
- [x] connect with custom header
- [x] automatic reconnect in case of errors, network problems etc
- [x] exponential backoff for reconnect
- [x] connect and disconnect events
- [x] handle disconnect reason
- [x] subscribe on channel and handle asynchronous Publications
- [x] handle Join and Leave messages
- [x] handle Unsubscribe notifications
- [x] reconnect on subscribe timeout
- [x] publish method of Subscription
- [x] unsubscribe method of Subscription
- [x] presence method of Subscription
- [x] presence stats method of Subscription
- [x] history method of Subscription
- [x] send asynchronous messages to server
- [x] handle asynchronous messages from server
- [x] send RPC commands
- [x] publish to channel without being subscribed
- [x] subscribe to private channels with JWT
- [x] connection JWT refresh
- [ ] private channel subscription JWT refresh
- [ ] handle connection expired error
- [ ] handle subscription expired error
- [x] ping/pong to find broken connection
- [ ] server-side subscriptions
- [ ] message recovery mechanism

## Generate proto

```
protoc --java_out=./ client.proto
mv io/github/centrifugal/centrifuge/internal/protocol centrifuge/src/main/java/io/github/centrifugal/centrifuge/internal/protocol
rm -r io/
```

## License

Library is available under the MIT license. See LICENSE for details.

## Contributors

* Thanks to [Maks Atygaev](https://github.com/atygaev) for initial library boilerplate

## Release

Bump version. Then run:

```
./gradlew uploadArchives
```

Then follow instructions:

https://central.sonatype.org/pages/releasing-the-deployment.html
