package com.example.smartweed;

import android.content.Context;
import android.os.Environment;
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
import java.util.Iterator;
import java.util.List;

/**
 * Verwaltet den Papierkorb fuer geloeschte Bilder.
 * Bilder werden in einen .trash-Ordner verschoben statt permanent geloescht,
 * sodass sie bei Bedarf wiederhergestellt werden koennen.
 */
public class TrashManager {

    private static final String TAG = "TrashManager";
    private static final String TRASH_DIR_NAME = ".trash";
    private static final String METADATA_FILE = "trash_metadata.json";
    private static final long AUTO_DELETE_DAYS = 30;

    private final Context context;
    private final File trashDir;
    private final File metadataFile;

    public TrashManager(Context context) {
        this.context = context.getApplicationContext();
        File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartWeed");
        trashDir = new File(baseDir, TRASH_DIR_NAME);
        if (!trashDir.exists()) trashDir.mkdirs();
        metadataFile = new File(trashDir, METADATA_FILE);
    }

    /** Ein einzelnes Bild in den Papierkorb verschieben */
    public boolean moveToTrash(File file) {
        if (file == null || !file.exists()) return false;

        try {
            String trashName = System.currentTimeMillis() + "_" + file.getName();
            File trashFile = new File(trashDir, trashName);

            if (copyFile(file, trashFile)) {
                // Metadaten speichern
                JSONObject meta = loadMetadata();
                JSONObject entry = new JSONObject();
                entry.put("originalPath", file.getAbsolutePath());
                entry.put("trashName", trashName);
                entry.put("originalName", file.getName());
                entry.put("deletedAt", System.currentTimeMillis());
                entry.put("fileSize", file.length());

                JSONArray items = meta.optJSONArray("items");
                if (items == null) {
                    items = new JSONArray();
                }
                items.put(entry);
                meta.put("items", items);
                saveMetadata(meta);

                // Original loeschen
                file.delete();
                Log.i(TAG, "Moved to trash: " + file.getName() + " -> " + trashName);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error moving to trash: " + file.getName(), e);
        }
        return false;
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
                    f.delete();
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
        try {
            JSONObject meta = loadMetadata();
            JSONArray items = meta.optJSONArray("items");
            if (items == null) return false;

            for (int i = 0; i < items.length(); i++) {
                JSONObject entry = items.getJSONObject(i);
                if (trashName.equals(entry.optString("trashName"))) {
                    File trashFile = new File(trashDir, trashName);
                    if (!trashFile.exists()) {
                        // Datei nicht mehr vorhanden, Eintrag entfernen
                        items.remove(i);
                        saveMetadata(meta);
                        return false;
                    }

                    String originalPath = entry.optString("originalPath");
                    File originalFile = new File(originalPath);
                    File parentDir = originalFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }

                    // Falls Original-Pfad belegt, alternativen Namen waehlen
                    File restoreTarget = originalFile;
                    if (restoreTarget.exists()) {
                        String name = entry.optString("originalName", trashFile.getName());
                        int dot = name.lastIndexOf('.');
                        String base = (dot > 0) ? name.substring(0, dot) : name;
                        String ext = (dot > 0) ? name.substring(dot) : "";
                        restoreTarget = new File(parentDir, base + "_restored" + ext);
                    }

                    if (copyFile(trashFile, restoreTarget)) {
                        trashFile.delete();
                        items.remove(i);
                        saveMetadata(meta);
                        Log.i(TAG, "Restored from trash: " + trashName + " -> " + restoreTarget.getAbsolutePath());
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restoring from trash: " + trashName, e);
        }
        return false;
    }

    /** Ein einzelnes Element permanent aus dem Papierkorb loeschen */
    public boolean deletePermanently(String trashName) {
        try {
            File trashFile = new File(trashDir, trashName);
            if (trashFile.exists()) trashFile.delete();

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

    /** Papierkorb komplett leeren */
    public void emptyTrash() {
        File[] files = trashDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().equals(METADATA_FILE)) {
                    f.delete();
                }
            }
        }
        // Metadata zuruecksetzen
        try {
            JSONObject meta = new JSONObject();
            meta.put("items", new JSONArray());
            saveMetadata(meta);
        } catch (Exception e) {
            Log.e(TAG, "Error emptying trash", e);
        }
        Log.i(TAG, "Trash emptied");
    }

    /** Alte Eintraege automatisch loeschen (aelter als AUTO_DELETE_DAYS Tage) */
    public int autoCleanup() {
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
                    // Abgelaufen -> permanent loeschen
                    String trashName = entry.optString("trashName");
                    File trashFile = new File(trashDir, trashName);
                    if (trashFile.exists()) trashFile.delete();
                    removed++;
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

    /** Alle Papierkorb-Eintraege laden */
    public List<TrashItem> getTrashItems() {
        List<TrashItem> result = new ArrayList<>();
        try {
            JSONObject meta = loadMetadata();
            JSONArray items = meta.optJSONArray("items");
            if (items == null) return result;

            for (int i = 0; i < items.length(); i++) {
                JSONObject entry = items.getJSONObject(i);
                File trashFile = new File(trashDir, entry.optString("trashName"));
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

    /** Anzahl der Elemente im Papierkorb */
    public int getTrashCount() {
        try {
            JSONObject meta = loadMetadata();
            JSONArray items = meta.optJSONArray("items");
            return (items != null) ? items.length() : 0;
        } catch (Exception e) {
            return 0;
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
            return new JSONObject();
        }
    }

    private void saveMetadata(JSONObject meta) {
        try {
            byte[] bytes = meta.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(metadataFile)) {
                fos.write(bytes);
                fos.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving metadata", e);
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
            return false;
        }
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }
}
