# centrifuge-java relies on Protobuf JavaLite
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}