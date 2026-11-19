package com.example.smartweed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reine Logik aus v1.9 (ohne Android-Laufzeit): Namen der Analyse-Ablage,
 * Umschreiben gespeicherter Pfade, CSV-Zellen und das Zeitfenster zum
 * Fortsetzen einer Kamera-Session.
 */
public class V19LogicTest {

    // ---- AnalysisFiles -------------------------------------------------

    @Test
    public void panelName_istEinsBasiertUndZweistellig() {
        assertEquals("Vorher_01_Auswertung.jpg", AnalysisFiles.panelName(AnalysisFiles.GROUP_BEFORE, 0));
        assertEquals("Nachher_12_Auswertung.jpg", AnalysisFiles.panelName(AnalysisFiles.GROUP_AFTER, 11));
        assertEquals("Kontrolle_Vorher_03_Auswertung.jpg",
                AnalysisFiles.panelName(AnalysisFiles.GROUP_CONTROL_BEFORE, 2));
        assertEquals("Kontrolle_Nachher_100_Auswertung.jpg",
                AnalysisFiles.panelName(AnalysisFiles.GROUP_CONTROL_AFTER, 99));
    }

    @Test
    public void overviewName_ersteSeiteOhneNummer() {
        assertEquals("00_Uebersicht_2026-11-19_09-10.jpg", AnalysisFiles.overviewName("2026-11-19_09-10", 1));
        assertEquals("00_Uebersicht_Feld A_2.jpg", AnalysisFiles.overviewName("Feld A", 2));
        assertEquals("00_Uebersicht_Feld A_3.jpg", AnalysisFiles.overviewName("Feld A", 3));
    }

    @Test
    public void groupForTag_kenntAlleTagsVonAnalysisPy() {
        assertEquals(AnalysisFiles.GROUP_BEFORE, AnalysisFiles.groupForTag("before"));
        assertEquals(AnalysisFiles.GROUP_AFTER, AnalysisFiles.groupForTag("after"));
        assertEquals(AnalysisFiles.GROUP_CONTROL_BEFORE, AnalysisFiles.groupForTag("control_before"));
        assertEquals(AnalysisFiles.GROUP_CONTROL_AFTER, AnalysisFiles.groupForTag("control_after"));
        assertEquals(-1, AnalysisFiles.groupForTag("vorher"));
        assertEquals(-1, AnalysisFiles.groupForTag(null));
    }

    // ---- ResultStore: Pfade umschreiben ---------------------------------

    @Test
    public void rebasePath_schreibtUnterpfadeUm() {
        String oldDir = "/data/SmartWeed/2026-11-19_09-10/Analyse_09-40-12_A";
        String newDir = "/data/SmartWeed/Feld A/Analyse_09-40-12_A";
        assertEquals(newDir + "/details/before_00_panel.jpg",
                ResultStore.rebasePath(oldDir + "/details/before_00_panel.jpg", oldDir, newDir));
        assertEquals(newDir, ResultStore.rebasePath(oldDir, oldDir, newDir));
    }

    @Test
    public void rebasePath_laesstFremdeUndAehnlichePfadeInRuhe() {
        String oldDir = "/data/SmartWeed/S/Analyse_1";
        String newDir = "/data/SmartWeed/T/Analyse_1";
        // "Analyse_1" darf "Analyse_10" nicht treffen
        assertEquals("/data/SmartWeed/S/Analyse_10/x.jpg",
                ResultStore.rebasePath("/data/SmartWeed/S/Analyse_10/x.jpg", oldDir, newDir));
        assertEquals("/anderswo/x.jpg", ResultStore.rebasePath("/anderswo/x.jpg", oldDir, newDir));
        assertEquals("", ResultStore.rebasePath("", oldDir, newDir));
        assertEquals(null, ResultStore.rebasePath(null, oldDir, newDir));
    }

    // ---- ResultStore: CSV-Zellen ----------------------------------------

    @Test
    public void csvCell_setztNurBeiBedarfAnfuehrungszeichen() {
        assertEquals("IMG_1234.jpg", ResultStore.csvCell("IMG_1234.jpg"));
        assertEquals("12,34", ResultStore.csvCell("12,34"));
        assertEquals("\"a;b.jpg\"", ResultStore.csvCell("a;b.jpg"));
        assertEquals("\"sagt \"\"hallo\"\".jpg\"", ResultStore.csvCell("sagt \"hallo\".jpg"));
        assertEquals("\"zeile1\nzeile2\"", ResultStore.csvCell("zeile1\nzeile2"));
        assertEquals("", ResultStore.csvCell(null));
    }

    @Test
    public void csvDisplayName_enthaeltSessionUndAnalyse() {
        java.io.File dir = new java.io.File("/data/SmartWeed/Feld A/Analyse_09-40-12_B");
        assertEquals("Ergebnis_Feld A_Analyse_09-40-12_B.csv", ResultStore.csvDisplayName(dir));
    }

    // ---- SessionStore: Fortsetzen einer Kamera-Session -------------------

    @Test
    public void isResumable_innerhalbDesZeitfensters() {
        long now = 1_800_000_000_000L;
        assertTrue(SessionStore.isResumable("2026-11-19_09-10", now - 30 * 60 * 1000L, now));
        assertTrue(SessionStore.isResumable("Feld A", now, now));
        assertTrue(SessionStore.isResumable("Feld A", now - SessionStore.RESUME_WINDOW_MS + 1, now));
    }

    @Test
    public void isResumable_zuAltOderUnbrauchbar() {
        long now = 1_800_000_000_000L;
        assertFalse(SessionStore.isResumable("Feld A", now - SessionStore.RESUME_WINDOW_MS, now));
        assertFalse(SessionStore.isResumable("Feld A", now - 3 * SessionStore.RESUME_WINDOW_MS, now));
        assertFalse(SessionStore.isResumable(null, now, now));
        assertFalse(SessionStore.isResumable("", now, now));
        assertFalse(SessionStore.isResumable("Feld A", 0, now));
        // Uhr zurückgestellt: letzte Nutzung liegt "in der Zukunft"
        assertFalse(SessionStore.isResumable("Feld A", now + 60_000L, now));
    }
}
