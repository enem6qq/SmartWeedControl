// app/src/main/java/com/example/smartweed/AnalysisFragment.java
package com.example.smartweed;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.smartweed.databinding.FragmentAnalysisBinding;

// Chaquopy
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalysisFragment extends Fragment {

    private FragmentAnalysisBinding binding;

    // Mehrfach-Auswahl
    private final List<Uri> beforeUris = new ArrayList<>();
    private final List<Uri> afterUris  = new ArrayList<>();

    private ExecutorService bgExecutor;
    private String imageDirPath; // optional via Args

    private static final String TAG = "AnalysisScreen";
    private static final String EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents";

    private AnalysisViewModel analysisVM;

    /**
     * Dokument-Picker, der direkt im Galerie-Ordner Pictures/SmartWeed startet —
     * dorthin exportiert die App die aufgenommenen Fotos, sodass sie ganz oben
     * angezeigt werden. (Die Session-Ordner selbst liegen im app-eigenen
     * Speicher und werden beim Öffnen einer Session direkt vorgeladen.)
     */
    private class OpenMultipleImagesInSmartWeedDir extends ActivityResultContracts.OpenMultipleDocuments {
        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
            Intent intent = super.createIntent(context, input);
            Uri initialUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER,
                    "primary:" + Environment.DIRECTORY_PICTURES + "/SmartWeed");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            return intent;
        }
    }

    // === Multi-Picker ===
    private final ActivityResultLauncher<String[]> pickBeforeImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, true));

    private final ActivityResultLauncher<String[]> pickAfterImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, false));

    private void applyPickedUris(List<Uri> uris, boolean isBefore) {
        // Abbruch liefert eine LEERE Liste (nicht null) — die bestehende
        // Auswahl muss dann erhalten bleiben.
        if (uris == null || uris.isEmpty()) return;
        // Neue Bilder werden ANGEHÄNGT (Duplikate übersprungen): Einzelne
        // Bilder lassen sich über das ✕ auf der Kachel wieder entfernen, die
        // ganze Gruppe über "Alle entfernen".
        List<Uri> target = isBefore ? beforeUris : afterUris;
        for (Uri u : uris) {
            if (target.contains(u)) continue;
            takePersistable(u);
            target.add(u);
        }
        renderSelectedImages(isBefore);
    }

    private void removeFromSelection(Uri uri, boolean isBefore) {
        List<Uri> target = isBefore ? beforeUris : afterUris;
        if (!target.remove(uri)) return;
        releaseUnusedPersistables(java.util.Collections.singletonList(uri));
        renderSelectedImages(isBefore);
    }

    private void clearSelection(boolean isBefore) {
        List<Uri> target = isBefore ? beforeUris : afterUris;
        if (target.isEmpty()) return;
        List<Uri> old = new ArrayList<>(target);
        target.clear();
        releaseUnusedPersistables(old);
        renderSelectedImages(isBefore);
    }

    /** Gibt persistierte URI-Grants frei, die in keiner Auswahl mehr vorkommen —
     *  Android deckelt die Grants pro App (128 vor API 30, 512 danach); ohne
     *  Freigabe schlagen neue Grants bei intensiver Langzeitnutzung fehl.
     *  Während eine Analyse läuft, wird NICHT freigegeben: Der Hintergrund-
     *  Thread kopiert die Bilder seiner Auswahl ggf. noch — ein entzogener
     *  Grant ließe die laufende Analyse mit SecurityException abbrechen. */
    private void releaseUnusedPersistables(List<Uri> removed) {
        if (analysisVM != null && Boolean.TRUE.equals(analysisVM.running.getValue())) return;
        for (Uri u : removed) {
            if (beforeUris.contains(u) || afterUris.contains(u)) continue;
            if (!"content".equals(u.getScheme())) continue;
            try {
                requireContext().getContentResolver().releasePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(TAG, "releasePersistableUriPermission fehlgeschlagen: " + u);
            }
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAnalysisBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        analysisVM = new ViewModelProvider(requireActivity()).get(AnalysisViewModel.class);
        bgExecutor = Executors.newSingleThreadExecutor();

        imageDirPath = (getArguments() != null) ? getArguments().getString("imageDir") : null;
        if (imageDirPath == null) {
            // App-eigener Speicher (keine Berechtigung nötig, Scoped Storage konform)
            imageDirPath = SessionStore.getBaseDir(requireContext()).getAbsolutePath();
        }

        // State wiederherstellen
        if (savedInstanceState != null) {
            ArrayList<String> bList = savedInstanceState.getStringArrayList("beforeUris");
            ArrayList<String> aList = savedInstanceState.getStringArrayList("afterUris");
            beforeUris.clear();
            afterUris.clear();
            if (bList != null) for (String s : bList) beforeUris.add(Uri.parse(s));
            if (aList != null) for (String s : aList) afterUris.add(Uri.parse(s));
        } else if (getArguments() != null && getArguments().getString("imageDir") != null
                && beforeUris.isEmpty() && afterUris.isEmpty()) {
            // Session geöffnet: Bilder aus vorher/ und nachher/ direkt vorladen.
            // Nur bei LEERER Auswahl: Beim Zurückkehren von der Ergebnisseite
            // läuft onViewCreated erneut (savedInstanceState == null, Argumente
            // unverändert gesetzt) — ohne den Guard würde eine manuell
            // getroffene Bildauswahl durch die Session-Ordner-Bilder ersetzt.
            preloadSessionImages(new File(imageDirPath));
        }

        // Render initial
        renderSelectedImages(true);
        renderSelectedImages(false);

        // Analyse-Modus: Beschreibung bei Wechsel aktualisieren
        binding.rgAnalysisMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbModeB) {
                binding.tvModeDescription.setText(R.string.analysis_mode_b_desc);
            } else if (checkedId == R.id.rbModeC) {
                binding.tvModeDescription.setText(R.string.analysis_mode_c_desc);
            } else {
                binding.tvModeDescription.setText(R.string.analysis_mode_a_desc);
            }
        });

        // Buttons: Multi auswählen
        binding.buttonPickBefore.setOnClickListener(v ->
                pickBeforeImages.launch(new String[]{"image/*"}));
        binding.buttonPickAfter.setOnClickListener(v ->
                pickAfterImages.launch(new String[]{"image/*"}));
        binding.btnClearBefore.setOnClickListener(v -> clearSelection(true));
        binding.btnClearAfter.setOnClickListener(v -> clearSelection(false));

        // Analyse starten (Vorher- und Nachher-Gruppe dürfen unterschiedlich groß sein)
        binding.buttonRunAnalysis.setOnClickListener(v -> {
            if (beforeUris.isEmpty() || afterUris.isEmpty()) {
                Toast.makeText(requireContext(), R.string.toast_pick_before_after, Toast.LENGTH_LONG).show();
                return;
            }

            // Läuft bereits eine Analyse (z. B. nach Zurück-Navigation von der
            // Ergebnisseite), keine zweite parallel starten — beide würden in
            // dieselben LiveData und Cache-Dateien schreiben.
            if (Boolean.TRUE.equals(analysisVM.running.getValue())) {
                Toast.makeText(requireContext(), R.string.status_analysis_running, Toast.LENGTH_SHORT).show();
                return;
            }

            // Doppelklick-/Doppelnavigations-Guard: nur starten, wenn wir noch
            // wirklich auf der Analyse-Seite stehen (sonst würde ein schneller
            // Doppeltipp die Navigation zweimal auslösen -> Crash + Doppel-Analyse).
            androidx.navigation.NavController nav = NavHostFragment.findNavController(this);
            if (nav.getCurrentDestination() == null
                    || nav.getCurrentDestination().getId() != R.id.AnalysisFragment) {
                return;
            }

            boolean weedFilter = binding.rbModeB.isChecked();
            boolean rowMode = binding.rbModeC.isChecked();

            // Auf dem UI-Thread ist setValue korrekt (deterministisch sofort
            // sichtbar — postValue könnte Zwischenwerte verschlucken)
            analysisVM.running.setValue(true);
            analysisVM.resultJson.setValue(null);
            analysisVM.error.setValue(null);
            analysisVM.weedFilter.setValue(weedFilter);

            // Ausgabe-Ordner INNERHALB der Session: kommt man von der Kamera-Seite,
            // ist die Session bekannt; sonst wird eine neue Session angelegt.
            // (Locale.US: Dateisystem-Namen locale-unabhängig halten)
            File sessionDir;
            if (getArguments() != null && getArguments().getString("imageDir") != null) {
                sessionDir = new File(imageDirPath);
            } else {
                String sessionName = new SimpleDateFormat(SessionStore.SESSION_NAME_PATTERN, Locale.US).format(new Date());
                sessionDir = new File(SessionStore.getBaseDir(requireContext()), sessionName);
            }
            String analyseTimestamp = new SimpleDateFormat("HH-mm-ss", Locale.US).format(new Date());
            File outDir = new File(sessionDir, SessionStore.ANALYSIS_PREFIX + analyseTimestamp);
            // Kollisionsschutz: Der Ordnername trägt kein Datum — eine erneute
            // Analyse derselben Session zur exakt gleichen Uhrzeit (Sekunde)
            // würde sonst in denselben Ordner schreiben und die alten
            // Ergebnisse überschreiben (feste Dateinamen wie uebersicht_*.jpg).
            int suffix = 2;
            while (outDir.exists()) {
                outDir = new File(sessionDir,
                        SessionStore.ANALYSIS_PREFIX + analyseTimestamp + "_" + suffix++);
            }
            if (!outDir.mkdirs()) {
                Toast.makeText(requireContext(),
                        getString(R.string.error_analysis_failed, outDir.getName()),
                        Toast.LENGTH_LONG).show();
                analysisVM.running.setValue(false);
                return;
            }
            analysisVM.outDir.setValue(outDir.getAbsolutePath());

            nav.navigate(R.id.action_AnalysisFragment_to_SummaryFragment);

            runPythonAnalysis(outDir, weedFilter, rowMode);
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> b = new ArrayList<>();
        for (Uri u : beforeUris) b.add(u.toString());
        ArrayList<String> a = new ArrayList<>();
        for (Uri u : afterUris) a.add(u.toString());
        outState.putStringArrayList("beforeUris", b);
        outState.putStringArrayList("afterUris", a);
    }

    /** Lädt beim Öffnen einer Session die Bilder aus vorher/ und nachher/ als Paare vor */
    private void preloadSessionImages(File sessionDir) {
        beforeUris.clear();
        afterUris.clear();
        addImagesFromDir(new File(sessionDir, SessionStore.BEFORE_DIR_NAME), beforeUris);
        addImagesFromDir(new File(sessionDir, SessionStore.AFTER_DIR_NAME), afterUris);
    }

    private void addImagesFromDir(File dir, List<Uri> target) {
        File[] files = dir.listFiles(f -> f.isFile() && SessionStore.isImageFile(f.getName()));
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) target.add(Uri.fromFile(f));
    }

    private void takePersistable(Uri uri) {
        // Nur das LESE-Recht persistieren: Write wird nirgends benötigt, und ein
        // kombinierter READ|WRITE-Aufruf würde bei nicht gewährtem Write-Grant
        // mit einer SecurityException auch das Lese-Recht mit verlieren — die in
        // onSaveInstanceState geretteten URIs wären nach Prozess-Tod unbrauchbar.
        try {
            requireContext().getContentResolver()
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "takePersistableUriPermission fehlgeschlagen: " + uri, e);
        }
    }

    private String getDisplayName(Uri uri) {
        String name = getString(R.string.unnamed);
        if ("content".equals(uri.getScheme())) {
            // Nach Prozess-Tod oder Deinstallation der Quell-App kann der Grant
            // entzogen sein: query() wirft dann SecurityException bzw.
            // IllegalArgumentException — ungefangen stürzte die App beim
            // Anzeigen der Auswahl ab. Dann bleibt es beim Platzhalter-Namen.
            try (Cursor c = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Dateiname nicht lesbar: " + uri, e);
            }
        } else if ("file".equals(uri.getScheme())) {
            String last = uri.getLastPathSegment();
            if (last != null) name = last;
        }
        return name;
    }

    // ==================================================================
    // Python-Analyse: Gruppen-Auswertung (N Vorher- vs. M Nachher-Bilder)
    // ==================================================================
    private void runPythonAnalysis(@NonNull File outDir, boolean weedFilter, boolean rowMode) {
        Toast.makeText(requireContext(), R.string.toast_analysis_started, Toast.LENGTH_SHORT).show();

        // Application-Context und Einstellungen vorab holen: das Fragment kann während
        // der Analyse bereits weggeräumt sein, requireContext() würde dann crashen.
        final Context appContext = requireContext().getApplicationContext();
        final String errPython = getString(R.string.error_python, "%s");
        final String errAnalysis = getString(R.string.error_analysis_failed, "%s");
        final AnalysisSettings settings = new AnalysisSettings(appContext);
        final int minSize = settings.getMinSize();
        final int hueLow  = settings.getHueLow();
        final int hueHigh = settings.getHueHigh();
        final String method = settings.getMethod();
        final boolean frameCrop = settings.getFrameCrop();

        // Unveränderliche KOPIEN der Auswahllisten: Der Nutzer kann während der
        // Analyse zurück navigieren und neue Bilder wählen (clear() auf dem
        // UI-Thread) — der Hintergrund-Thread darf davon nichts mitbekommen.
        final List<Uri> beforeSnapshot = new ArrayList<>(beforeUris);
        final List<Uri> afterSnapshot  = new ArrayList<>(afterUris);
        // Lauf-eindeutiges Präfix für die Cache-Dateien, damit sich zwei
        // Analysen nie gegenseitig die Zwischen-Kopien überschreiben können.
        final String runPrefix = "run" + System.currentTimeMillis() + "_";

        bgExecutor.execute(() -> {
            try {
                ArrayList<String> beforePaths = new ArrayList<>();
                ArrayList<String> afterPaths  = new ArrayList<>();
                for (int i = 0; i < beforeSnapshot.size(); i++) {
                    beforePaths.add(copyUriToCache(appContext, beforeSnapshot.get(i), runPrefix + "before_" + i + ".jpg").getAbsolutePath());
                }
                for (int i = 0; i < afterSnapshot.size(); i++) {
                    afterPaths.add(copyUriToCache(appContext, afterSnapshot.get(i), runPrefix + "after_" + i + ".jpg").getAbsolutePath());
                }

                if (!Python.isStarted()) Python.start(new AndroidPlatform(appContext));
                Python py = Python.getInstance();
                PyObject module = py.getModule("analysis");
                try { module.callAttr("chaquopy_probe"); } catch (Exception ignored) {}

                // Jedes Bild einzeln analysieren (Fortschritt "Bild i von n"),
                // anschließend fasst build_group_summary alles zusammen.
                int total = beforePaths.size() + afterPaths.size();
                int done = 0;
                JSONArray items = new JSONArray();
                for (int i = 0; i < beforePaths.size(); i++) {
                    analysisVM.progress.postValue(new int[]{++done, total});
                    PyObject r = module.callAttr("analyze_image",
                            beforePaths.get(i), outDir.getAbsolutePath(), "before", i,
                            weedFilter, rowMode, minSize, hueLow, hueHigh, method, frameCrop);
                    items.put(new JSONObject(r.toString()));
                }
                for (int i = 0; i < afterPaths.size(); i++) {
                    analysisVM.progress.postValue(new int[]{++done, total});
                    PyObject r = module.callAttr("analyze_image",
                            afterPaths.get(i), outDir.getAbsolutePath(), "after", i,
                            weedFilter, rowMode, minSize, hueLow, hueHigh, method, frameCrop);
                    items.put(new JSONObject(r.toString()));
                }
                analysisVM.progress.postValue(null);

                PyObject summary = module.callAttr("build_group_summary",
                        items.toString(), outDir.getAbsolutePath());
                final String json = summary.toString();
                // Volles Ergebnis-JSON (inkl. aller Ausgabepfade) nur im
                // Debug-Build loggen — im Release reicht die Erfolgsmeldung
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "analysis result: " + json);
                } else {
                    Log.i(TAG, "analysis finished, " + items.length() + " items");
                }

                analysisVM.resultJson.postValue(json);
                analysisVM.running.postValue(false);
                analysisVM.error.postValue(null);

                // Galerie-Export NACH dem Melden des Ergebnisses: Die
                // Ergebnisseite wartet so nicht auf die MediaStore-Kopien.
                exportResultsToGallery(appContext, outDir, new JSONObject(json));

            } catch (PyException pyEx) {
                analysisVM.error.postValue(String.format(errPython, firstLine(pyEx.getMessage())));
                analysisVM.running.postValue(false);
                cleanupFailedOutDir(outDir);
            } catch (Throwable e) {
                // Bewusst Throwable statt Exception: Ein OutOfMemoryError (bei
                // vielen 12-MP-Bildern realistisch) ließe sonst running dauerhaft
                // auf true stehen — der Doppelstart-Guard blockierte dann jede
                // weitere Analyse bis zum App-Neustart und die Ergebnisseite
                // zeigte endlos "Analyse läuft".
                Log.e(TAG, "Analyse fehlgeschlagen", e);
                analysisVM.error.postValue(String.format(errAnalysis, firstLine(e.getMessage())));
                analysisVM.running.postValue(false);
                cleanupFailedOutDir(outDir);
            } finally {
                // Zwischen-Kopien dieses Laufs aus dem Cache entfernen
                File[] cacheFiles = appContext.getCacheDir()
                        .listFiles((d, n) -> n.startsWith(runPrefix));
                if (cacheFiles != null) {
                    for (File f : cacheFiles) f.delete();
                }
            }
        });
    }

    /**
     * Ergebnisbilder geordnet in die Galerie legen:
     *   Pictures/SmartWeed/&lt;Session&gt;/&lt;Analyse_HH-mm-ss&gt;/
     *     00_Uebersicht_&lt;Session&gt;.jpg
     *     Vorher_01_Auswertung.jpg, Nachher_01_Auswertung.jpg, ...
     * Masken und Zwischenbilder bleiben versteckt im details/-Ordner der App.
     * Die führende "00_" sortiert die Übersicht im Ordner an den Anfang.
     */
    private static void exportResultsToGallery(Context ctx, File outDir, JSONObject result) {
        String sub = MediaExport.analysisSubPath(outDir);
        File session = outDir.getParentFile();
        String sessionName = session != null ? session.getName() : outDir.getName();

        JSONObject combo = result.optJSONObject("combo");
        String overview = (combo != null) ? combo.optString("overview", "") : "";
        if (!overview.isEmpty()) {
            MediaExport.exportToGallery(ctx, new File(overview), sub,
                    "00_Uebersicht_" + sessionName + ".jpg");
        }
        JSONArray items = result.optJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject outputs = it.optJSONObject("outputs");
            String panel = (outputs != null) ? outputs.optString("panel", "") : "";
            if (panel.isEmpty()) continue;
            String group = "after".equals(it.optString("tag")) ? "Nachher" : "Vorher";
            String name = String.format(Locale.US, "%s_%02d_Auswertung.jpg",
                    group, it.optInt("index", i) + 1);
            MediaExport.exportToGallery(ctx, new File(panel), sub, name);
        }
    }

    /** Nach fehlgeschlagener Analyse keinen leeren Analyse-Ordner zurücklassen
     *  (er würde in der Session-Übersicht als Analyse gezählt). */
    private static void cleanupFailedOutDir(File outDir) {
        if (outDir == null || !outDir.isDirectory()) return;
        if (containsImage(outDir)) return; // Teilergebnisse behalten
        deleteRecursively(outDir);
    }

    private static boolean containsImage(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsImage(f)) return true;
            } else if (SessionStore.isImageFile(f.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursively(f); else f.delete();
            }
        }
        dir.delete();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0) ? s.substring(0, i) : s;
    }

    /** Kopiert eine content:// URI in eine echte Datei im App-Cache, sodass OpenCV/Chaquopy sie lesen kann */
    private static File copyUriToCache(@NonNull Context context, @NonNull Uri uri, @NonNull String nameHint) throws IOException {
        File outFile = new File(context.getCacheDir(), nameHint);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) {
                // openInputStream darf laut Vertrag null liefern (Provider weg,
                // Grant abgelaufen) — ohne diese Prüfung gäbe es nur eine
                // nichtssagende NullPointerException in der Fehlermeldung
                throw new IOException("Bild nicht mehr lesbar: " + uri);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
        return outFile;
    }

    // ==============================
    // UI-Rendering (ein Pfad für Vorher und Nachher)
    // ==============================
    private void renderSelectedImages(boolean isBefore) {
        if (binding == null) return;
        List<Uri> uris = isBefore ? beforeUris : afterUris;
        android.widget.TextView status = isBefore ? binding.tvBeforeName : binding.tvAfterName;
        View clear = isBefore ? binding.btnClearBefore : binding.btnClearAfter;
        View scroll = isBefore ? binding.scrollBefore : binding.scrollAfter;
        ViewGroup container = isBefore ? binding.beforeContainer : binding.afterContainer;

        int count = uris.size();
        if (count == 0) {
            status.setText(R.string.no_image_selected);
        } else if (count == 1) {
            status.setText(getDisplayName(uris.get(0)));
        } else {
            status.setText(getResources().getQuantityString(R.plurals.images_selected, count, count));
        }
        clear.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        scroll.setVisibility(count > 0 ? View.VISIBLE : View.GONE);

        // Laufende Glide-Ladungen der alten Kacheln abbrechen, dann neu aufbauen
        for (int i = 0; i < container.getChildCount(); i++) {
            View tile = container.getChildAt(i);
            View img = tile.findViewWithTag("thumb");
            if (img instanceof android.widget.ImageView) Glide.with(this).clear(img);
        }
        container.removeAllViews();
        for (Uri u : uris) {
            container.addView(createThumbTile(u, isBefore));
        }
    }

    /** Kachel: Vorschaubild (antippen = Vollbild) mit ✕-Knopf zum Entfernen.
     *  Glide statt setImageURI: dekodiert asynchron und auf Kachelgröße
     *  heruntergesampelt (ein 12-MP-Foto wären sonst ~48 MB auf dem UI-Thread). */
    private View createThumbTile(Uri uri, boolean isBefore) {
        int size = dp(104);
        android.widget.FrameLayout tile = new android.widget.FrameLayout(requireContext());
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(8));
        tile.setLayoutParams(lp);
        tile.setBackgroundResource(R.drawable.bg_image_placeholder);
        tile.setClipToOutline(true);

        android.widget.ImageView img = new android.widget.ImageView(requireContext());
        img.setTag("thumb");
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        img.setContentDescription(getDisplayName(uri));
        tile.addView(img, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Glide.with(this)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .override(size, size)
                .centerCrop()
                .into(img);
        // Der Fullscreen-Dialog kann content://-URIs direkt laden — keine
        // synchrone Kopie auf dem UI-Thread nötig.
        img.setOnClickListener(v ->
                SummaryFragment.FullscreenImageDialog.show(this, uri.toString()));

        android.widget.ImageButton remove = new android.widget.ImageButton(requireContext());
        remove.setImageResource(R.drawable.ic_close);
        remove.setBackgroundResource(R.drawable.bg_thumb_remove);
        remove.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        remove.setPadding(dp(5), dp(5), dp(5), dp(5));
        remove.setContentDescription(getString(R.string.cd_remove_image));
        android.widget.FrameLayout.LayoutParams rlp =
                new android.widget.FrameLayout.LayoutParams(dp(30), dp(30),
                        android.view.Gravity.TOP | android.view.Gravity.END);
        rlp.setMargins(0, dp(4), dp(4), 0);
        tile.addView(remove, rlp);
        remove.setOnClickListener(v -> removeFromSelection(uri, isBefore));
        return tile;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bgExecutor != null) bgExecutor.shutdown();
        binding = null;
    }
}
