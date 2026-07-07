package com.example.smartweed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests für die CSC-Berechnung und die Empfehlungslogik — den fachlichen
 * Kern der App. Läuft als lokaler JUnit-Test ohne Emulator:
 * ./gradlew testDebugUnitTest
 */
public class CscCalculatorTest {

    private static final double EPS = 0.0001;

    // ---------- compute() ----------

    @Test
    public void compute_typicalReduction() {
        // 20 % Bedeckung vorher, 18 % nachher → 10 % der Pflanzen bedeckt
        assertEquals(10.0, CscCalculator.compute(20.0, 18.0), EPS);
    }

    @Test
    public void compute_noChange_returnsZero() {
        assertEquals(0.0, CscCalculator.compute(15.0, 15.0), EPS);
    }

    @Test
    public void compute_fullLoss_returnsHundred() {
        assertEquals(100.0, CscCalculator.compute(12.5, 0.0), EPS);
    }

    @Test
    public void compute_moreGreenAfter_returnsNegative() {
        // Nachher mehr grün als vorher → negativer CSC (Bildpaar fragwürdig)
        assertEquals(-25.0, CscCalculator.compute(20.0, 25.0), EPS);
    }

    @Test
    public void compute_nullInputs_returnNull() {
        assertNull(CscCalculator.compute(null, 10.0));
        assertNull(CscCalculator.compute(10.0, null));
        assertNull(CscCalculator.compute(null, null));
    }

    @Test
    public void compute_zeroBefore_returnsNull() {
        // Division durch 0 vermeiden: 0 % Bedeckung vorher ist nicht auswertbar
        assertNull(CscCalculator.compute(0.0, 5.0));
    }

    @Test
    public void compute_nanInputs_returnNull() {
        assertNull(CscCalculator.compute(Double.NaN, 10.0));
        assertNull(CscCalculator.compute(10.0, Double.NaN));
    }

    // ---------- recommend() mit Standard-Zielband 8–12 % ----------

    @Test
    public void recommend_belowBand_moreAggressive() {
        assertEquals(CscCalculator.Recommendation.MORE_AGGRESSIVE, CscCalculator.recommend(5.0));
        assertEquals(CscCalculator.Recommendation.MORE_AGGRESSIVE, CscCalculator.recommend(7.99));
    }

    @Test
    public void recommend_insideBand_optimal() {
        assertEquals(CscCalculator.Recommendation.OPTIMAL, CscCalculator.recommend(8.0));
        assertEquals(CscCalculator.Recommendation.OPTIMAL, CscCalculator.recommend(10.0));
        assertEquals(CscCalculator.Recommendation.OPTIMAL, CscCalculator.recommend(12.0));
    }

    @Test
    public void recommend_aboveBand_lessAggressive() {
        assertEquals(CscCalculator.Recommendation.LESS_AGGRESSIVE, CscCalculator.recommend(12.01));
        assertEquals(CscCalculator.Recommendation.LESS_AGGRESSIVE, CscCalculator.recommend(40.0));
    }

    @Test
    public void recommend_negative_checkImages() {
        assertEquals(CscCalculator.Recommendation.CHECK_IMAGES, CscCalculator.recommend(-3.0));
    }

    @Test
    public void recommend_zero_isMoreAggressiveNotError() {
        // 0 % CSC ist plausibel (nichts bedeckt) → aggressiver striegeln, kein Fehler
        assertEquals(CscCalculator.Recommendation.MORE_AGGRESSIVE, CscCalculator.recommend(0.0));
    }

    @Test
    public void recommend_null_notAvailable() {
        assertEquals(CscCalculator.Recommendation.NOT_AVAILABLE, CscCalculator.recommend(null));
        assertEquals(CscCalculator.Recommendation.NOT_AVAILABLE, CscCalculator.recommend(Double.NaN));
    }

    // ---------- recommend() mit konfiguriertem Zielband ----------

    @Test
    public void recommend_customBand() {
        assertEquals(CscCalculator.Recommendation.OPTIMAL, CscCalculator.recommend(6.0, 5.0, 15.0));
        assertEquals(CscCalculator.Recommendation.MORE_AGGRESSIVE, CscCalculator.recommend(4.9, 5.0, 15.0));
        assertEquals(CscCalculator.Recommendation.LESS_AGGRESSIVE, CscCalculator.recommend(15.1, 5.0, 15.0));
    }

    // ---------- recommendForCoverage() ----------

    @Test
    public void coverage_noVegetation_whenBeforeIsZero() {
        // Bilder ohne Grünanteil: klare Meldung statt "n. a."
        assertEquals(CscCalculator.Recommendation.NO_VEGETATION,
                CscCalculator.recommendForCoverage(0.0, 0.11, 8.0, 12.0));
        assertEquals(CscCalculator.Recommendation.NO_VEGETATION,
                CscCalculator.recommendForCoverage(0.05, 0.0, 8.0, 12.0));
    }

    @Test
    public void coverage_normalValues_delegateToCsc() {
        // 20 % → 18 % ist CSC 10 % → optimal
        assertEquals(CscCalculator.Recommendation.OPTIMAL,
                CscCalculator.recommendForCoverage(20.0, 18.0, 8.0, 12.0));
        // 20 % → 25 % ist negativ → Bildpaar prüfen
        assertEquals(CscCalculator.Recommendation.CHECK_IMAGES,
                CscCalculator.recommendForCoverage(20.0, 25.0, 8.0, 12.0));
    }

    @Test
    public void coverage_nullOrNan_notAvailable() {
        assertEquals(CscCalculator.Recommendation.NOT_AVAILABLE,
                CscCalculator.recommendForCoverage(null, 5.0, 8.0, 12.0));
        assertEquals(CscCalculator.Recommendation.NOT_AVAILABLE,
                CscCalculator.recommendForCoverage(5.0, Double.NaN, 8.0, 12.0));
    }

    @Test
    public void coverage_lowButRealVegetation_isNotFlagged() {
        // 1 % Bedeckung ist wenig, aber echter Bewuchs (z. B. frühes Stadium)
        assertEquals(CscCalculator.Recommendation.OPTIMAL,
                CscCalculator.recommendForCoverage(1.0, 0.9, 8.0, 12.0));
    }
}
