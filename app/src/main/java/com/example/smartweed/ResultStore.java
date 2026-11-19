package com.example.smartweed;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Dauerhafte Ablage eines Analyse-Ergebnisses (v1.9).
 *
 * Bis v1.8 lebten die Messwerte (CSC, Bedeckung je Bild, Hinweise) nur im
 * ViewModel: Nach "Fertig" oder einem Prozess-Tod gab es nur noch Bilder.
 * Jetzt liegen im Analyse-Ordner zusätzlich
 *   ergebnis.json – das vollständige Ergebnis (wird über "Meine Aufnahmen"
 *                   wieder auf der Ergebnisseite angezeigt)
 *   ergebnis.csv  – dieselben Werte als Tabelle für Excel (Kopie in
 *                   Documents/SmartWeed/…, siehe {@link MediaExport})
 */
public final class ResultStore {

    private static final String TAG = "ResultStore";

    public static final String RESULT_FILE = "ergebnis.json";
    public static final String CSV_FILE = "ergebnis.csv";

    /** Byte-Order-Mark am Dateianfang: Excel erkennt die CSV sonst nicht als
     *  UTF-8 (Umlaute). Als Zahl notiert — das Zeichen selbst darf nicht im
     *  Quelltext stehen (Lint "ByteOrderMark"). */
    private static final String UTF8_BOM = String.valueOf((char) 0xFEFF);

    private ResultStore() { }

    public static boolean hasResult(File analysisDir) {
        return analysisDir != null && new File(analysisDir, RESULT_FILE).isFile();
    }

    /** Schreibt das Ergebnis-JSON atomar (erst Temp-Datei, dann umbenennen). */
    public static boolean save(File analysisDir, String json) {
        return writeText(new File(analysisDir, RESULT_FILE), json);
    }

    /**
     * Lädt ein gespeichertes Ergebnis. Die Bildpfade im JSON sind absolut;
     * wurde die Session inzwischen umbenannt (oder die Analyse aus dem
     * Papierkorb an einen anderen Ort wiederhergestellt), werden sie auf den
     * aktuellen Ordner umgeschrieben.
     *
     * @return JSON-String oder null, wenn kein lesbares Ergebnis vorliegt
     */
    public static String load(File analysisDir) {
        File f = new File(analysisDir, RESULT_FILE);
        if (!f.isFile()) return null;
        try {
            JSONObject root = new JSONObject(readText(f));
            rebase(root, analysisDir.getAbsolutePath());
            return root.toString();
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Ergebnis nicht lesbar: " + f, e);
            return null;
        }
    }

