# Keep WebSocket library
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep JSON library
-keep class org.json.** { *; }

# Keep accessibility service
-keep class com.example.quickdial.QuickAccessibilityService { *; }

# Keep MainActivity (Android entry point)
-keep class com.example.quickdial.MainActivity { *; }

# Keep WebSocketManager
-keep class com.example.quickdial.WebSocketManager { *; }

# Obfuscate everything else
-obfuscationdictionary dictionary.txt
-classobfuscationdictionary dictionary.txt
-packageobfuscationdictionary dictionary.txt
