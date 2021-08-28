0.1.0
=====

Update to work with Centrifuge >= v0.18.0 and Centrifugo v3.

**Breaking change:** client History API behavior changed – Centrifuge >= v0.18.0 and Centrifugo >= v3.0.0 won't return all publications in a stream by default, see Centrifuge [v0.18.0 release notes](https://github.com/centrifugal/centrifuge/releases/tag/v0.18.0) or [Centrifugo v3 migration guide](https://centrifugal.dev/docs/getting-started/migration_v3) for more information and workaround on server-side. History call now accepts options. Example on how to mimic previous behavior:

```
HistoryOptions opts = new HistoryOptions.Builder().withLimit(-1).build();
subscription.history(opts, ...)
```

If you are using Centrifuge < v0.18.0 or Centrifugo v2 then default options will work the same way as before - i.e. return all publications in a history stream.

0.0.8
=====

* Add possibility to connect over proxy - [#26](https://github.com/centrifugal/centrifuge-java/pull/26)

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
