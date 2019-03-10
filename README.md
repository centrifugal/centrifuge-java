# centrifuge-java

This is a Websocket client for Centrifugo and Centrifuge library. Client uses Protobuf protocol for client-server communication. `centrifuge-java` runs all operations in its own threads and provides necessary callbacks so you don't need to worry about managing concurrency yourself.

## Status of library

This library is feature rich and supports almost all available Centrifuge/Centrifugo features (see matrix below). But it's very young and not tested in production application yet. Any help and feedback is very appreciated to make it production ready and update library status. Any report will give us an understanding that the library works, is useful and we should continue developing it. Please share your stories.

## Installation

Library available in Maven: https://search.maven.org/artifact/io.github.centrifugal/centrifuge-java

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
- [ ] message recovery mechanism

## Generate proto

```
protoc --java_out=./ client.proto
mv io/github/centrifugal/centrifuge/internal/proto centrifuge/src/main/java/io/github/centrifugal/centrifuge/internal/proto
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
