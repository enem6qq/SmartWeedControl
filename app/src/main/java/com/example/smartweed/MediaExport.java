package com.example.smartweed;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Exportiert Bilddateien aus dem app-eigenen Speicher zusätzlich in die
 * öffentliche Galerie (Pictures/SmartWeed/...), damit Fotos und
 * Analyse-Übersichten wie gewohnt in Galerie und Bild-Picker erscheinen.
 *
 * Ab Android 10 (API 29) läuft das ohne jede Berechtigung über MediaStore
 * mit RELATIVE_PATH; auf Android 9 (API 28) über den klassischen
 * Pictures-Ordner (WRITE_EXTERNAL_STORAGE, maxSdkVersion 28).
 */
public final class MediaExport {

    private static final String TAG = "MediaExport";

    private MediaExport() { }

    /** Wurzelordner in der Galerie */
    public static final String GALLERY_ROOT = "SmartWeed";

    /**
     * Für Menschen lesbarer Galerie-Pfad eines Session-Unterordners, z. B.
     * "Pictures/SmartWeed/2026-10-18_10-30/Analyse_10-45-12" — wird in der App
     * angezeigt, damit man die Bilder im Dateimanager/der Galerie wiederfindet.
     */
    public static String galleryPath(String subPath) {
        return Environment.DIRECTORY_PICTURES + "/" + GALLERY_ROOT
                + (subPath == null || subPath.isEmpty() ? "" : "/" + subPath);
    }

    /** Galerie-Unterordner einer Analyse: &lt;Session&gt;/&lt;Analyse_HH-mm-ss&gt; */
    public static String analysisSubPath(File analysisDir) {
        File session = analysisDir.getParentFile();
        return (session != null ? session.getName() + "/" : "") + analysisDir.getName();
    }

    /**
     * @param subPath Unterordner unterhalb von Pictures/SmartWeed ("" für keinen)
     * @return true, wenn das Bild fertig (sichtbar) in der Galerie liegt
     */
    public static boolean exportToGallery(Context context, File src, String subPath) {
        return exportToGallery(context, src, subPath, null);
    }

