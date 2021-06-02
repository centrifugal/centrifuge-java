0.0.7
=====

* attempt to fix JDK version problems

0.0.6
=====

* Support message recovery
* Support server-side subscriptions
* Migrate to protobuf-javalite
* Pass exception to ErrorEvent
* Print stack trace in case of connect error

0.0.5
=====

* Support RPC `method` field - [#16](https://github.com/centrifugal/centrifuge-java/pull/16)

0.0.4
=====

* add `Data` field for `ConnectEvent`

0.0.3
=====

* fix executing Subscription methods in Subscribed state - see [#9](https://github.com/centrifugal/centrifuge-java/issues/9)

0.0.2
=====

Change Subscription API a bit – users should first create new Subscription then manage it's lifecycle (subscribe, unsubscribe) until done with it. When done it's possible to remove subscription using `Client.removeSubscription` method.

0.0.1
=====

Initial release.