    /** Schreibt alle Ausgabepfade von root auf den Ordner newDir um. */
    static void rebase(JSONObject root, String newDir) throws JSONException {
        String oldDir = root.optString("out_dir", "");
        if (oldDir.isEmpty() || oldDir.equals(newDir)) return;
        JSONObject combo = root.optJSONObject("combo");
        if (combo != null) {
            if (combo.has("overview")) {
                combo.put("overview", rebasePath(combo.optString("overview", ""), oldDir, newDir));
            }
            JSONArray pages = combo.optJSONArray("overview_pages");
            if (pages != null) {
                for (int i = 0; i < pages.length(); i++) {
                    pages.put(i, rebasePath(pages.optString(i, ""), oldDir, newDir));
                }
            }
        }
        JSONArray items = root.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                JSONObject outputs = (item != null) ? item.optJSONObject("outputs") : null;
                if (outputs == null) continue;
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = outputs.keys(); it.hasNext(); ) keys.add(it.next());
                for (String key : keys) {
                    outputs.put(key, rebasePath(outputs.optString(key, ""), oldDir, newDir));
                }
            }
        }
        root.put("out_dir", newDir);
    }

    /** Nur echte Unterpfade umschreiben ("/a/Analyse_1" darf "/a/Analyse_10/x" nicht treffen). */
    static String rebasePath(String path, String oldDir, String newDir) {
        if (path == null) return null;
        if (path.equals(oldDir)) return newDir;
        if (path.startsWith(oldDir + "/")) return newDir + path.substring(oldDir.length());
        return path;
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    /** Schreibt ergebnis.csv in den Analyse-Ordner; null bei Fehlschlag. */
    public static File writeCsv(File analysisDir, JSONObject result) {
        File f = new File(analysisDir, CSV_FILE);
        // BOM voran: Excel erkennt die Datei sonst nicht als UTF-8 (Umlaute)
        return writeText(f, UTF8_BOM + buildCsv(result, Locale.getDefault())) ? f : null;
    }

    /**
     * Ergebnis als Tabelle. Trennzeichen ist immer ";" (die erste Zeile
     * "sep=;" sagt das Excel in jeder Sprache); Dezimalzeichen nach der
     * Sprache der App — deutsches Excel liest "3.11" sonst als Datum.
     * Die Spaltennamen sind feste, sprachunabhängige Bezeichner.
     */
    static String buildCsv(JSONObject root, Locale locale) {
        StringBuilder sb = new StringBuilder();
        sb.append("sep=;\n");
        JSONObject meta = root.optJSONObject("meta");
        if (meta == null) meta = new JSONObject();
        row(sb, "SmartWeedControl", meta.optString("app_version", ""));
        row(sb, "Session", meta.optString("session", ""));
        row(sb, "Analyse", meta.optString("analysis", ""));
        row(sb, "Datum", meta.optString("created", ""));
        row(sb, "Modus", meta.optString("mode", ""));
        row(sb, "Segmentierung", meta.optString("method", ""));
        row(sb, "Mindestgroesse_px", meta.has("min_size") ? String.valueOf(meta.optInt("min_size")) : "");
        row(sb, "Zaehlrahmen_Auswertung", meta.has("frame_crop") ? (meta.optBoolean("frame_crop") ? "ja" : "nein") : "");
        String basis = root.optString("csc_basis", "");
        row(sb, "CSC_Basis", basis);
        row(sb, "CSC_%", num(root, "csc", locale));
        row(sb, "CSC_kontrollbezogen_%", num(root, "csc_control_corrected", locale));
        row(sb, "Aenderung_Kontrolle_%", num(root, "control_change_percent", locale));
        row(sb, "Bilder_vorher", String.valueOf(root.optInt("count_before", 0)));
        row(sb, "Bilder_nachher", String.valueOf(root.optInt("count_after", 0)));
        row(sb, "Bilder_Kontrolle_vorher", String.valueOf(root.optInt("count_control_before", 0)));
        row(sb, "Bilder_Kontrolle_nachher", String.valueOf(root.optInt("count_control_after", 0)));
        row(sb, "Bilder_nicht_im_Mittel", String.valueOf(root.optInt("excluded_count", 0)));
        row(sb, "Bilder_uebersprungen", String.valueOf(root.optInt("skipped_count", 0)));
        if (!basis.isEmpty()) {
            row(sb, "Mittel_vorher_%", num(root.optJSONObject("avg_before"), basis, locale));
            row(sb, "Mittel_nachher_%", num(root.optJSONObject("avg_after"), basis, locale));
        }
        sb.append('\n');
        row(sb, "Gruppe", "Nr", "Datei", "Bedeckung_%", "Bedeckung_gefiltert_%",
                "Bedeckung_unkrautgefiltert_%", "Kultur_%", "Unkraut_%",
                "Zaehlrahmen", "Im_Mittelwert", "Hinweis");
        JSONArray items = root.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String frame = "";
                if (it.has("frame_found")) {
                    frame = it.optBoolean("frame_found", false)
                            ? it.optString("frame_kind", "ja")
                            : (it.optBoolean("frame_ignored", false) ? "nicht_verwendet" : "nein");
                }
                StringBuilder hint = new StringBuilder();
                String cov = it.optString("coverage_warning", "");
                if (!cov.isEmpty()) hint.append("Bedeckung_").append(cov);
                if (it.optBoolean("dense_warning", false)) {
                    if (hint.length() > 0) hint.append(' ');
                    hint.append("dichter_Bestand");
                }
                row(sb, groupLabel(it.optString("tag", "")),
                        String.valueOf(it.optInt("index", i) + 1),
                        it.optString("file", ""),
                        num(it, "coverage_bw_percent", locale),
                        num(it, "coverage_filtered_percent", locale),
                        num(it, "coverage_weedfiltered_percent", locale),
                        num(it, "coverage_crop_percent", locale),
                        num(it, "coverage_weed_percent", locale),
                        frame,
                        it.has("excluded_from_mean") ? "nein" : "ja",
                        hint.toString());
            }
        }
        return sb.toString();
    }

    private static String groupLabel(String tag) {
        switch (tag) {
            case "before":         return "vorher";
            case "after":          return "nachher";
            case "control_before": return "kontrolle_vorher";
            case "control_after":  return "kontrolle_nachher";
            default:               return tag;
        }
    }

    private static String num(JSONObject obj, String key, Locale locale) {
        if (obj == null) return "";
        double v = obj.optDouble(key, Double.NaN);
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "" : String.format(locale, "%.2f", v);
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(csvCell(cells[i]));
        }
        sb.append('\n');
    }

    /** Zellen mit Trennzeichen, Anführungszeichen oder Zeilenumbruch in "…" setzen. */
    static String csvCell(String s) {
        if (s == null) return "";
        if (s.indexOf(';') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------
    // Galerie-Spiegel (nach Wiederherstellen aus dem Papierkorb)
    // ------------------------------------------------------------------

    /**
     * Legt einen wiederhergestellten Ordner (Session oder einzelne Analyse)
     * wieder in die Galerie — beim Löschen/Verwerfen wurden die Galerie-Kopien
     * entfernt. Läuft im Hintergrund-Thread des Aufrufers.
     *
     * @return Anzahl fehlgeschlagener Exporte
     */
    public static int reexportToGallery(Context context, File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        if (dir.getName().startsWith(SessionStore.ANALYSIS_PREFIX)) {
            return exportAnalysis(context, dir);
        }
        int failed = 0;
        for (String sub : new String[]{SessionStore.BEFORE_DIR_NAME, SessionStore.AFTER_DIR_NAME}) {
            failed += exportImagesOf(context, new File(dir, sub), dir.getName() + "/" + sub);
        }
        File[] analyses = dir.listFiles(f ->
                f.isDirectory() && f.getName().startsWith(SessionStore.ANALYSIS_PREFIX));
        if (analyses != null) {
            for (File a : analyses) failed += exportAnalysis(context, a);
        }
        return failed;
    }

    /** Eingangsbilder, Auswertungsbilder, Übersicht und CSV einer Analyse exportieren. */
    private static int exportAnalysis(Context context, File analysisDir) {
        String sub = MediaExport.analysisSubPath(analysisDir);
        int failed = 0;
        for (String group : AnalysisFiles.GROUP_DIRS) {
            failed += exportImagesOf(context, new File(analysisDir, group), sub + "/" + group);
        }
        File session = analysisDir.getParentFile();
        String sessionName = (session != null) ? session.getName() : analysisDir.getName();
        String json = load(analysisDir);
        if (json == null) {
            // Analyse aus v1.8 oder älter (ohne ergebnis.json): nur die Übersicht
            File overview = new File(analysisDir, "uebersicht_vorher_nachher.jpg");
            if (overview.isFile() && !MediaExport.exportToGallery(context, overview, sub,
                    AnalysisFiles.overviewName(sessionName, 1))) {
                failed++;
            }
            return failed;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    int group = AnalysisFiles.groupForTag(it.optString("tag", ""));
                    JSONObject outputs = it.optJSONObject("outputs");
                    String panel = (outputs != null) ? outputs.optString("panel", "") : "";
                    if (group < 0 || panel.isEmpty()) continue;
                    if (!MediaExport.exportToGallery(context, new File(panel), sub,
                            AnalysisFiles.panelName(group, it.optInt("index", i)))) {
                        failed++;
                    }
                }
            }
            List<String> pages = overviewPages(root);
            for (int p = 0; p < pages.size(); p++) {
                if (!MediaExport.exportToGallery(context, new File(pages.get(p)), sub,
                        AnalysisFiles.overviewName(sessionName, p + 1))) {
                    failed++;
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ergebnis für Galerie-Export nicht lesbar: " + analysisDir, e);
        }
        File csv = new File(analysisDir, CSV_FILE);
        if (csv.isFile()) {
            MediaExport.exportDocument(context, csv, sub, csvDisplayName(analysisDir));
        }
        return failed;
    }

    private static int exportImagesOf(Context context, File dir, String gallerySub) {
        File[] files = dir.listFiles(f -> f.isFile() && SessionStore.isImageFile(f.getName()));
        if (files == null) return 0;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int failed = 0;
        for (File f : files) {
            if (!MediaExport.exportToGallery(context, f, gallerySub)) failed++;
        }
        return failed;
    }

    /** Alle Seiten des Übersichtsbilds (Seite 1 zuerst); leer, wenn es keins gibt. */
    public static List<String> overviewPages(JSONObject root) {
        List<String> result = new ArrayList<>();
        JSONObject combo = (root != null) ? root.optJSONObject("combo") : null;
        if (combo == null) return result;
        JSONArray pages = combo.optJSONArray("overview_pages");
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                String p = pages.optString(i, "");
                if (!p.isEmpty()) result.add(p);
            }
        }
        if (result.isEmpty()) {
            String single = combo.optString("overview", "");
            if (!single.isEmpty()) result.add(single);
        }
        return result;
    }

    /** Sprechender Dateiname der CSV in Documents (bleibt auch geteilt zuordenbar). */
    public static String csvDisplayName(File analysisDir) {
        File session = analysisDir.getParentFile();
        return "Ergebnis_" + (session != null ? session.getName() + "_" : "")
                + analysisDir.getName() + ".csv";
    }

    // ------------------------------------------------------------------
    // Datei-Helfer
    // ------------------------------------------------------------------

    private static boolean writeText(File target, String text) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            if (tmp.renameTo(target)) return true;
            Log.w(TAG, "Umbenennen fehlgeschlagen: " + target);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Schreiben fehlgeschlagen: " + target, e);
        }
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "Temp-Datei nicht löschbar: " + tmp);
        }
        return false;
    }

    private static String readText(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
