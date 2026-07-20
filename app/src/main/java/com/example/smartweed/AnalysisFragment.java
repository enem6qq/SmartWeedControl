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
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(), uris -> {
                beforeUris.clear();
                if (uris != null) {
                    for (Uri u : uris) {
                        takePersistable(u);
                        beforeUris.add(u);
                    }
                }
                renderSelectedImages(true);
            });

    private final ActivityResultLauncher<String[]> pickAfterImages =
            registerForActivityResult(new OpenMultipleImagesInSmartWeedDir(), uris -> {
                afterUris.clear();
                if (uris != null) {
                    for (Uri u : uris) {
                        takePersistable(u);
                        afterUris.add(u);
                    }
                }
                renderSelectedImages(false);
            });

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
        } else if (getArguments() != null && getArguments().getString("imageDir") != null) {
            // Session geöffnet: Bilder aus vorher/ und nachher/ direkt vorladen
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
            if (!outDir.exists()) outDir.mkdirs();
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
            try (Cursor c = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
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
                            weedFilter, rowMode, minSize, hueLow, hueHigh, method);
                    items.put(new JSONObject(r.toString()));
                }
                for (int i = 0; i < afterPaths.size(); i++) {
                    analysisVM.progress.postValue(new int[]{++done, total});
                    PyObject r = module.callAttr("analyze_image",
                            afterPaths.get(i), outDir.getAbsolutePath(), "after", i,
                            weedFilter, rowMode, minSize, hueLow, hueHigh, method);
                    items.put(new JSONObject(r.toString()));
                }
                analysisVM.progress.postValue(null);

                PyObject summary = module.callAttr("build_group_summary",
                        items.toString(), outDir.getAbsolutePath());
                final String json = summary.toString();
                Log.i(TAG, "analysis result: " + json);

                // Nur das fertige Übersichtsbild der Galerie melden — Masken und
                // Einzelbilder liegen versteckt im details/-Unterordner (.nomedia).
                JSONObject combo = new JSONObject(json).optJSONObject("combo");
                String overview = (combo != null) ? combo.optString("overview", "") : "";
                if (!overview.isEmpty()) {
                    // Übersichtsbild in die öffentliche Galerie exportieren
                    MediaExport.exportToGallery(appContext, new File(overview), "");
                }

                analysisVM.resultJson.postValue(json);
                analysisVM.running.postValue(false);
                analysisVM.error.postValue(null);

            } catch (PyException pyEx) {
                analysisVM.error.postValue(String.format(errPython, firstLine(pyEx.getMessage())));
                analysisVM.running.postValue(false);
                cleanupFailedOutDir(outDir);
            } catch (Exception e) {
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
        renderGroup(isBefore ? beforeUris : afterUris,
                isBefore ? binding.imageBefore : binding.imageAfter,
                isBefore ? binding.tvBeforeName : binding.tvAfterName,
                isBefore ? binding.beforeContainer : binding.afterContainer);
    }

    private void renderGroup(List<Uri> uris, android.widget.ImageView single,
                             android.widget.TextView nameView, ViewGroup container) {
        int count = uris.size();

        // Single-Preview sichtbar nur bei 1 Bild, sonst verstecken
        setVisibility(single, count == 1 ? View.VISIBLE : View.GONE);
        setVisibility(nameView, count == 1 ? View.VISIBLE : View.GONE);

        // Container sichtbar nur bei >=2; bei 0/1 leeren & verstecken
        container.removeAllViews();
        if (count >= 2) {
            setVisibility(container, View.VISIBLE);
            for (Uri u : uris) {
                addThumbToContainer(container, u);
            }
        } else {
            setVisibility(container, View.GONE);
        }

        // Single-Preview Bild/Name befüllen bei genau 1 Bild
        if (count == 1) {
            Uri u = uris.get(0);
            single.setImageURI(u);
            nameView.setText(getDisplayName(u));
        } else if (count == 0) {
            single.setImageDrawable(null);
            nameView.setText(R.string.no_image_selected);
        }
    }

    private void addThumbToContainer(ViewGroup container, Uri uri) {
        android.widget.ImageView img = new android.widget.ImageView(requireContext());
        img.setAdjustViewBounds(true);
        img.setPadding(0, dp(4), 0, dp(2));
        Glide.with(this)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .fitCenter()
                .into(img);

        // Der Fullscreen-Dialog kann content://-URIs direkt laden — keine
        // synchrone Kopie auf dem UI-Thread nötig.
        img.setOnClickListener(v ->
                SummaryFragment.FullscreenImageDialog.show(this, uri.toString()));

        container.addView(img);
    }

    private void setVisibility(View v, int visibility) {
        if (v != null) v.setVisibility(visibility);
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
