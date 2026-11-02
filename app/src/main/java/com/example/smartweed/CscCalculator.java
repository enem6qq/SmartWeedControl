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
     * (fehlende Werte, negative Bedeckungen oder Vorher-Bedeckung von 0 %).
     * Bedeckungsgrade sind per Definition 0..100 — eine negative Nachher-
     * Bedeckung ergäbe sonst kommentarlos einen CSC über 100 %.
     */
    public static Double compute(Double coverageBefore, Double coverageAfter) {
        if (coverageBefore == null || coverageAfter == null) return null;
        if (Double.isNaN(coverageBefore) || Double.isNaN(coverageAfter)) return null;
        if (coverageBefore <= 0 || coverageAfter < 0) return null;
        return (coverageBefore - coverageAfter) / coverageBefore * 100.0;
    }

    /**
     * CSC mit Kontrollflächen-Korrektur (v1.8).
     *
     * Eine unbehandelte Kontrollfläche, die zur selben Zeit unter demselben
     * Licht vorher und nachher fotografiert wurde, erfasst alle Effekte, die
     * NICHTS mit dem Striegeln zu tun haben (Lichtwechsel über den Tag,
     * Tau, Wind). Im Feldversuch (30./31.10.2026) zeigte die Kontrolle allein
     * durch den Lichtwechsel einen "CSC" von 8–11 %. Diese Änderung wird
     * herausgerechnet:
     *
     *   CSC_korr = (1 − (nachher / vorher) / (kontrolle_nachher / kontrolle_vorher)) × 100
     *
     * Ohne Veränderung der Kontrolle (Verhältnis 1) ist das Ergebnis identisch
     * mit {@link #compute(Double, Double)}.
     *
     * @return korrigierter CSC in Prozent, oder null bei unbrauchbaren Eingaben
     *         (ein Wert null/NaN/unendlich, vorher ≤ 0, Kontrolle vorher ≤ 0,
     *         Kontrolle nachher ≤ 0 oder nachher &lt; 0)
     */
    public static Double computeControlCorrected(Double before, Double after,
                                                 Double controlBefore, Double controlAfter) {
        if (before == null || after == null || controlBefore == null || controlAfter == null) return null;
        if (!isFinite(before) || !isFinite(after) || !isFinite(controlBefore) || !isFinite(controlAfter)) {
            return null;
        }
        if (before <= 0 || after < 0 || controlBefore <= 0 || controlAfter <= 0) return null;
        double treatedRatio = after / before;
        double controlRatio = controlAfter / controlBefore;
        double corrected = (1.0 - treatedRatio / controlRatio) * 100.0;
        return isFinite(corrected) ? corrected : null;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** Empfehlung anhand des Standard-Zielbands (8–12 %). */
    public static Recommendation recommend(Double csc) {
        return recommend(csc, DEFAULT_BAND_LOW, DEFAULT_BAND_HIGH);
    }

    /** Empfehlung anhand eines konfigurierbaren Zielbands. */
    public static Recommendation recommend(Double csc, double bandLow, double bandHigh) {
        if (csc == null || Double.isNaN(csc)) return Recommendation.NOT_AVAILABLE;
        // Degeneriertes Band defensiv normalisieren: Mit bandLow > bandHigh
        // wäre sonst KEIN Wert je OPTIMAL. AnalysisSettings.save validiert das
        // zwar, aber als öffentliche API soll die Methode selbst robust sein.
        if (bandLow > bandHigh) {
            double t = bandLow;
            bandLow = bandHigh;
            bandHigh = t;
        }
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
