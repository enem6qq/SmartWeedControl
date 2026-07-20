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

            analysisVM.running.postValue(true);
            analysisVM.resultJson.postValue(null);
            analysisVM.error.postValue(null);
            analysisVM.weedFilter.postValue(weedFilter);

            // Ausgabe-Ordner INNERHALB der Session: kommt man von der Kamera-Seite,
            // ist die Session bekannt; sonst wird eine neue Session angelegt.
            File sessionDir;
            if (getArguments() != null && getArguments().getString("imageDir") != null) {
                sessionDir = new File(imageDirPath);
            } else {
                String sessionName = new SimpleDateFormat(SessionStore.SESSION_NAME_PATTERN, Locale.getDefault()).format(new Date());
                sessionDir = new File(SessionStore.getBaseDir(requireContext()), sessionName);
            }
            String analyseTimestamp = new SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(new Date());
            File outDir = new File(sessionDir, SessionStore.ANALYSIS_PREFIX + analyseTimestamp);
            if (!outDir.exists()) outDir.mkdirs();
            analysisVM.outDir.postValue(outDir.getAbsolutePath());

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
        try {
            final int flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) { }
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

        bgExecutor.execute(() -> {
            try {
                ArrayList<String> beforePaths = new ArrayList<>();
                ArrayList<String> afterPaths  = new ArrayList<>();
                for (int i = 0; i < beforeUris.size(); i++) {
                    beforePaths.add(copyUriToCache(appContext, beforeUris.get(i), "before_" + i + ".jpg").getAbsolutePath());
                }
                for (int i = 0; i < afterUris.size(); i++) {
                    afterPaths.add(copyUriToCache(appContext, afterUris.get(i), "after_" + i + ".jpg").getAbsolutePath());
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
            } catch (Exception e) {
                analysisVM.error.postValue(String.format(errAnalysis, firstLine(e.getMessage())));
                analysisVM.running.postValue(false);
            }
        });
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
    // UI-Rendering ohne Duplikate
    // ==============================
    private void renderSelectedImages(boolean isBefore) {
        if (isBefore) {
            int count = beforeUris.size();

            // Single-Preview sichtbar nur bei 1 Bild, sonst verstecken
            setVisibility(binding.imageBefore, count == 1 ? View.VISIBLE : View.GONE);
            setVisibility(binding.tvBeforeName, count == 1 ? View.VISIBLE : View.GONE);

            // Container sichtbar nur bei >=2; bei 0/1 leeren & verstecken
            if (count >= 2) {
                setVisibility(binding.beforeContainer, View.VISIBLE);
                binding.beforeContainer.removeAllViews();
                for (int i = 0; i < count; i++) {
                    Uri u = beforeUris.get(i);
                    addThumbToContainer(binding.beforeContainer, u, "before_preview_" + i + ".jpg");
                }
            } else {
                // 0 oder 1
                binding.beforeContainer.removeAllViews();
                setVisibility(binding.beforeContainer, View.GONE);
            }

            // Single-Preview Bild/Name befüllen bei genau 1 Bild
            if (count == 1) {
                Uri u = beforeUris.get(0);
                binding.imageBefore.setImageURI(u);
                binding.tvBeforeName.setText(getDisplayName(u));
            } else if (count == 0) {
                binding.imageBefore.setImageDrawable(null);
                binding.tvBeforeName.setText(R.string.no_image_selected);
            }

        } else {
            int count = afterUris.size();

            setVisibility(binding.imageAfter, count == 1 ? View.VISIBLE : View.GONE);
            setVisibility(binding.tvAfterName, count == 1 ? View.VISIBLE : View.GONE);

            if (count >= 2) {
                setVisibility(binding.afterContainer, View.VISIBLE);
                binding.afterContainer.removeAllViews();
                for (int i = 0; i < count; i++) {
                    Uri u = afterUris.get(i);
                    addThumbToContainer(binding.afterContainer, u, "after_preview_" + i + ".jpg");
                }
            } else {
                binding.afterContainer.removeAllViews();
                setVisibility(binding.afterContainer, View.GONE);
            }

            if (count == 1) {
                Uri u = afterUris.get(0);
                binding.imageAfter.setImageURI(u);
                binding.tvAfterName.setText(getDisplayName(u));
            } else if (count == 0) {
                binding.imageAfter.setImageDrawable(null);
                binding.tvAfterName.setText(R.string.no_image_selected);
            }
        }
    }

    private void addThumbToContainer(ViewGroup container, Uri uri, String cacheName) {
        android.widget.ImageView img = new android.widget.ImageView(requireContext());
        img.setAdjustViewBounds(true);
        img.setPadding(0, dp(4), 0, dp(2));
        Glide.with(this)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .fitCenter()
                .into(img);

        img.setOnClickListener(v -> {
            try {
                File cached = copyUriToCache(requireContext(), uri, cacheName);
                SummaryFragment.FullscreenImageDialog.show(this, cached.getAbsolutePath());
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.toast_image_open_failed, Toast.LENGTH_SHORT).show();
            }
        });

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
