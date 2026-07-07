package com.example.smartweed;

/**
 * Berechnet den Crop Soil Cover (CSC) aus Vorher-/Nachher-Bedeckungsgraden
 * und leitet daraus eine Handlungsempfehlung ab.
 *
 * CSC = (Bedeckung vorher − Bedeckung nachher) / Bedeckung vorher × 100
 *
 * Empirisch gilt ein CSC von ca. 10 % als optimal (siehe Businessplan):
 * darunter kann aggressiver gestriegelt werden, darüber sollte die
 * Intensität reduziert werden. Reine Rechenlogik ohne Android-Abhängigkeiten,
 * damit sie per JUnit testbar ist.
 */
public final class CscCalculator {

    /** Standard-Zielband um den optimalen CSC von ~10 % */
    public static final double DEFAULT_BAND_LOW = 8.0;
    public static final double DEFAULT_BAND_HIGH = 12.0;

    /** Vorher-Bedeckung unterhalb dieser Schwelle gilt als "kein Bewuchs" (in %) */
    public static final double MIN_PLANT_COVERAGE_PERCENT = 0.1;

    public enum Recommendation {
        /** CSC unter dem Zielband: es ist Luft nach oben */
        MORE_AGGRESSIVE,
        /** CSC im Zielband: Einstellung passt */
        OPTIMAL,
        /** CSC über dem Zielband: Kulturpflanze nimmt Schaden */
        LESS_AGGRESSIVE,
        /** CSC negativ: Nachher-Bild hat mehr Bewuchs als Vorher-Bild — Bildpaar vermutlich nicht vergleichbar */
        CHECK_IMAGES,
        /** (Fast) kein Grün im Vorher-Bild — Aufnahmen zeigen vermutlich keinen Bestand */
        NO_VEGETATION,
        /** Keine verwertbaren Eingangswerte */
        NOT_AVAILABLE
    }

    private CscCalculator() { }

    /**
     * CSC in Prozent, oder null wenn die Eingaben unbrauchbar sind
     * (fehlende Werte oder Vorher-Bedeckung von 0 %).
     */
    public static Double compute(Double coverageBefore, Double coverageAfter) {
        if (coverageBefore == null || coverageAfter == null) return null;
        if (Double.isNaN(coverageBefore) || Double.isNaN(coverageAfter)) return null;
        if (coverageBefore <= 0) return null;
        return (coverageBefore - coverageAfter) / coverageBefore * 100.0;
    }

    /** Empfehlung anhand des Standard-Zielbands (8–12 %). */
    public static Recommendation recommend(Double csc) {
        return recommend(csc, DEFAULT_BAND_LOW, DEFAULT_BAND_HIGH);
    }

    /** Empfehlung anhand eines konfigurierbaren Zielbands. */
    public static Recommendation recommend(Double csc, double bandLow, double bandHigh) {
        if (csc == null || Double.isNaN(csc)) return Recommendation.NOT_AVAILABLE;
        if (csc < 0) return Recommendation.CHECK_IMAGES;
        if (csc < bandLow) return Recommendation.MORE_AGGRESSIVE;
        if (csc <= bandHigh) return Recommendation.OPTIMAL;
        return Recommendation.LESS_AGGRESSIVE;
    }

    /**
     * Empfehlung direkt aus den Bedeckungsgraden. Erkennt zusätzlich den Fall
     * "kein Bewuchs" (Vorher-Bedeckung praktisch 0 %), der sonst nur als
     * nichtssagendes "n. a." erscheinen würde.
     */
    public static Recommendation recommendForCoverage(Double coverageBefore, Double coverageAfter,
                                                      double bandLow, double bandHigh) {
        if (coverageBefore == null || coverageAfter == null) return Recommendation.NOT_AVAILABLE;
        if (Double.isNaN(coverageBefore) || Double.isNaN(coverageAfter)) return Recommendation.NOT_AVAILABLE;
        if (coverageBefore < MIN_PLANT_COVERAGE_PERCENT) return Recommendation.NO_VEGETATION;
        return recommend(compute(coverageBefore, coverageAfter), bandLow, bandHigh);
    }
}
