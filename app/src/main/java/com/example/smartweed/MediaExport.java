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

    /**
     * @param subPath Unterordner unterhalb von Pictures/SmartWeed ("" für keinen)
     */
    public static void exportToGallery(Context context, File src, String subPath) {
        if (src == null || !src.exists()) return;
        String relative = "SmartWeed" + (subPath == null || subPath.isEmpty() ? "" : "/" + subPath);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, src.getName());
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + relative);
                // IS_PENDING: Galerie-Apps sehen den Eintrag erst, wenn die
                // Datei fertig geschrieben ist (keine halbfertigen Bilder)
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Log.w(TAG, "MediaStore-Insert fehlgeschlagen: " + src.getName());
                    return;
                }
                boolean written = false;
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
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Images.Media.IS_PENDING, 0);
                        context.getContentResolver().update(uri, done, null, null);
                    } else {
                        // Fehlschlag: Geister-Eintrag entfernen statt ihn
                        // dauerhaft (unsichtbar/leer) im MediaStore zu lassen
                        context.getContentResolver().delete(uri, null, null);
                    }
                }
            } else {
                File dstDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        relative);
                if (!dstDir.exists()) dstDir.mkdirs();
                File dst = new File(dstDir, src.getName());
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                MediaScannerConnection.scanFile(context,
                        new String[]{dst.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);
            }
        } catch (Exception e) {
            // Galerie-Export ist "best effort" — die Analyse-Daten selbst liegen
            // sicher im App-Speicher.
            Log.e(TAG, "Galerie-Export fehlgeschlagen: " + src.getName(), e);
        }
    }
}
