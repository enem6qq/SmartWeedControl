package com.example.smartweed;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Helper class for managing app language/locale settings.
 *
 * Bewusste Architektur-Entscheidung (v1.5): Diese Klasse implementiert den
 * klassischen attachBaseContext-Ansatz. Das Manifest deklariert zusätzlich
 * den AppCompat-Per-App-Locale-Service (autoStoreLocales) — der modernere Weg
 * wäre AppCompatDelegate.setApplicationLocales(), der attachBaseContext und
 * das manuelle recreate() überflüssig machen würde. Die Umstellung wird
 * bewusst NICHT ungetestet durchgeführt, da der Sprachwechsel ein zentrales,
 * abgenommenes Feature ist; sie ist ein Kandidat für eine spätere Version
 * mit Gerätetest.
 */
public class LocaleHelper {

    private static final String PREFS_NAME = "smartweed_prefs";
    private static final String KEY_LANGUAGE = "app_language";

    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_GERMAN = "de";
    public static final String LANGUAGE_FRENCH = "fr";

    /**
     * Set the app language and persist the preference.
     */
    public static Context setLocale(Context context, String languageCode) {
        persist(context, languageCode);
        return updateResources(context, languageCode);
    }

    /**
     * Get the currently saved language preference.
     */
    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, LANGUAGE_GERMAN); // Default to German
    }

    /**
     * Apply the saved language on app start.
     */
    public static Context onAttach(Context context) {
        String lang = getLanguage(context);
        return updateResources(context, lang);
    }

    private static void persist(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply();
    }

    private static Context updateResources(Context context, String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }
}
