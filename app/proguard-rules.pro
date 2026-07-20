# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================================
# HINWEIS für minifyEnabled true (derzeit false in build.gradle):
#
# Chaquopy liefert eigene Consumer-Keep-Regeln mit — der Python-Java-
# Uebergang (com.chaquo.python.*) funktioniert auch mit R8. Zusaetzlich
# beim Aktivieren von Minify pruefen:
#  - Die JSON-Schluessel des Python-Kontrakts (analysis.py -> AnalysisFragment/
#    SummaryFragment) sind reine Strings und von R8 unberuehrt — hier ist
#    nichts zu keepen.
#  - Glide: benoetigt bei Minify die Standard-Regeln aus der Glide-Doku
#    (-keep public class * implements com.bumptech.glide.module.GlideModule).
#  - Log-Aufrufe fuer Release strippen:
#    -assumenosideeffects class android.util.Log { public static int d(...); public static int i(...); }
# ============================================================

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile