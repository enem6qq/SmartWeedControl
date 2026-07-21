package com.example.smartweed;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltet den Papierkorb fuer geloeschte Bilder.
 * Bilder werden in einen .trash-Ordner verschoben statt permanent geloescht,
 * sodass sie bei Bedarf wiederhergestellt werden koennen.
 *
 * Thread-Sicherheit: Alle Operationen, die die Metadaten-JSON lesen/schreiben,
 * laufen unter einem gemeinsamen Lock. Ohne das Lock koennten z. B. der
 * autoCleanup()-Thread beim App-Start und ein gleichzeitiges Session-Loeschen
 * sich gegenseitig Eintraege ueberschreiben (Read-Modify-Write-Kollision) —
 * die betroffenen Dateien laegen dann unsichtbar und unloeschbar im Papierkorb.
 */
public class TrashManager {

    private static final String TAG = "TrashManager";
    private static final String TRASH_DIR_NAME = ".trash";
    private static final String METADATA_FILE = "trash_metadata.json";
    private static final long AUTO_DELETE_DAYS = 30;

    /** Gemeinsames Lock aller TrashManager-Instanzen (die Metadaten-Datei ist global). */
    private static final Object METADATA_LOCK = new Object();

    private final Context context;
    private final File trashDir;
    private final File metadataFile;

    public TrashManager(Context context) {
        this.context = context.getApplicationContext();
        trashDir = new File(SessionStore.getBaseDir(this.context), TRASH_DIR_NAME);
        if (!trashDir.exists() && !trashDir.mkdirs()) {
            // Folgeoperationen scheitern dann zwar sichtbar (renameTo/copy false),
            // aber ohne diese Zeile fehlte im Log die eigentliche Ursache
            Log.e(TAG, "Could not create trash dir: " + trashDir.getAbsolutePath());
        }
        metadataFile = new File(trashDir, METADATA_FILE);
    }

    /** trashName stammt aus den (auf gerooteten Geräten/per USB manipulierbaren)
     *  Metadaten: Pfadanteile strippen, damit ein präparierter Eintrag nie auf
     *  Dateien außerhalb des Papierkorb-Ordners zeigen kann (Defense-in-depth,
     *  analog zur isInsideBaseDir-Prüfung für originalPath). */
    private File trashFileFor(String trashName) {
        return new File(trashDir, new File(trashName).getName());
    }

    /** Ein einzelnes Bild in den Papierkorb verschieben */
    public boolean moveToTrash(File file) {
        if (file == null || !file.exists()) return false;

        synchronized (METADATA_LOCK) {
            try {
                String trashName = System.currentTimeMillis() + "_" + file.getName();
                File trashFile = new File(trashDir, trashName);
                // Eindeutigkeit sicherstellen: gleichnamige Dateien in derselben
                // Millisekunde dürfen sich nicht gegenseitig überschreiben
                int counter = 1;
                while (trashFile.exists()) {
                    trashName = System.currentTimeMillis() + "_" + (counter++) + "_" + file.getName();
                    trashFile = new File(trashDir, trashName);
                }

                // Metadaten VOR dem Verschieben erfassen (Groesse/Name)
                long fileSize = file.length();
                String originalName = file.getName();
                String originalPath = file.getAbsolutePath();

                // Quelle und Papierkorb liegen im selben Verzeichnisbaum:
                // renameTo ist O(1); Copy+Delete nur als Fallback (z. B. falls
                // das Dateisystem kein Umbenennen erlaubt)
                boolean moved = file.renameTo(trashFile);
                if (!moved) {
                    if (!copyFile(file, trashFile)) return false;
                    if (!file.delete()) {
                        Log.w(TAG, "Original could not be deleted after copy: " + originalPath);
                    }
                }

                JSONObject meta = loadMetadata();
                JSONObject entry = new JSONObject();
                entry.put("originalPath", originalPath);
                entry.put("trashName", trashName);
                entry.put("originalName", originalName);
                entry.put("deletedAt", System.currentTimeMillis());
                entry.put("fileSize", fileSize);

                JSONArray items = meta.optJSONArray("items");
                if (items == null) {
                    items = new JSONArray();
                }
                items.put(entry);
                meta.put("items", items);
                if (!saveMetadata(meta)) {
                    // Ohne Metadaten-Eintrag läge die Datei unsichtbar und
                    // unwiederherstellbar im Papierkorb (getTrashItems liest nur
                    // Einträge) -> Verschieben rückgängig machen und ehrlich
                    // fehlschlagen, statt Erfolg zu melden
                    if (!trashFile.renameTo(file)
                            && !(copyFile(trashFile, file) && trashFile.delete())) {
                        Log.e(TAG, "Rollback after metadata failure failed: " + trashName);
                    }
                    return false;
                }

                Log.i(TAG, "Moved to trash: " + originalName + " -> " + trashName);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error moving to trash: " + file.getName(), e);
            }
            return false;
        }
    }

    /**
     * Einen ganzen Ordner (Session inkl. Unterordner) in den Papierkorb verschieben.
     * Bilder werden wiederherstellbar in den Papierkorb gelegt; Hilfsdateien
     * (.nomedia, summary.json) werden direkt geloescht. Leere Ordner werden entfernt.
     * @return Anzahl der in den Papierkorb verschobenen Bilder
     */
    public int moveDirectoryToTrash(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    count += moveDirectoryToTrash(f);
                } else if (isImageFile(f.getName())) {
                    if (moveToTrash(f)) count++;
                } else {
                    // Hilfsdateien sind nicht wiederherstellbar -> direkt loeschen
                    if (!f.delete()) {
                        Log.w(TAG, "Could not delete helper file: " + f.getAbsolutePath());
                    }
                }
            }
        }
        // Leeren Ordner aufraeumen
        File[] remaining = dir.listFiles();
        if (remaining == null || remaining.length == 0) {
            dir.delete();
        }
        return count;
    }

    /** Bild aus dem Papierkorb wiederherstellen */
    public boolean restoreFromTrash(String trashName) {
        synchronized (METADATA_LOCK) {
            try {
                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                if (items == null) return false;

                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.getJSONObject(i);
                    if (trashName.equals(entry.optString("trashName"))) {
                        File trashFile = trashFileFor(trashName);
                        if (!trashFile.exists()) {
                            // Datei nicht mehr vorhanden, Eintrag entfernen
                            items.remove(i);
                            saveMetadata(meta);
                            return false;
                        }

                        String originalPath = entry.optString("originalPath");
                        File originalFile = new File(originalPath);
                        // Schutz vor manipulierten Metadaten: nur innerhalb des
                        // App-Datenverzeichnisses wiederherstellen
                        if (!isInsideBaseDir(originalFile)) {
                            Log.w(TAG, "Restore target outside base dir refused: " + originalPath);
                            return false;
                        }
                        File parentDir = originalFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs();
                        }

                        // Falls Original-Pfad belegt, alternativen Namen waehlen.
                        // In einer Schleife: renameTo ueberschreibt ein
                        // existierendes Ziel stillschweigend — ein einzelner
                        // fester Ausweichname (_restored) haette bei der
                        // zweiten Wiederherstellung desselben Namens die erste
                        // zerstoert (Datenverlust im Restore-Feature selbst).
                        File restoreTarget = originalFile;
                        if (restoreTarget.exists()) {
                            String name = entry.optString("originalName", trashFile.getName());
                            int dot = name.lastIndexOf('.');
                            String base = (dot > 0) ? name.substring(0, dot) : name;
                            String ext = (dot > 0) ? name.substring(dot) : "";
                            restoreTarget = new File(parentDir, base + "_restored" + ext);
                            int n = 2;
                            while (restoreTarget.exists()) {
                                restoreTarget = new File(parentDir, base + "_restored_" + (n++) + ext);
                            }
                        }

                        boolean moved = trashFile.renameTo(restoreTarget);
                        if (!moved) {
                            if (!copyFile(trashFile, restoreTarget)) return false;
                            if (!trashFile.delete()) {
                                Log.w(TAG, "Trash file could not be deleted after restore: " + trashName);
                            }
                        }
                        items.remove(i);
                        saveMetadata(meta);
                        Log.i(TAG, "Restored from trash: " + trashName + " -> " + restoreTarget.getAbsolutePath());
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error restoring from trash: " + trashName, e);
            }
            return false;
        }
    }

    /** Ein einzelnes Element permanent aus dem Papierkorb loeschen */
    public boolean deletePermanently(String trashName) {
        synchronized (METADATA_LOCK) {
            try {
                File trashFile = trashFileFor(trashName);
                boolean fileGone = !trashFile.exists() || trashFile.delete();
                if (!fileGone) {
                    // Datei existiert weiterhin -> keinen Erfolg vortaeuschen,
                    // Metadaten-Eintrag behalten, damit sie sichtbar bleibt
                    Log.w(TAG, "Could not delete trash file: " + trashName);
                    return false;
                }

                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject entry = items.getJSONObject(i);
                        if (trashName.equals(entry.optString("trashName"))) {
                            items.remove(i);
                            saveMetadata(meta);
                            break;
                        }
                    }
                }
                Log.i(TAG, "Permanently deleted: " + trashName);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Error deleting permanently: " + trashName, e);
            }
            return false;
        }
    }

    /** Papierkorb komplett leeren */
    public void emptyTrash() {
        synchronized (METADATA_LOCK) {
            File[] files = trashDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().equals(METADATA_FILE)) {
                        if (!f.delete()) {
                            Log.w(TAG, "Could not delete trash file: " + f.getName());
                        }
                    }
                }
            }
            // Metadata zuruecksetzen; Eintraege nicht geloeschter Dateien behalten
            try {
                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                JSONArray keep = new JSONArray();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject entry = items.getJSONObject(i);
                        File f = trashFileFor(entry.optString("trashName"));
                        if (f.exists()) keep.put(entry);
                    }
                }
                JSONObject fresh = new JSONObject();
                fresh.put("items", keep);
                saveMetadata(fresh);
            } catch (Exception e) {
                Log.e(TAG, "Error emptying trash", e);
            }
            Log.i(TAG, "Trash emptied");
        }
    }

    /** Alte Eintraege automatisch loeschen (aelter als AUTO_DELETE_DAYS Tage) */
    public int autoCleanup() {
        synchronized (METADATA_LOCK) {
            int removed = 0;
            try {
                long cutoff = System.currentTimeMillis() - (AUTO_DELETE_DAYS * 24 * 60 * 60 * 1000L);
                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                if (items == null) return 0;

                JSONArray remaining = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.getJSONObject(i);
                    long deletedAt = entry.optLong("deletedAt", 0);
                    if (deletedAt > 0 && deletedAt < cutoff) {
                        // Abgelaufen -> permanent loeschen; bei Fehlschlag den
                        // Eintrag behalten und beim naechsten Lauf erneut versuchen
                        String trashName = entry.optString("trashName");
                        File trashFile = trashFileFor(trashName);
                        if (!trashFile.exists() || trashFile.delete()) {
                            removed++;
                        } else {
                            Log.w(TAG, "Auto-cleanup could not delete: " + trashName);
                            remaining.put(entry);
                        }
                    } else {
                        remaining.put(entry);
                    }
                }
                meta.put("items", remaining);
                saveMetadata(meta);
            } catch (Exception e) {
                Log.e(TAG, "Error during auto-cleanup", e);
            }
            if (removed > 0) Log.i(TAG, "Auto-cleanup removed " + removed + " items");
            return removed;
        }
    }

    /** Alle Papierkorb-Eintraege laden */
    public List<TrashItem> getTrashItems() {
        synchronized (METADATA_LOCK) {
            List<TrashItem> result = new ArrayList<>();
            try {
                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                if (items == null) return result;

                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.getJSONObject(i);
                    File trashFile = trashFileFor(entry.optString("trashName"));
                    if (!trashFile.exists()) continue;

                    TrashItem item = new TrashItem();
                    item.trashName = entry.optString("trashName");
                    item.originalName = entry.optString("originalName");
                    item.originalPath = entry.optString("originalPath");
                    item.deletedAt = entry.optLong("deletedAt", 0);
                    item.fileSize = entry.optLong("fileSize", 0);
                    item.trashFile = trashFile;
                    result.add(item);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading trash items", e);
            }
            return result;
        }
    }

    /** Anzahl der Elemente im Papierkorb — zaehlt wie getTrashItems() nur
     *  Eintraege, deren Datei noch existiert. Vorher zaehlte die Methode alle
     *  Metadaten-Eintraege: Der "Papierkorb leeren"-Dialog zeigte dann mehr
     *  Elemente an, als die Liste darstellt. */
    public int getTrashCount() {
        synchronized (METADATA_LOCK) {
            try {
                JSONObject meta = loadMetadata();
                JSONArray items = meta.optJSONArray("items");
                if (items == null) return 0;
                int count = 0;
                for (int i = 0; i < items.length(); i++) {
                    if (trashFileFor(items.getJSONObject(i).optString("trashName")).exists()) {
                        count++;
                    }
                }
                return count;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** Papierkorb-Verzeichnis holen (fuer Thumbnail-Loading) */
    public File getTrashDir() {
        return trashDir;
    }

    // === Datenklasse fuer ein Papierkorb-Element ===
    public static class TrashItem {
        public String trashName;
        public String originalName;
        public String originalPath;
        public long deletedAt;
        public long fileSize;
        public File trashFile;

        public String getFormattedDate() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "dd.MM.yyyy HH:mm", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(deletedAt));
        }

        public String getFormattedSize() {
            java.util.Locale loc = java.util.Locale.getDefault();
            if (fileSize < 1024) return fileSize + " B";
            if (fileSize < 1024 * 1024) return String.format(loc, "%.1f KB", fileSize / 1024.0);
            return String.format(loc, "%.1f MB", fileSize / (1024.0 * 1024.0));
        }

        /** Tage bis zur automatischen Loeschung */
        public int getDaysRemaining() {
            long elapsed = System.currentTimeMillis() - deletedAt;
            long remaining = (AUTO_DELETE_DAYS * 24 * 60 * 60 * 1000L) - elapsed;
            return Math.max(0, (int) (remaining / (24 * 60 * 60 * 1000L)));
        }
    }

    // === Private Hilfsmethoden ===

    /** Liegt die Datei (kanonisch aufgeloest) im App-Datenverzeichnis? */
    private boolean isInsideBaseDir(File f) {
        try {
            String base = SessionStore.getBaseDir(context).getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(base);
        } catch (IOException e) {
            return false;
        }
    }

    private JSONObject loadMetadata() {
        try {
            if (!metadataFile.exists()) return new JSONObject();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(metadataFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
            }
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "Error loading metadata", e);
            // Korrupte Datei zur Diagnose BEISEITE legen statt sie beim
            // naechsten saveMetadata stillschweigend mit einem (fast) leeren
            // Objekt zu ueberschreiben — die alten Eintraege waeren sonst
            // endgueltig weg, ohne jede Spur der Ursache.
            if (metadataFile.exists()) {
                File quarantine = new File(trashDir,
                        METADATA_FILE + ".corrupt-" + System.currentTimeMillis());
                if (!metadataFile.renameTo(quarantine)) {
                    Log.e(TAG, "Could not quarantine corrupt metadata file");
                }
            }
            return new JSONObject();
        }
    }

    /** @return true nur, wenn die Metadaten sicher auf der Platte sind */
    private boolean saveMetadata(JSONObject meta) {
        // Atomar schreiben: erst in eine Temp-Datei, dann umbenennen. Ein
        // Absturz mitten im Schreiben wuerde sonst die komplette Metadaten-
        // JSON zerstoeren (alle Papierkorb-Eintraege verwaist).
        File tmp = new File(trashDir, METADATA_FILE + ".tmp");
        try {
            byte[] bytes = meta.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(metadataFile)) {
                // Fallback (sollte auf demselben Dateisystem nie noetig sein)
                boolean copied = copyFile(tmp, metadataFile);
                if (!copied) {
                    Log.e(TAG, "Error saving metadata: rename and copy failed");
                }
                tmp.delete();
                return copied;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving metadata", e);
            tmp.delete();
            return false;
        }
    }

    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copying file", e);
            // Teilkopie nicht liegen lassen: Sie taeuchte im Papierkorb bzw.
            // Session-Ordner eine intakte (aber korrupte) Bilddatei vor
            if (dst.exists() && !dst.delete()) {
                Log.w(TAG, "Could not remove partial copy: " + dst.getAbsolutePath());
            }
            return false;
        }
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }
}