    /**
     * @param subPath     Unterordner unterhalb von Pictures/SmartWeed ("" für keinen)
     * @param displayName Dateiname in der Galerie (null = Name der Quelldatei)
     * @return true, wenn das Bild fertig (sichtbar) in der Galerie liegt.
     *         Achtung: Schon der MediaStore-Insert legt den Ordner an — ein
     *         Abbruch danach hinterlässt einen (scheinbar) leeren Ordner, weil
     *         der Eintrag noch IS_PENDING=1 ist bzw. wieder gelöscht wurde.
     */
    public static boolean exportToGallery(Context context, File src, String subPath, String displayName) {
        if (src == null || !src.isFile() || src.length() == 0) {
            Log.w(TAG, "Galerie-Export übersprungen, Quelle fehlt/leer: " + src);
            return false;
        }
        String relative = GALLERY_ROOT + (subPath == null || subPath.isEmpty() ? "" : "/" + subPath);
        String name = (displayName == null || displayName.isEmpty()) ? src.getName() : displayName;
        String mime = mimeFor(name);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + relative);
                // IS_PENDING: Galerie-Apps sehen den Eintrag erst, wenn die
                // Datei fertig geschrieben ist (keine halbfertigen Bilder)
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Log.w(TAG, "MediaStore-Insert fehlgeschlagen: " + name);
                    return false;
                }
                boolean written = false;
                boolean published = false;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        written = true;
                    }
                } finally {
                    if (written) {
                        try {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Images.Media.IS_PENDING, 0);
                            published = context.getContentResolver().update(uri, done, null, null) > 0;
                        } catch (RuntimeException e) {
                            Log.w(TAG, "IS_PENDING=0 fehlgeschlagen: " + name, e);
                        }
                    }
                    if (!published) {
                        // Fehlschlag: Geister-Eintrag entfernen statt ihn
                        // dauerhaft (unsichtbar/leer) im MediaStore zu lassen
                        try {
                            context.getContentResolver().delete(uri, null, null);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Pending-Eintrag nicht löschbar: " + uri, e);
                        }
                    }
                }
                return published;
            } else {
                File dstDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        relative);
                if (!dstDir.exists()) dstDir.mkdirs();
                // Eindeutigen Namen wählen statt still zu überschreiben: Das
                // Übersichtsbild heißt bei jeder Analyse gleich
                // (uebersicht_vorher_nachher.jpg) — auf Android 9 hätte jede
                // neue Analyse das Galerie-Bild der vorherigen ersetzt.
                // MediaStore auf API 29+ nummeriert von sich aus durch;
                // dieser Zweig zieht damit gleich.
                File dst = uniqueFile(dstDir, name);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                MediaScannerConnection.scanFile(context,
                        new String[]{dst.getAbsolutePath()},
                        new String[]{mime}, null);
                return true;
            }
        } catch (Exception e) {
            // Galerie-Export ist "best effort" — die Analyse-Daten selbst liegen
            // sicher im App-Speicher. Der Aufrufer zählt Fehlschläge und zeigt
            // sie an (statt still leere Galerie-Ordner zu hinterlassen).
            Log.e(TAG, "Galerie-Export fehlgeschlagen: " + src.getName(), e);
            return false;
        }
    }

    /**
     * Session-Ordner in der Galerie umbenennen: Pictures/SmartWeed/&lt;alt&gt;/...
     * wird zu Pictures/SmartWeed/&lt;neu&gt;/... (inkl. Unterordner).
     * Best effort: Einträge, die nicht dieser App-Installation gehören (z. B.
     * nach Neuinstallation), darf Android 10+ nicht verschieben — sie bleiben
     * im alten Ordner liegen.
     *
     * @return Anzahl der Bilder, die nicht verschoben werden konnten
     */
    public static int renameGalleryFolder(Context context, String oldName, String newName) {
        String oldPrefix = Environment.DIRECTORY_PICTURES + "/" + GALLERY_ROOT + "/" + oldName + "/";
        String newPrefix = Environment.DIRECTORY_PICTURES + "/" + GALLERY_ROOT + "/" + newName + "/";
        int failed = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentResolver cr = context.getContentResolver();
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH};
            // LIKE grob vorfiltern ("_" ist dort ein Platzhalter), exakt prüfen
            // dann per startsWith
            try (android.database.Cursor c = cr.query(collection, proj,
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{oldPrefix + "%"}, null)) {
                if (c == null) return 0;
                int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
                while (c.moveToNext()) {
                    String rel = c.getString(pathCol);
                    if (rel == null || !rel.startsWith(oldPrefix)) continue;
                    Uri item = android.content.ContentUris.withAppendedId(collection, c.getLong(idCol));
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, newPrefix + rel.substring(oldPrefix.length()));
                    try {
                        if (cr.update(item, v, null, null) <= 0) failed++;
                    } catch (RuntimeException e) {
                        // RecoverableSecurityException: Eintrag gehört nicht uns
                        failed++;
                        Log.w(TAG, "Galerie-Eintrag nicht verschiebbar: " + item, e);
                    }
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Galerie-Umbenennung fehlgeschlagen", e);
            }
        } else {
            File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File oldDir = new File(pics, GALLERY_ROOT + "/" + oldName);
            File newDir = new File(pics, GALLERY_ROOT + "/" + newName);
            if (!oldDir.exists()) return 0;
            java.util.List<String> oldPaths = new java.util.ArrayList<>();
            collectFiles(oldDir, oldPaths);
            if (newDir.exists() || !oldDir.renameTo(newDir)) return oldPaths.size();
            java.util.List<String> paths = new java.util.ArrayList<>(oldPaths);
            collectFiles(newDir, paths);
            // Alte Pfade neu scannen entfernt die toten Einträge, neue werden aufgenommen
            MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
        }
        return failed;
    }

    // ------------------------------------------------------------------
    // v1.9: Galerie aufräumen und Tabellen (CSV) nach Documents legen
    // ------------------------------------------------------------------

    /** Wurzelordner für Tabellen: Documents/SmartWeed/… */
    public static String documentsPath(String subPath) {
        return Environment.DIRECTORY_DOCUMENTS + "/" + GALLERY_ROOT
                + (subPath == null || subPath.isEmpty() ? "" : "/" + subPath);
    }

    /**
     * Entfernt einen Session- oder Analyse-Ordner aus der Galerie
     * (Pictures/SmartWeed/&lt;subPath&gt;/… inkl. Unterordner) und die zugehörigen
     * Tabellen aus Documents/SmartWeed/&lt;subPath&gt;/….
     *
     * Bis v1.8 blieben beim Verwerfen einer Analyse bzw. Löschen einer
     * Session alle Galerie-Kopien liegen. Best effort: Einträge, die nicht
     * dieser App-Installation gehören (z. B. nach Neuinstallation), darf
     * Android 10+ nicht löschen — sie bleiben liegen und werden gezählt.
     *
     * @return Anzahl der Dateien, die nicht entfernt werden konnten
     */
    public static int deleteGalleryFolder(Context context, String subPath) {
        if (subPath == null || subPath.isEmpty()) return 0;   // nie die ganze Wurzel löschen
        int failed = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            failed += deleteByRelativePath(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    galleryPath(subPath) + "/");
            failed += deleteByRelativePath(context, MediaStore.Files.getContentUri("external"),
                    documentsPath(subPath) + "/");
        } else {
            for (String root : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DOCUMENTS}) {
                File dir = new File(Environment.getExternalStoragePublicDirectory(root),
                        GALLERY_ROOT + "/" + subPath);
                if (!dir.exists()) continue;
                java.util.List<String> paths = new java.util.ArrayList<>();
                collectFiles(dir, paths);
                failed += deleteTree(dir);
                // Gelöschte Pfade neu scannen entfernt die toten MediaStore-Einträge
                if (!paths.isEmpty()) {
                    MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
                }
            }
        }
        return failed;
    }

    /** Löscht alle eigenen MediaStore-Einträge, deren RELATIVE_PATH mit prefix beginnt. */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private static int deleteByRelativePath(Context context, Uri collection, String prefix) {
        int failed = 0;
        android.content.ContentResolver cr = context.getContentResolver();
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH};
        java.util.List<Long> ids = new java.util.ArrayList<>();
        // LIKE grob vorfiltern ("_" ist dort ein Platzhalter), exakt prüfen per startsWith
        try (android.database.Cursor c = cr.query(collection, proj,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{prefix + "%"}, null)) {
            if (c == null) return 0;
            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rel = c.getString(pathCol);
                if (rel != null && rel.startsWith(prefix)) ids.add(c.getLong(idCol));
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Galerie-Abfrage fehlgeschlagen: " + prefix, e);
            return 0;
        }
        for (long id : ids) {
            Uri item = android.content.ContentUris.withAppendedId(collection, id);
            try {
                if (cr.delete(item, null, null) <= 0) failed++;
            } catch (RuntimeException e) {
                // RecoverableSecurityException: Eintrag gehört nicht (mehr) uns
                failed++;
                Log.w(TAG, "Galerie-Eintrag nicht löschbar: " + item, e);
            }
        }
        return failed;
    }

    /** Löscht einen Ordner rekursiv; liefert die Zahl nicht löschbarer Dateien. */
    private static int deleteTree(File dir) {
        int failed = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) failed += deleteTree(f);
                else if (!f.delete()) failed++;
            }
        }
        if (!dir.delete() && dir.exists()) {
            Log.w(TAG, "Ordner nicht löschbar: " + dir);
        }
        return failed;
    }

    /**
     * Legt eine Tabelle (CSV) nach Documents/SmartWeed/&lt;subPath&gt;/ — der
     * App-Speicher (Android/data/…) ist ab Android 11 für Dateimanager nicht
     * mehr erreichbar. Ab Android 10 ohne Berechtigung über MediaStore.
     *
     * @return true, wenn die Datei fertig in Documents liegt
     */
    public static boolean exportDocument(Context context, File src, String subPath, String displayName) {
        if (src == null || !src.isFile() || src.length() == 0) return false;
        String name = (displayName == null || displayName.isEmpty()) ? src.getName() : displayName;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, documentsPath(subPath));
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Files.getContentUri("external"), values);
                if (uri == null) return false;
                boolean written = false;
                boolean published = false;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        written = true;
                    }
                } finally {
                    if (written) {
                        try {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            published = context.getContentResolver().update(uri, done, null, null) > 0;
                        } catch (RuntimeException e) {
                            Log.w(TAG, "IS_PENDING=0 fehlgeschlagen: " + name, e);
                        }
                    }
                    if (!published) {
                        try {
                            context.getContentResolver().delete(uri, null, null);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Pending-Eintrag nicht löschbar: " + uri, e);
                        }
                    }
                }
                return published;
            } else {
                File dstDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        GALLERY_ROOT + (subPath == null || subPath.isEmpty() ? "" : "/" + subPath));
                if (!dstDir.exists() && !dstDir.mkdirs()) return false;
                File dst = uniqueFile(dstDir, name);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                return true;
            }
        } catch (Exception e) {
            // Best effort: Die Tabelle liegt in jedem Fall im Analyse-Ordner der App
            Log.e(TAG, "Tabellen-Export fehlgeschlagen: " + name, e);
            return false;
        }
    }

    private static void collectFiles(File dir, java.util.List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f.getAbsolutePath());
        }
    }

    /** MIME-Typ aus der Endung — importierte Bilder können auch PNG/WebP sein;
     *  pauschal "image/jpeg" ließe die Galerie sie falsch dekodieren. */
    static String mimeFor(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    /** name.jpg -> name (1).jpg, name (2).jpg ... bis der Name frei ist */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        int n = 1;
        while (f.exists()) {
            f = new File(dir, base + " (" + (n++) + ")" + ext);
        }
        return f;
    }
}
