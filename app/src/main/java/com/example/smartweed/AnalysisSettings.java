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

    public static final int DEFAULT_MIN_SIZE = 50;
    public static final int DEFAULT_H_LOW = 35;
    public static final int DEFAULT_H_HIGH = 85;

    /** Segmentierungsmethoden (müssen zu analysis.py passen) */
    public static final String METHOD_HSV = "hsv";
    public static final String METHOD_EXG = "exg";
    public static final String METHOD_EXGR = "exgr";
    public static final String DEFAULT_METHOD = METHOD_EXG;

    /**
     * Nur das Innere eines roten Zählrahmens auswerten. Standardmäßig an: Ohne
     * gefundenen Rahmen wertet analysis.py ohnehin das ganze Bild aus und
     * meldet das (frame_found=false) — Fotos ohne Rahmen funktionieren also
     * weiter wie bisher.
     */
    public static final boolean DEFAULT_FRAME_CROP = true;

    private final SharedPreferences prefs;

    public AnalysisSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getMinSize()   { return prefs.getInt(KEY_MIN_SIZE, DEFAULT_MIN_SIZE); }
    public int getHueLow()    { return prefs.getInt(KEY_H_LOW, DEFAULT_H_LOW); }
    public int getHueHigh()   { return prefs.getInt(KEY_H_HIGH, DEFAULT_H_HIGH); }

    public String getMethod() { return prefs.getString(KEY_METHOD, DEFAULT_METHOD); }

    public void setMethod(String method) {
        if (METHOD_HSV.equals(method) || METHOD_EXG.equals(method) || METHOD_EXGR.equals(method)) {
            prefs.edit().putString(KEY_METHOD, method).apply();
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
        prefs.edit().clear().apply();
    }
}
