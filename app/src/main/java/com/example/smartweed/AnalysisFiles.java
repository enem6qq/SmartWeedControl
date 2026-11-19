package com.example.smartweed;

import java.util.Locale;

/**
 * Gemeinsame Namen einer Analyse-Ablage (App-Speicher und Galerie): Bildgruppen,
 * ihre Unterordner und die Dateinamen der Auswertungsbilder. Die Analyse-Seite
 * (Export während der Analyse) und der Papierkorb (erneuter Export nach dem
 * Wiederherstellen) müssen exakt dieselben Namen verwenden.
 */
public final class AnalysisFiles {

    public static final int GROUP_BEFORE = 0;
    public static final int GROUP_AFTER = 1;
    /** optionale, unbehandelte Kontrollfläche (gleiches Licht) */
    public static final int GROUP_CONTROL_BEFORE = 2;
    public static final int GROUP_CONTROL_AFTER = 3;
    public static final int GROUP_COUNT = 4;

    /** Tags für analyze_image/build_group_summary (müssen zu analysis.py passen) */
    static final String[] TAGS = {"before", "after", "control_before", "control_after"};
    /** Unterordner im Analyse-Ordner (App-Speicher und Galerie) */
    static final String[] GROUP_DIRS = {
            SessionStore.ANALYSIS_BEFORE_DIR, SessionStore.ANALYSIS_AFTER_DIR,
            SessionStore.ANALYSIS_CONTROL_BEFORE_DIR, SessionStore.ANALYSIS_CONTROL_AFTER_DIR};
    /** Präfix der Auswertungsbilder in der Galerie (Vorher_01_Auswertung.jpg …) */
    static final String[] GALLERY_PREFIX = {
            "Vorher", "Nachher", "Kontrolle_Vorher", "Kontrolle_Nachher"};

    private AnalysisFiles() { }

    /** Gruppen-Index zu einem Tag aus analysis.py, -1 bei unbekanntem Tag */
    public static int groupForTag(String tag) {
        for (int g = 0; g < GROUP_COUNT; g++) {
            if (TAGS[g].equals(tag)) return g;
        }
        return -1;
    }

    /**
     * Galerie-Name eines Auswertungsbildes:
     *   Pictures/SmartWeed/&lt;Session&gt;/&lt;Analyse_HH-mm-ss_M&gt;/
     *     00_Uebersicht_&lt;Session&gt;.jpg (weitere Seiten: ..._2.jpg, ..._3.jpg)
     *     Vorher_01_Auswertung.jpg, Nachher_01_Auswertung.jpg,
     *     Kontrolle_Vorher_01_Auswertung.jpg, ...
     * Masken und Zwischenbilder bleiben versteckt im details/-Ordner der App.
     *
     * @param index 0-basierte Position des Bildes in seiner Gruppe
     */
    public static String panelName(int group, int index) {
        return String.format(Locale.US, "%s_%02d_Auswertung.jpg", GALLERY_PREFIX[group], index + 1);
    }

    /**
     * Galerie-Name des Übersichtsbilds — die führende "00_" sortiert es im
     * Galerie-Ordner an den Anfang.
     *
     * @param page 1-basierte Seitennummer (v1.9: viele Bilder ergeben mehrere Seiten)
     */
    public static String overviewName(String sessionName, int page) {
        return "00_Uebersicht_" + sessionName + (page <= 1 ? "" : "_" + page) + ".jpg";
    }
}
