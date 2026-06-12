// app/src/main/java/com/example/smartweed/fragment_analysis.java
package com.example.smartweed;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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

public class fragment_analysis extends Fragment {

    private FragmentAnalysisBinding binding;

    // Mehrfach-Auswahl
    private final List<Uri> beforeUris = new ArrayList<>();
    private final List<Uri> afterUris  = new ArrayList<>();

    private ExecutorService bgExecutor;
    private String imageDirPath; // optional via Args

    private static final String TAG = "AnalysisScreen";

    private AnalysisViewModel analysisVM;

    // Android <11: WRITE_EXTERNAL_STORAGE Runtime-Permission
    private final ActivityResultLauncher<String> writePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(requireContext(),
                            "Speicher-Berechtigung wird zum Speichern der Ergebnisse benötigt",
                            Toast.LENGTH_LONG).show();
                }
            });

    // === Multi-Picker ===
    private final ActivityResultLauncher<String[]> pickBeforeImages =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
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
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
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
            // App-spezifischer Speicher (keine Berechtigung nötig, Scoped Storage kompatibel)
            File fallback = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartWeed");
            imageDirPath = fallback.getAbsolutePath();
        }

        // State wiederherstellen
        if (savedInstanceState != null) {
            ArrayList<String> bList = savedInstanceState.getStringArrayList("beforeUris");
            ArrayList<String> aList = savedInstanceState.getStringArrayList("afterUris");
            beforeUris.clear();
            afterUris.clear();
            if (bList != null) for (String s : bList) beforeUris.add(Uri.parse(s));
            if (aList != null) for (String s : aList) afterUris.add(Uri.parse(s));
        }

        // Render initial (leer)
        renderSelectedImages(true);
        renderSelectedImages(false);

        // Analyse-Modus: Beschreibung bei Wechsel aktualisieren
        binding.rgAnalysisMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbModeB) {
                binding.tvModeDescription.setText(R.string.analysis_mode_b_desc);
            } else {
                binding.tvModeDescription.setText(R.string.analysis_mode_a_desc);
            }
        });

        // Buttons: Multi auswählen
        binding.buttonPickBefore.setOnClickListener(v ->
                pickBeforeImages.launch(new String[]{"image/*"}));
        binding.buttonPickAfter.setOnClickListener(v ->
                pickAfterImages.launch(new String[]{"image/*"}));

        // Analyse starten
        binding.buttonRunAnalysis.setOnClickListener(v -> {
            if (beforeUris.isEmpty() || afterUris.isEmpty()) {
                Toast.makeText(requireContext(), "Bitte Vorher- und Nachher-Bilder wählen", Toast.LENGTH_LONG).show();
                return;
            }
            if (beforeUris.size() != afterUris.size()) {
                Toast.makeText(requireContext(), "Anzahl Vorher/Nachher muss gleich sein.", Toast.LENGTH_LONG).show();
                return;
            }

            // Android <11: Schreibberechtigung prüfen bevor Ausgabe-Ordner erstellt wird
            if (!hasWritePermission()) {
                writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                return;
            }

            boolean weedFilter = binding.rbModeB.isChecked();

            analysisVM.running.postValue(true);
            analysisVM.resultJson.postValue(null);
            analysisVM.error.postValue(null);
            analysisVM.weedFilter.postValue(weedFilter);

            // Ausgabe-Ordner mit Timestamp (jede Analyse bekommt eigenen Ordner)
            File appStorage = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartWeed");
            String analyseTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File outDir = new File(appStorage, "ausgabe_" + analyseTimestamp);
            if (!outDir.exists()) outDir.mkdirs();
            analysisVM.outDir.postValue(outDir.getAbsolutePath());

            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_AnalysisFragment_to_SummaryFragment);

            runPythonAnalysis(outDir, weedFilter);
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

    private void takePersistable(Uri uri) {
        try {
            final int flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) { }
    }

    private String getDisplayName(Uri uri) {
        String name = "Unbenannt";
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

    /** Prüft ob Schreibzugriff auf externen Speicher vorhanden ist */
    private boolean hasWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return true; // Android 11+: MANAGE_EXTERNAL_STORAGE in MainActivity
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // ==============================
    // Python-Analyse: mehrere Paare
    // ==============================
    private void runPythonAnalysis(@NonNull File outDir, boolean weedFilter) {
        Toast.makeText(requireContext(), "Analyse gestartet …", Toast.LENGTH_SHORT).show();

        bgExecutor.execute(() -> {
            try {
                ArrayList<String> beforePaths = new ArrayList<>();
                ArrayList<String> afterPaths  = new ArrayList<>();

                for (int i = 0; i < beforeUris.size(); i++) {
                    Uri bu = beforeUris.get(i);
                    Uri au = afterUris.get(i);
                    File bf = copyUriToCache(bu, "before_" + i + ".jpg");
                    File af = copyUriToCache(au, "after_"  + i + ".jpg");
                    beforePaths.add(bf.getAbsolutePath());
                    afterPaths.add(af.getAbsolutePath());
                }

                if (!Python.isStarted()) Python.start(new AndroidPlatform(requireContext()));
                Python py = Python.getInstance();
                PyObject module = py.getModule("analysis");
                try { module.callAttr("chaquopy_probe"); } catch (Exception ignored) {}

                PyObject result;
                try {
                    result = module.callAttr("analyze_batch", beforePaths, afterPaths, outDir.getAbsolutePath(), weedFilter);
                    Log.i(TAG, "Batch-Analyse (analyze_batch) ausgeführt.");
                } catch (Exception noBatch) {
                    Log.w(TAG, "analyze_batch nicht vorhanden. Fallback auf analyze_pair.");
                    JSONArray jArr = new JSONArray();
                    for (int i = 0; i < beforePaths.size(); i++) {
                        PyObject r = module.callAttr("analyze_pair", beforePaths.get(i), afterPaths.get(i), outDir.getAbsolutePath(), weedFilter);
                        String js = (r == null) ? "" : r.toString();
                        if (js != null && js.trim().startsWith("{")) jArr.put(new JSONObject(js));
                    }
                    result = PyObject.fromJava(jArr.toString());
                }

                final String json = (result == null) ? "" : result.toString();
                Log.i(TAG, "analysis result: " + json);

                MediaScannerConnection.scanFile(requireContext(),
                        new String[]{ outDir.getAbsolutePath() }, null, null);

                analysisVM.resultJson.postValue(json);
                analysisVM.running.postValue(false);
                analysisVM.error.postValue(null);

            } catch (PyException pyEx) {
                analysisVM.error.postValue("Python-Fehler: " + firstLine(pyEx.getMessage()));
                analysisVM.running.postValue(false);
            } catch (Exception e) {
                analysisVM.error.postValue("Analyse fehlgeschlagen: " + firstLine(e.getMessage()));
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
    private File copyUriToCache(@NonNull Uri uri, @NonNull String nameHint) throws IOException {
        File outFile = new File(requireContext().getCacheDir(), nameHint);
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
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
                binding.tvBeforeName.setText("Kein Bild ausgewählt");
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
                binding.tvAfterName.setText("Kein Bild ausgewählt");
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
                File cached = copyUriToCache(uri, cacheName);
                SummaryFragment.FullscreenImageDialog.show(this, cached.getAbsolutePath());
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Konnte Bild nicht öffnen.", Toast.LENGTH_SHORT).show();
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
