package com.example.smartweed;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Experten-Einstellungen für die Bildanalyse (SharedPreferences).
 * Die Standardwerte entsprechen den bisher fest kodierten Konstanten
 * in analysis.py bzw. CscCalculator.
 */
public final class AnalysisSettings {

    private static final String PREFS_NAME = "analysis_settings";

    private static final String KEY_MIN_SIZE = "min_size";
    private static final String KEY_H_LOW = "h_low";
    private static final String KEY_H_HIGH = "h_high";
    private static final String KEY_CSC_LOW = "csc_band_low";        // Altbestand (float, bis v1.5)
    private static final String KEY_CSC_HIGH = "csc_band_high";      // Altbestand (float, bis v1.5)
    private static final String KEY_CSC_LOW_BITS = "csc_band_low_bits";   // double als Long-Bits
    private static final String KEY_CSC_HIGH_BITS = "csc_band_high_bits"; // double als Long-Bits
    private static final String KEY_METHOD = "segmentation_method";
    private static final String KEY_FRAME_CROP = "frame_crop";
    /** Einmal-Migration v1.8: gespeichertes "exg" → "soilref" (siehe migrateV18) */
    private static final String KEY_MIGRATED_V18 = "migrated_v18";

    public static final int DEFAULT_MIN_SIZE = 50;
    public static final int DEFAULT_H_LOW = 35;
    public static final int DEFAULT_H_HIGH = 85;

    /** Segmentierungsmethoden (müssen zu analysis.py passen) */
    public static final String METHOD_HSV = "hsv";
    public static final String METHOD_EXG = "exg";
    public static final String METHOD_EXGR = "exgr";
    /**
     * v1.8: Boden-referenzierte Lab-a*-Segmentierung. Misst die Bodenfarbe im
     * Bild selbst (Median der weniger grünen Bildhälfte) und zählt nur Pixel
     * als Pflanze, die deutlich grüner sind — ein Farbstich durch tiefe Sonne
     * verschiebt Boden und Pflanze gleichermaßen und fällt so heraus.
     * Feldversuch 30./31.10.2026: 93 % Genauigkeit gegen 2 Bonitierer,
     * ExG + Otsu nur Recall 0,30/0,08 bei tiefer Sonne.
     */
    public static final String METHOD_SOILREF = "soilref";
    public static final String DEFAULT_METHOD = METHOD_SOILREF;

    /**
     * Nur das Innere eines Zählrahmens auswerten (v1.8: grauer U-Rahmen mit
     * blauem Stab, sonst roter Rahmen). Standardmäßig an: Ohne
     * gefundenen Rahmen wertet analysis.py ohnehin das ganze Bild aus und
     * meldet das (frame_found=false) — Fotos ohne Rahmen funktionieren also
     * weiter wie bisher.
     */
    public static final boolean DEFAULT_FRAME_CROP = true;

    private final SharedPreferences prefs;

    public AnalysisSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateV18();
    }

    /**
     * Einmalige Umstellung auf die neue Standardmethode: Wer bis v1.7 "exg"
     * gespeichert hatte (meist nur, weil es damals die Empfehlung war), wird
     * auf "soilref" umgestellt — ExG + Otsu lieferte im Feldversuch bei tiefer
     * Sonne Messfehler (CSC ~90 % bzw. −100 %). Danach ist das Flag gesetzt:
     * Wer ExG später bewusst wieder wählt, behält es.
     */
    private void migrateV18() {
        if (prefs.getBoolean(KEY_MIGRATED_V18, false)) return;
        SharedPreferences.Editor e = prefs.edit();
        if (METHOD_EXG.equals(prefs.getString(KEY_METHOD, null))) {
            e.putString(KEY_METHOD, METHOD_SOILREF);
        }
        e.putBoolean(KEY_MIGRATED_V18, true).apply();
    }

    public int getMinSize()   { return prefs.getInt(KEY_MIN_SIZE, DEFAULT_MIN_SIZE); }
    public int getHueLow()    { return prefs.getInt(KEY_H_LOW, DEFAULT_H_LOW); }
    public int getHueHigh()   { return prefs.getInt(KEY_H_HIGH, DEFAULT_H_HIGH); }

    public String getMethod() { return prefs.getString(KEY_METHOD, DEFAULT_METHOD); }

    public void setMethod(String method) {
        if (METHOD_SOILREF.equals(method) || METHOD_HSV.equals(method)
                || METHOD_EXG.equals(method) || METHOD_EXGR.equals(method)) {
            // Flag mitsetzen: eine bewusste Wahl wird nie mehr migriert
            prefs.edit().putString(KEY_METHOD, method).putBoolean(KEY_MIGRATED_V18, true).apply();
        }
    }

    public boolean getFrameCrop() { return prefs.getBoolean(KEY_FRAME_CROP, DEFAULT_FRAME_CROP); }

    public void setFrameCrop(boolean frameCrop) {
        prefs.edit().putBoolean(KEY_FRAME_CROP, frameCrop).apply();
    }
    // Bänder verlustfrei als Double-Bits gespeichert: Der frühere float-Cast
    // machte aus 8.3 den Wert 8.300000190... — ein CSC exakt auf der
    // Bandgrenze konnte dadurch auf die falsche Seite kippen. Der Fallback
    // auf die alten float-Keys migriert Bestandsdaten aus v1.5.
    public double getCscBandLow() {
        if (prefs.contains(KEY_CSC_LOW_BITS)) {
            return Double.longBitsToDouble(prefs.getLong(KEY_CSC_LOW_BITS,
                    Double.doubleToLongBits(CscCalculator.DEFAULT_BAND_LOW)));
        }
        return prefs.getFloat(KEY_CSC_LOW, (float) CscCalculator.DEFAULT_BAND_LOW);
    }

    public double getCscBandHigh() {
        if (prefs.contains(KEY_CSC_HIGH_BITS)) {
            return Double.longBitsToDouble(prefs.getLong(KEY_CSC_HIGH_BITS,
                    Double.doubleToLongBits(CscCalculator.DEFAULT_BAND_HIGH)));
        }
        return prefs.getFloat(KEY_CSC_HIGH, (float) CscCalculator.DEFAULT_BAND_HIGH);
    }

    /**
     * Speichert alle Werte, sofern sie plausibel sind.
     * @return true wenn gespeichert wurde, false bei ungültigen Werten
     */
    public boolean save(int minSize, int hueLow, int hueHigh, double cscLow, double cscHigh) {
        boolean valid = minSize >= 0
                && hueLow >= 0 && hueHigh <= 179 && hueLow < hueHigh
                && cscLow >= 0 && cscLow < cscHigh && cscHigh <= 100;
        if (!valid) return false;

        prefs.edit()
                .putInt(KEY_MIN_SIZE, minSize)
                .putInt(KEY_H_LOW, hueLow)
                .putInt(KEY_H_HIGH, hueHigh)
                .putLong(KEY_CSC_LOW_BITS, Double.doubleToLongBits(cscLow))
                .putLong(KEY_CSC_HIGH_BITS, Double.doubleToLongBits(cscHigh))
                .remove(KEY_CSC_LOW)
                .remove(KEY_CSC_HIGH)
                .apply();
        return true;
    }

    public void reset() {
        // Migrations-Flag behalten: Sonst würde ein nach dem Zurücksetzen
        // bewusst gewähltes "exg" beim nächsten Start wieder umgestellt
        prefs.edit().clear().putBoolean(KEY_MIGRATED_V18, true).apply();
    }
}
