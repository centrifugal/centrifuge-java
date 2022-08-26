# centrifuge-java

Websocket client for [Centrifugo](https://github.com/centrifugal/centrifugo) server and [Centrifuge](https://github.com/centrifugal/centrifuge) library.

There is no v1 release of this library yet – API still evolves. At the moment patch version updates only contain backwards compatible changes, minor version updates can have backwards incompatible API changes.

Check out [client SDK API specification](https://centrifugal.dev/docs/transports/client_api) to learn how this SDK behaves. It's recommended to read that before starting to work with this SDK as the spec covers common SDK behavior - describes client and subscription state transitions, main options and methods. Also check out examples folder.

The features implemented by this SDK can be found in [SDK feature matrix](https://centrifugal.dev/docs/transports/client_sdk#sdk-feature-matrix).

## Installation

Library available in Maven: https://search.maven.org/artifact/io.github.centrifugal/centrifuge-java

## Javadoc online

http://www.javadoc.io/doc/io.github.centrifugal/centrifuge-java

## Before you start

Centrifuge-java library uses Protobuf library (Lite version) for client protocol. This fact and the fact that Protobuf Lite uses reflection internally can cause connection errors when releasing your application with Android shrinking and obfuscation features enabled. See [protocolbuffers/protobuf#6463](https://github.com/protocolbuffers/protobuf/issues/6463) for details. To deal with this add the following Proguard rule into `proguard-rules.pro` file in your project:

```
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
```

More information [about Android shrinking](https://developer.android.com/studio/build/shrink-code).

## Basic usage

See example code in [console Java example](https://github.com/centrifugal/centrifuge-java/blob/master/example/src/main/java/io/github/centrifugal/centrifuge/example/Main.java) or in [demo Android app](https://github.com/centrifugal/centrifuge-java/blob/master/demo/src/main/java/io/github/centrifugal/centrifuge/demo/MainActivity.java)

To use with Android don't forget to set INTERNET permission to `AndroidManifest.xml`:

```
<uses-permission android:name="android.permission.INTERNET" />
```

## Usage in background

When a mobile application goes to the background there are OS-specific limitations for established persistent connections - which can be silently closed shortly. Thus in most cases you need to disconnect from a server when app moves to the background and connect again when app goes to the foreground.

## CI status

[![Build Status](https://travis-ci.org/centrifugal/centrifuge-java.svg)](https://travis-ci.org/centrifugal/centrifuge-java)

## License

Library is available under the MIT license. See LICENSE for details.

## For Contributors

This section contains an information for library contributors. You don't need generating protobuf code if you just want to use `centrifuge-java` in your project.

### Generate proto

The protobuf definitions are located in `centrifuge/main/proto` directory.
`Protocol` class is generated automatically during project compilation by `protobuf` gradle plugin.

### Check API signatures

We use [metalava-gradle](https://github.com/tylerbwong/metalava-gradle) to ensure we are aware of breaking API changes in the library.

All PRs check API signatures for compatibility. If you see an error, it may indicate there is a breaking change.
Regenerate API signature with and include it in your PR:
```shell
./gradlew :centrifuge:metalavaGenerateSignature
```

Also indicate a breaking change in changelog.

To verify API compatibility locally, run the following command:
```shell
./gradlew :centrifuge:metalavaCheckCompatibility
```

## For maintainer

### Automatic publishing

1. Bump version in `publish-setup.gradle`. 
2. Update changelog to reflect API and behavior changes, bugfixes. 
3. Create new library tag. 

The release GitHub Action should now publish the library.

### Manual publishing

Do all steps from the automatic publishing. Create configuration file `gradle.properties` in `GRADLE_USER_HOME`:

```properties
signing.keyId=<LAST_8_SYMBOLS_OF_KEY_ID>
signing.password=<PASSWORD>
signing.secretKeyRingFile=/Path/to/.gnupg/secring.gpg

mavenCentralUsername=<USERNAME>
mavenCentralPassword=<PASSWORD>
```

Then run:

```shell
./gradlew publish --no-daemon --no-parallel
./gradlew closeAndReleaseRepository
```

The second command will promote staged release to production on MavenCentral.

You can do it manually by following the instructions:

https://central.sonatype.org/pages/releasing-the-deployment.html

I.e.

1) Login here: https://oss.sonatype.org/
2) Go to `Staging repositories`
3) Find release, push `Close` button, wait
4) Push `Release` button

## Special thanks

* Thanks to [Maks Atygaev](https://github.com/atygaev) for initial library boilerplate
