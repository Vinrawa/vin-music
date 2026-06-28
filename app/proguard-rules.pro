# ── Room entities (accessed via reflection by Room compiler) ────────────────────
-keep class com.vinmusic.data.db.** { *; }

# ── Gson models (deserialized via reflection) ──────────────────────────────────
-keepclassmembers class com.vinmusic.lyrics.** { <fields>; }
-keepclassmembers class com.vinmusic.innertube.** { <fields>; }
-keepclassmembers class com.vinmusic.recommendation.** { <fields>; }
-keep class com.vinmusic.lyrics.WordTiming { *; }
-keep class com.vinmusic.lyrics.LyricsLine { *; }
-keep class com.vinmusic.lyrics.LyricsCandidate { *; }

# ── Hilt / Dagger (injected via reflection) ────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── OkHttp / Okio (suppress warnings for platform-specific classes) ────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Misc ───────────────────────────────────────────────────────────────────────
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep enum values for Gson
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Media3 - keep ExoPlayer internals
-keep class androidx.media3.** { *; }
