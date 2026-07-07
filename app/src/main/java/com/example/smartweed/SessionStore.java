package com.example.smartweed;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Zentrale Ablage-Logik für Aufnahme-Sessions.
 *
 * Ordnerstruktur:
 *   Pictures/SmartWeed/
 *   └── 2026-07-07_18-58/          ← eine Session (ein Feldeinsatz)
 *       ├── vorher/                ← Kamera-Aufnahmen
 *       ├── nachher/
 *       └── Analyse_18-59-30/      ← Analyse-Ergebnis zur Session
 *           └── details/           ← Masken/Einzelbilder (.nomedia, galerie-versteckt)
 */
public final class SessionStore {

    /** Lesbares, sortierbares Namensschema für Session-Ordner */
    public static final String SESSION_NAME_PATTERN = "yyyy-MM-dd_HH-mm";

    /** Präfix für Analyse-Unterordner innerhalb einer Session */
    public static final String ANALYSIS_PREFIX = "Analyse_";

    public static final String BEFORE_DIR_NAME = "vorher";
    public static final String AFTER_DIR_NAME = "nachher";

    private static final String TRASH_DIR_NAME = ".trash";

    private SessionStore() { }

    /** Basisordner der App: Pictures/SmartWeed */
    public static File getBaseDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartWeed");
    }

    /** Daten einer Session für die Übersichts-Seite */
    public static class Session {
        public File dir;
        public int beforeCount;
        public int afterCount;
        public int analysisCount;
        public File thumbnail;
    }

    /** Alle Sessions, neueste zuerst (Papierkorb wird ausgeblendet) */
    public static List<Session> listSessions() {
        List<Session> result = new ArrayList<>();
        File[] dirs = getBaseDir().listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            if (TRASH_DIR_NAME.equals(dir.getName())) continue;

            Session s = new Session();
            s.dir = dir;
            s.beforeCount = countImages(new File(dir, BEFORE_DIR_NAME));
            s.afterCount = countImages(new File(dir, AFTER_DIR_NAME));

            File[] analyses = dir.listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(ANALYSIS_PREFIX));
            s.analysisCount = (analyses == null) ? 0 : analyses.length;

            s.thumbnail = findFirstImage(new File(dir, BEFORE_DIR_NAME));
            if (s.thumbnail == null) s.thumbnail = findFirstImage(new File(dir, AFTER_DIR_NAME));
            if (s.thumbnail == null) s.thumbnail = findFirstImage(dir);

            result.add(s);
        }

        // Neueste zuerst (Namensschema ist chronologisch sortierbar)
        Collections.sort(result, (a, b) -> b.dir.getName().compareTo(a.dir.getName()));
        return result;
    }

    /** Anzahl Bilder direkt in einem Ordner (nicht rekursiv) */
    public static int countImages(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && isImageFile(f.getName()));
        return (files == null) ? 0 : files.length;
    }

    /** Erstes Bild in einem Ordner (eine Ebene tief), z. B. als Vorschaubild */
    public static File findFirstImage(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && isImageFile(f.getName())) return f;
        }
        for (File f : files) {
            if (f.isDirectory() && !TRASH_DIR_NAME.equals(f.getName())) {
                File found = findFirstImage(f);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }
}
