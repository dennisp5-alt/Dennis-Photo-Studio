# UniFFI's Kotlin bindings reach the Rust library through JNA, which finds the bindings' native
# functions and structures by reflection: keep both.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class ai.storyteller.lightcraft.core.** { *; }
-dontwarn java.awt.**
