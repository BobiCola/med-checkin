# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
# Glance widget + receivers + action callbacks (instantiated reflectively)
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
# enums used by name in JSON / DB
-keepclassmembers enum com.luge.medreminder.** { *; }
-dontwarn org.jetbrains.annotations.**
