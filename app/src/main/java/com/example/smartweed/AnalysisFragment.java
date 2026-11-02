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

    // Bildgruppen (Index in TAGS/GROUP_DIRS/GALLERY_PREFIX)
    private static final int GROUP_BEFORE = 0;
    private static final int GROUP_AFTER = 1;
    /** v1.8: optionale, unbehandelte Kontrollfläche (gleiches Licht) */
    private static final int GROUP_CONTROL_BEFORE = 2;
    private static final int GROUP_CONTROL_AFTER = 3;
    private static final int GROUP_COUNT = 4;

    /** Tags für analyze_image/build_group_summary (müssen zu analysis.py passen) */
    private static final String[] TAGS = {"before", "after", "control_before", "control_after"};
    /** Unterordner im Analyse-Ordner (App-Speicher und Galerie) */
    private static final String[] GROUP_DIRS = {
            SessionStore.ANALYSIS_BEFORE_DIR, SessionStore.ANALYSIS_AFTER_DIR,
            SessionStore.ANALYSIS_CONTROL_BEFORE_DIR, SessionStore.ANALYSIS_CONTROL_AFTER_DIR};
    /** Präfix der Auswertungsbilder in der Galerie (Vorher_01_Auswertung.jpg …) */
    private static final String[] GALLERY_PREFIX = {
            "Vorher", "Nachher", "Kontrolle_Vorher", "Kontrolle_Nachher"};
    private static final String[] STATE_KEYS = {
            "beforeUris", "afterUris", "controlBeforeUris", "controlAfterUris"};
    private static final String STATE_CONTROL_EXPANDED = "controlExpanded";

    // Mehrfach-Auswahl
    private final List<Uri> beforeUris = new ArrayList<>();
    private final List<Uri> afterUris  = new ArrayList<>();
    private final List<Uri> controlBeforeUris = new ArrayList<>();
    private final List<Uri> controlAfterUris  = new ArrayList<>();

    /** Kontrollflächen-Bereich aufgeklappt? (Feld überlebt die View-Neuerstellung
     *  beim Zurückkehren von der Ergebnisseite) */
    private boolean controlExpanded = false;

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

    // === Multi-Picker (ein Launcher je Gruppe, gleicher Code-Pfad) ===
    private final ActivityResultLauncher<String[]> pickBeforeImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, GROUP_BEFORE));

    private final ActivityResultLauncher<String[]> pickAfterImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, GROUP_AFTER));

    private final ActivityResultLauncher<String[]> pickControlBeforeImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, GROUP_CONTROL_BEFORE));

    private final ActivityResultLauncher<String[]> pickControlAfterImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(),
                    uris -> applyPickedUris(uris, GROUP_CONTROL_AFTER));

    private List<Uri> urisFor(int group) {
        switch (group) {
            case GROUP_AFTER:          return afterUris;
            case GROUP_CONTROL_BEFORE: return controlBeforeUris;
            case GROUP_CONTROL_AFTER:  return controlAfterUris;
            default:                   return beforeUris;
        }
    }

    private void applyPickedUris(List<Uri> uris, int group) {
        // Abbruch liefert eine LEERE Liste (nicht null) — die bestehende
        // Auswahl muss dann erhalten bleiben.
        if (uris == null || uris.isEmpty()) return;
        // Neue Bilder werden ANGEHÄNGT (Duplikate übersprungen): Einzelne
        // Bilder lassen sich über das ✕ auf der Kachel wieder entfernen, die
        // ganze Gruppe über "Alle entfernen".
        List<Uri> target = urisFor(group);
        for (Uri u : uris) {
            if (target.contains(u)) continue;
            takePersistable(u);
            target.add(u);
        }
        renderSelectedImages(group);
    }

    private void removeFromSelection(Uri uri, int group) {
        List<Uri> target = urisFor(group);
        if (!target.remove(uri)) return;
        releaseUnusedPersistables(java.util.Collections.singletonList(uri));
        renderSelectedImages(group);
    }

    private void clearSelection(int group) {
        List<Uri> target = urisFor(group);
        if (target.isEmpty()) return;
        List<Uri> old = new ArrayList<>(target);
        target.clear();
        releaseUnusedPersistables(old);
        renderSelectedImages(group);
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
            if (beforeUris.contains(u) || afterUris.contains(u)
                    || controlBeforeUris.contains(u) || controlAfterUris.contains(u)) continue;
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
            for (int g = 0; g < GROUP_COUNT; g++) {
                List<Uri> target = urisFor(g);
                target.clear();
                ArrayList<String> list = savedInstanceState.getStringArrayList(STATE_KEYS[g]);
                if (list != null) for (String s : list) target.add(Uri.parse(s));
            }
            controlExpanded = savedInstanceState.getBoolean(STATE_CONTROL_EXPANDED, false);
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
        for (int g = 0; g < GROUP_COUNT; g++) renderSelectedImages(g);

        // Kontrollfläche: eingeklappt, außer es sind schon Kontrollbilder gewählt
        if (!controlBeforeUris.isEmpty() || !controlAfterUris.isEmpty()) controlExpanded = true;
        applyControlExpanded();
        binding.controlHeader.setOnClickListener(v -> {
            controlExpanded = !controlExpanded;
            applyControlExpanded();
        });

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
        binding.buttonPickControlBefore.setOnClickListener(v ->
                pickControlBeforeImages.launch(new String[]{"image/*"}));
        binding.buttonPickControlAfter.setOnClickListener(v ->
                pickControlAfterImages.launch(new String[]{"image/*"}));
        binding.btnClearBefore.setOnClickListener(v -> clearSelection(GROUP_BEFORE));
        binding.btnClearAfter.setOnClickListener(v -> clearSelection(GROUP_AFTER));
        binding.btnClearControlBefore.setOnClickListener(v -> clearSelection(GROUP_CONTROL_BEFORE));
        binding.btnClearControlAfter.setOnClickListener(v -> clearSelection(GROUP_CONTROL_AFTER));

        // Analyse starten (Vorher- und Nachher-Gruppe dürfen unterschiedlich groß sein)
        binding.buttonRunAnalysis.setOnClickListener(v -> {
            if (beforeUris.isEmpty() || afterUris.isEmpty()) {
                Toast.makeText(requireContext(), R.string.toast_pick_before_after, Toast.LENGTH_LONG).show();
                return;
            }
            // Kontrollfläche nur vollständig (vorher UND nachher) oder gar nicht:
            // eine halbe Kontrolle ließe sich nicht verrechnen und würde nur
            // stillschweigend ignoriert
            if (controlBeforeUris.isEmpty() != controlAfterUris.isEmpty()) {
                controlExpanded = true;
                applyControlExpanded();
                Toast.makeText(requireContext(), R.string.toast_control_incomplete, Toast.LENGTH_LONG).show();
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
            // Jeder Lauf bekommt einen EIGENEN Ordner mit Uhrzeit UND Modus im
            // Namen (z. B. Analyse_08-50-12_B): Wer dieselben Bilder mit einer
            // anderen Variante auswertet, sieht sofort, welcher Ordner zu
            // welcher Variante gehört.
            String mode = rowMode ? "C" : (weedFilter ? "B" : "A");
            String analyseTimestamp = new SimpleDateFormat("HH-mm-ss", Locale.US).format(new Date());
            String baseName = SessionStore.ANALYSIS_PREFIX + analyseTimestamp + "_" + mode;
            File outDir = new File(sessionDir, baseName);
            // Kollisionsschutz: zweiter Lauf in derselben Sekunde -> _2, _3 ...
            int suffix = 2;
            while (outDir.exists()) {
                outDir = new File(sessionDir, baseName + "_" + suffix++);
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
        for (int g = 0; g < GROUP_COUNT; g++) {
            ArrayList<String> list = new ArrayList<>();
            for (Uri u : urisFor(g)) list.add(u.toString());
            outState.putStringArrayList(STATE_KEYS[g], list);
        }
        outState.putBoolean(STATE_CONTROL_EXPANDED, controlExpanded);
    }

    /** Kontrollflächen-Bereich auf-/zuklappen (nur Darstellung) */
    private void applyControlExpanded() {
        if (binding == null) return;
        binding.controlContent.setVisibility(controlExpanded ? View.VISIBLE : View.GONE);
        binding.tvControlToggle.setText(controlExpanded
                ? R.string.control_toggle_expanded : R.string.control_toggle_collapsed);
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
        // Dateinamen auf dem UI-Thread ermitteln (ContentResolver-Abfrage).
        // Die ausgewerteten Bilder werden IM Analyse-Ordner abgelegt
        // (Vorher/, Nachher/ und ggf. Kontrolle_Vorher/, Kontrolle_Nachher/) —
        // egal ob aus Kamera, Import oder Galerie. So ist jede Analyse
        // vollständig nachvollziehbar.
        final List<List<Uri>> snapshots = new ArrayList<>();
        final List<List<String>> names = new ArrayList<>();
        final File[] inDirs = new File[GROUP_COUNT];
        for (int g = 0; g < GROUP_COUNT; g++) {
            List<Uri> snap = new ArrayList<>(urisFor(g));
            List<String> n = new ArrayList<>();
            for (Uri u : snap) n.add(safeImageName(getDisplayName(u)));
            snapshots.add(snap);
            names.add(n);
            inDirs[g] = new File(outDir, GROUP_DIRS[g]);
        }

        bgExecutor.execute(() -> {
            try {
                // 1) Eingangsbilder in den Analyse-Ordner kopieren (lokal, schnell)
                List<List<File>> inputs = new ArrayList<>();
                int total = 0;
                for (int g = 0; g < GROUP_COUNT; g++) {
                    List<File> files = new ArrayList<>();
                    List<Uri> snap = snapshots.get(g);
                    for (int i = 0; i < snap.size(); i++) {
                        files.add(copyUriToDir(appContext, snap.get(i), inDirs[g], names.get(g).get(i)));
                    }
                    inputs.add(files);
                    total += files.size();
                }

                if (!Python.isStarted()) Python.start(new AndroidPlatform(appContext));
                Python py = Python.getInstance();
                PyObject module = py.getModule("analysis");
                try { module.callAttr("chaquopy_probe"); } catch (Exception ignored) {}

                // 2) Jedes Bild einzeln analysieren (Fortschritt "Bild i von n")
                //    und SOFORT in die Galerie legen (Original + Auswertungsbild).
                //    Bis v1.7.2 lief der Galerie-Export erst NACH der Ergebnis-
                //    meldung: Die App ging dann meist in den Hintergrund (Nutzer
                //    schaut in die Galerie / tippt "Fertig") und Android fror
                //    den Hintergrund-Thread ein bzw. beendete den Prozess. Der
                //    erste MediaStore-Insert hatte die Ordner Vorher/Nachher
                //    aber schon angelegt, die Bilder darin blieben unsichtbar
                //    (IS_PENDING) — "Ordner da, Bilder nicht".
                String gallerySub = MediaExport.analysisSubPath(outDir);
                int done = 0;
                int exportFailed = 0;
                JSONArray items = new JSONArray();
                for (int g = 0; g < GROUP_COUNT; g++) {
                    List<File> files = inputs.get(g);
                    for (int i = 0; i < files.size(); i++) {
                        analysisVM.progress.postValue(new int[]{++done, total});
                        File input = files.get(i);
                        PyObject r = module.callAttr("analyze_image",
                                input.getAbsolutePath(), outDir.getAbsolutePath(), TAGS[g], i,
                                weedFilter, rowMode, minSize, hueLow, hueHigh, method, frameCrop);
                        JSONObject item = new JSONObject(r.toString());
                        items.put(item);

                        if (!MediaExport.exportToGallery(appContext, input,
                                gallerySub + "/" + GROUP_DIRS[g])) {
                            exportFailed++;
                        }
                        JSONObject outputs = item.optJSONObject("outputs");
                        String panel = (outputs != null) ? outputs.optString("panel", "") : "";
                        if (!panel.isEmpty() && !MediaExport.exportToGallery(appContext,
                                new File(panel), gallerySub, galleryPanelName(g, i))) {
                            exportFailed++;
                        }
                    }
                }
                analysisVM.progress.postValue(null);

                PyObject summary = module.callAttr("build_group_summary",
                        items.toString(), outDir.getAbsolutePath());
                JSONObject result = new JSONObject(summary.toString());

                // 3) Übersichtsbild — die führende "00_" sortiert es im
                //    Galerie-Ordner an den Anfang
                JSONObject combo = result.optJSONObject("combo");
                String overview = (combo != null) ? combo.optString("overview", "") : "";
                if (!overview.isEmpty()) {
                    File session = outDir.getParentFile();
                    String sessionName = session != null ? session.getName() : outDir.getName();
                    if (!MediaExport.exportToGallery(appContext, new File(overview), gallerySub,
                            "00_Uebersicht_" + sessionName + ".jpg")) {
                        exportFailed++;
                    }
                }
                // Fehlgeschlagene Galerie-Exporte sichtbar machen (Ergebnisseite)
                // statt still leere Ordner zu hinterlassen
                if (exportFailed > 0) result.put("gallery_export_failed", exportFailed);

                final String json = result.toString();
                // Volles Ergebnis-JSON (inkl. aller Ausgabepfade) nur im
                // Debug-Build loggen — im Release reicht die Erfolgsmeldung
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "analysis result: " + json);
                } else {
                    Log.i(TAG, "analysis finished, " + items.length() + " items, gallery failures: " + exportFailed);
                }

                // Erst jetzt "fertig" melden: Ab hier darf der Nutzer die App
                // verlassen oder die Analyse verwerfen — alle Dateien liegen.
                analysisVM.resultJson.postValue(json);
                analysisVM.error.postValue(null);
                analysisVM.running.postValue(false);

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
            }
        });
    }

    /**
     * Galerie-Name eines Auswertungsbildes:
     *   Pictures/SmartWeed/&lt;Session&gt;/&lt;Analyse_HH-mm-ss&gt;/
     *     00_Uebersicht_&lt;Session&gt;.jpg
     *     Vorher_01_Auswertung.jpg, Nachher_01_Auswertung.jpg,
     *     Kontrolle_Vorher_01_Auswertung.jpg, ...
     * Masken und Zwischenbilder bleiben versteckt im details/-Ordner der App.
     */
    private static String galleryPanelName(int group, int index) {
        return String.format(Locale.US, "%s_%02d_Auswertung.jpg", GALLERY_PREFIX[group], index + 1);
    }

    /** Nach fehlgeschlagener Analyse keinen Analyse-Ordner ohne Ergebnis
     *  zurücklassen (er würde in der Session-Übersicht als Analyse gezählt).
     *  Maßgeblich sind die Ergebnisbilder in details/ — die kopierten
     *  Eingangsbilder in Vorher/ und Nachher/ allein zählen nicht. */
    private static void cleanupFailedOutDir(File outDir) {
        if (outDir == null || !outDir.isDirectory()) return;
        if (containsImage(new File(outDir, "details"))) return; // Teilergebnisse behalten
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

    /** Dateiname für die Ablage: keine Pfadanteile, lesbare Bild-Endung */
    private static String safeImageName(String raw) {
        String n = (raw == null) ? "" : new File(raw).getName().replace("..", "_").trim();
        if (n.isEmpty() || ".".equals(n)) n = "bild";
        if (!SessionStore.isImageFile(n)) n = n + ".jpg";
        return n;
    }

    /** Kopiert eine (content:// oder file://) URI als echte Datei in dir —
     *  OpenCV/Chaquopy brauchen einen Dateipfad. Gleiche Namen werden
     *  durchnummeriert (bild.jpg, bild_2.jpg ...). */
    private static File copyUriToDir(@NonNull Context context, @NonNull Uri uri, @NonNull File dir,
                                     @NonNull String name) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Ordner nicht anlegbar: " + dir);
        File outFile = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        int k = 2;
        while (outFile.exists()) outFile = new File(dir, base + "_" + k++ + ext);
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
    // UI-Rendering (ein Pfad für alle Bildgruppen)
    // ==============================
    private void renderSelectedImages(int group) {
        if (binding == null) return;
        List<Uri> uris = urisFor(group);
        android.widget.TextView status;
        View clear;
        View scroll;
        ViewGroup container;
        switch (group) {
            case GROUP_AFTER:
                status = binding.tvAfterName;
                clear = binding.btnClearAfter;
                scroll = binding.scrollAfter;
                container = binding.afterContainer;
                break;
            case GROUP_CONTROL_BEFORE:
                status = binding.tvControlBeforeName;
                clear = binding.btnClearControlBefore;
                scroll = binding.scrollControlBefore;
                container = binding.controlBeforeContainer;
                break;
            case GROUP_CONTROL_AFTER:
                status = binding.tvControlAfterName;
                clear = binding.btnClearControlAfter;
                scroll = binding.scrollControlAfter;
                container = binding.controlAfterContainer;
                break;
            default:
                status = binding.tvBeforeName;
                clear = binding.btnClearBefore;
                scroll = binding.scrollBefore;
                container = binding.beforeContainer;
        }

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
            container.addView(createThumbTile(u, group));
        }
    }

    /** Kachel: Vorschaubild (antippen = Vollbild) mit ✕-Knopf zum Entfernen.
     *  Glide statt setImageURI: dekodiert asynchron und auf Kachelgröße
     *  heruntergesampelt (ein 12-MP-Foto wären sonst ~48 MB auf dem UI-Thread). */
    private View createThumbTile(Uri uri, int group) {
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
        remove.setOnClickListener(v -> removeFromSelection(uri, group));
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
