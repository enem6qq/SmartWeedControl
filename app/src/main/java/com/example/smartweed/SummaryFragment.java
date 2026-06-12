// app/src/main/java/com/example/smartweed/SummaryFragment.java
package com.example.smartweed;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.example.smartweed.databinding.FragmentSummaryBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public class SummaryFragment extends Fragment {

    private FragmentSummaryBinding binding;
    private AnalysisViewModel analysisVM;

    private String lastOriginalPath = null;
    private String lastStackedPath  = null;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSummaryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        analysisVM = new ViewModelProvider(requireActivity()).get(AnalysisViewModel.class);

        binding.progress.setVisibility(View.GONE);
        binding.tvStatus.setText("");
        binding.tvRecommendation.setText("");

        // Top-Images: Fullscreen
        binding.ivOriginalPanel.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastOriginalPath)) {
                FullscreenImageDialog.show(SummaryFragment.this, lastOriginalPath);
            }
        });
        binding.ivStackedPanels.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastStackedPath)) {
                FullscreenImageDialog.show(SummaryFragment.this, lastStackedPath);
            }
        });

        // Weed-Filter-Zeilen ein-/ausblenden
        analysisVM.weedFilter.observe(getViewLifecycleOwner(), wf -> {
            int vis = (wf != null && wf) ? View.VISIBLE : View.GONE;
            if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(vis);
            if (binding.rowAfterWeedFiltered  != null) binding.rowAfterWeedFiltered.setVisibility(vis);
            if (binding.rowDeltaWeedFiltered  != null) binding.rowDeltaWeedFiltered.setVisibility(vis);
        });

        analysisVM.outDir.observe(getViewLifecycleOwner(), path -> {
            if (!TextUtils.isEmpty(path)) binding.tvOutDir.setText(path);
        });

        analysisVM.running.observe(getViewLifecycleOwner(), running -> {
            if (running != null && running) {
                binding.progress.setVisibility(View.VISIBLE);
                binding.tvStatus.setText("Analyse läuft …");
            } else {
                binding.progress.setVisibility(View.GONE);
                if (TextUtils.isEmpty(binding.tvStatus.getText())) {
                    binding.tvStatus.setText("Analyse abgeschlossen.");
                }
            }
        });

        analysisVM.error.observe(getViewLifecycleOwner(), err -> {
            if (!TextUtils.isEmpty(err)) {
                binding.tvStatus.setText(err);
                binding.tvRecommendation.setText("");
                Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
                Glide.with(binding.ivStackedPanels).clear(binding.ivStackedPanels);
                safeResetFields();
                lastOriginalPath = null;
                lastStackedPath = null;

                if (binding.pairsContainer != null) binding.pairsContainer.removeAllViews();
                if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);
            }
        });

        // Ergebnis beobachten
        analysisVM.resultJson.observe(getViewLifecycleOwner(), json -> {
            if (TextUtils.isEmpty(json)) return;
            try {
                String trimmed = json.trim();
                if (trimmed.startsWith("[")) {
                    handleMultiResult(new JSONArray(trimmed));
                } else {
                    handleSingleResult(new JSONObject(trimmed));
                }
            } catch (Exception e) {
                binding.tvStatus.setText("Fehler beim Lesen der Ergebnisdaten.");
                binding.tvRecommendation.setText("");
                Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
                Glide.with(binding.ivStackedPanels).clear(binding.ivStackedPanels);
                lastOriginalPath = null;
                lastStackedPath = null;
                if (binding.pairsContainer != null) binding.pairsContainer.removeAllViews();
                if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);
            }
        });
    }

    // ===================================================
    // Mehrere Ergebnisse -> Durchschnitt + UI-Liste + Bilder pro Paar
    // ===================================================
    private void handleMultiResult(@NonNull JSONArray arr) {
        if (arr.length() == 0) return;

        double sumBeforeBw = 0, sumBeforeFlt = 0, sumAfterBw = 0, sumAfterFlt = 0;
        double sumBeforeWf = 0, sumAfterWf = 0;
        double sumDeltaBw = 0, sumDeltaFlt = 0, sumDeltaWf = 0;
        int n = 0;
        int nWf = 0;
        boolean hasWeedFilter = false;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject pair = arr.optJSONObject(i);
            if (pair == null) continue;

            if (pair.optBoolean("weed_filter", false)) hasWeedFilter = true;

            JSONArray items = pair.optJSONArray("items");
            JSONObject beforeObj = (items != null && items.length() > 0) ? items.optJSONObject(0) : null;
            JSONObject afterObj  = (items != null && items.length() > 1) ? items.optJSONObject(1) : null;
            JSONObject deltaObj  = pair.optJSONObject("delta");

            Double beforeBw  = asDouble(beforeObj, "coverage_bw_percent");
            Double beforeFlt = asDouble(beforeObj, "coverage_filtered_percent");
            Double afterBw   = asDouble(afterObj,  "coverage_bw_percent");
            Double afterFlt  = asDouble(afterObj,  "coverage_filtered_percent");

            Double beforeWf  = asDouble(beforeObj, "coverage_weedfiltered_percent");
            Double afterWf   = asDouble(afterObj,  "coverage_weedfiltered_percent");

            double dBw  = (deltaObj != null) ? deltaObj.optDouble("coverage_bw_percent_points", Double.NaN) : Double.NaN;
            double dFlt = (deltaObj != null) ? deltaObj.optDouble("coverage_filtered_percent_points", Double.NaN) : Double.NaN;
            double dWf  = (deltaObj != null) ? deltaObj.optDouble("coverage_weedfiltered_percent_points", Double.NaN) : Double.NaN;

            if (beforeBw  != null) sumBeforeBw  += beforeBw;
            if (beforeFlt != null) sumBeforeFlt += beforeFlt;
            if (afterBw   != null) sumAfterBw   += afterBw;
            if (afterFlt  != null) sumAfterFlt  += afterFlt;
            if (!Double.isNaN(dBw))  sumDeltaBw  += dBw;
            if (!Double.isNaN(dFlt)) sumDeltaFlt += dFlt;

            if (beforeWf != null) sumBeforeWf += beforeWf;
            if (afterWf  != null) sumAfterWf  += afterWf;
            if (!Double.isNaN(dWf)) sumDeltaWf += dWf;
            if (beforeWf != null || afterWf != null) nWf++;

            n++;
        }

        if (n == 0) return;

        double avgBeforeBw   = sumBeforeBw   / n;
        double avgBeforeFlt  = sumBeforeFlt  / n;
        double avgAfterBw    = sumAfterBw    / n;
        double avgAfterFlt   = sumAfterFlt   / n;
        double avgDeltaBw    = sumDeltaBw    / n;
        double avgDeltaFlt   = sumDeltaFlt   / n;

        safeSet(binding.tvBeforeBw,       toPct(avgBeforeBw));
        safeSet(binding.tvBeforeFiltered, toPct(avgBeforeFlt));
        safeSet(binding.tvAfterBw,        toPct(avgAfterBw));
        safeSet(binding.tvAfterFiltered,  toPct(avgAfterFlt));
        safeSet(binding.tvDeltaBw,        String.format("%.2f pp", avgDeltaBw));
        safeSet(binding.tvDeltaFiltered,  String.format("%.2f pp", avgDeltaFlt));

        // Unkrautfilter-Werte anzeigen
        if (hasWeedFilter && nWf > 0) {
            double avgBeforeWf = sumBeforeWf / nWf;
            double avgAfterWf  = sumAfterWf  / nWf;
            double avgDeltaWf  = sumDeltaWf  / nWf;
            safeSet(binding.tvBeforeWeedFiltered, toPct(avgBeforeWf));
            safeSet(binding.tvAfterWeedFiltered,  toPct(avgAfterWf));
            safeSet(binding.tvDeltaWeedFiltered,  String.format("%.2f pp", avgDeltaWf));
            if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(View.VISIBLE);
            if (binding.rowAfterWeedFiltered  != null) binding.rowAfterWeedFiltered.setVisibility(View.VISIBLE);
            if (binding.rowDeltaWeedFiltered  != null) binding.rowDeltaWeedFiltered.setVisibility(View.VISIBLE);
        }

        // Modus A (ohne Unkrautfilter) → BW-Werte, Modus B (mit Unkrautfilter) → Gefiltert-Werte
        double cscBefore = hasWeedFilter ? avgBeforeFlt : avgBeforeBw;
        double cscAfter  = hasWeedFilter ? avgAfterFlt  : avgAfterBw;
        Double cscValue = (cscBefore > 0) ? (cscBefore - cscAfter) / cscBefore * 100.0 : null;
        if (cscValue != null) {
            binding.tvCsc.setText(String.format("%.2f %%", cscValue));
            binding.tvRecommendation.setText(cscValue >= 10.0 ? "Weniger aggressiv striegeln!" : "Aggressiver striegeln!");
        } else {
            binding.tvCsc.setText("n. a.");
            binding.tvRecommendation.setText("");
        }

        // --- Einzelwerte + Bilder unten ohne RecyclerView ---
        if (binding.pairsContainer != null) {
            binding.pairsContainer.removeAllViews();
            if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.VISIBLE);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject pair = arr.optJSONObject(i);
                if (pair == null) continue;

                JSONArray items = pair.optJSONArray("items");
                JSONObject beforeObj = (items != null && items.length() > 0) ? items.optJSONObject(0) : null;
                JSONObject afterObj  = (items != null && items.length() > 1) ? items.optJSONObject(1) : null;
                JSONObject deltaObj  = pair.optJSONObject("delta");
                JSONObject combo     = pair.optJSONObject("combo");

                double beforeBw  = (beforeObj != null) ? beforeObj.optDouble("coverage_bw_percent", Double.NaN) : Double.NaN;
                double beforeFlt = (beforeObj != null) ? beforeObj.optDouble("coverage_filtered_percent", Double.NaN) : Double.NaN;
                double afterBw   = (afterObj  != null) ? afterObj .optDouble("coverage_bw_percent", Double.NaN) : Double.NaN;
                double afterFlt  = (afterObj  != null) ? afterObj .optDouble("coverage_filtered_percent", Double.NaN) : Double.NaN;

                double dBw  = (deltaObj != null) ? deltaObj.optDouble("coverage_bw_percent_points", Double.NaN)
                        : ((!Double.isNaN(afterBw) && !Double.isNaN(beforeBw)) ? afterBw - beforeBw : Double.NaN);
                double dFlt = (deltaObj != null) ? deltaObj.optDouble("coverage_filtered_percent_points", Double.NaN)
                        : ((!Double.isNaN(afterFlt) && !Double.isNaN(beforeFlt)) ? afterFlt - beforeFlt : Double.NaN);

                // Unkrautfilter-Werte pro Paar
                double beforeWfP = (beforeObj != null) ? beforeObj.optDouble("coverage_weedfiltered_percent", Double.NaN) : Double.NaN;
                double afterWfP  = (afterObj  != null) ? afterObj .optDouble("coverage_weedfiltered_percent", Double.NaN) : Double.NaN;
                double dWfP = (deltaObj != null) ? deltaObj.optDouble("coverage_weedfiltered_percent_points", Double.NaN) : Double.NaN;

                // CSC pro Paar: Modus A → BW-Werte, Modus B → Gefiltert-Werte
                double cscPairBefore = hasWeedFilter ? beforeFlt : beforeBw;
                double cscPairAfter  = hasWeedFilter ? afterFlt  : afterBw;
                Double cscPair = (!Double.isNaN(cscPairBefore) && cscPairBefore > 0 && !Double.isNaN(cscPairAfter))
                        ? ((cscPairBefore - cscPairAfter) / cscPairBefore * 100.0)
                        : null;
                String recoPair = (cscPair == null) ? "n. a."
                        : (cscPair >= 10.0 ? "Weniger aggressiv striegeln!" : "Aggressiver striegeln!");

                // --- UI-Block ---
                android.widget.LinearLayout block = new android.widget.LinearLayout(requireContext());
                block.setOrientation(android.widget.LinearLayout.VERTICAL);
                block.setPadding(0, dp(10), 0, dp(10));

                android.widget.TextView header = new android.widget.TextView(requireContext());
                header.setText(String.format("Paar %d", i + 1));
                header.setTextSize(15);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setTextColor(requireContext().getColor(R.color.text_white));

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Vorher – SW: %s | Gef.: %s", toPct(beforeBw), toPct(beforeFlt)));
                if (!Double.isNaN(beforeWfP)) sb.append(String.format(" | Unkr.: %s", toPct(beforeWfP)));
                sb.append(String.format("\nNachher – SW: %s | Gef.: %s", toPct(afterBw), toPct(afterFlt)));
                if (!Double.isNaN(afterWfP)) sb.append(String.format(" | Unkr.: %s", toPct(afterWfP)));
                sb.append(String.format("\nΔ SW: %s | Δ Gef.: %s",
                        Double.isNaN(dBw)  ? "-" : String.format("%.2f pp", dBw),
                        Double.isNaN(dFlt) ? "-" : String.format("%.2f pp", dFlt)));
                if (!Double.isNaN(dWfP)) sb.append(String.format(" | Δ Unkr.: %.2f pp", dWfP));
                sb.append(String.format("\nCSC: %s   Empfehlung: %s",
                        (cscPair == null) ? "n. a." : String.format("%.2f %%", cscPair),
                        recoPair));

                android.widget.TextView lines = new android.widget.TextView(requireContext());
                lines.setText(sb.toString());
                lines.setTextColor(requireContext().getColor(R.color.text_white));
                lines.setPadding(0, dp(4), 0, dp(0));

                block.addView(header);
                block.addView(lines);

                // Bilder (Original + Stacked)
                if (combo != null) {
                    String origPanel = normalizePath(combo.optString("original_panel", null));
                    String stacked   = normalizePath(combo.optString("labeled_tripanel_stacked", null));

                    if (!TextUtils.isEmpty(origPanel)) {
                        android.widget.ImageView imgOrig = new android.widget.ImageView(requireContext());
                        imgOrig.setAdjustViewBounds(true);
                        imgOrig.setPadding(0, dp(2), 0, dp(2));
                        loadInto(imgOrig, origPanel);

                        final String clickPath = origPanel;
                        imgOrig.setOnClickListener(v -> FullscreenImageDialog.show(SummaryFragment.this, clickPath));
                        block.addView(imgOrig);
                    }

                    if (!TextUtils.isEmpty(stacked)) {
                        android.widget.ImageView imgStacked = new android.widget.ImageView(requireContext());
                        imgStacked.setAdjustViewBounds(true);
                        imgStacked.setPadding(0, dp(2), 0, dp(2));
                        loadInto(imgStacked, stacked);

                        final String clickPath2 = stacked;
                        imgStacked.setOnClickListener(v -> FullscreenImageDialog.show(SummaryFragment.this, clickPath2));
                        block.addView(imgStacked);
                    }
                }

                binding.pairsContainer.addView(block);

                // Trennlinie (optional)
                View divider = new View(requireContext());
                divider.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                divider.setBackgroundColor(0x1F000000);
                binding.pairsContainer.addView(divider);
            }

        }

        String modeLabel = hasWeedFilter ? " | Modus B: Mit Unkrautfilter" : " | Modus A: Ohne Unkrautfilter";
        binding.tvStatus.setText("Analyse (" + n + " Paare) abgeschlossen." + modeLabel);
    }

    // ===================================================
    // Einzel-Ergebnis (alter Modus)
    // ===================================================
    private void handleSingleResult(@NonNull JSONObject root) {
        if (binding.pairsContainer != null) binding.pairsContainer.removeAllViews();
        if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);

        boolean hasWeedFilter = root.optBoolean("weed_filter", false);

        Double beforeBw = null, beforeFlt = null, afterBw = null, afterFlt = null;
        Double beforeWf = null, afterWf = null;
        if (root.has("items")) {
            JSONArray items = root.optJSONArray("items");
            if (items != null && items.length() >= 1) {
                JSONObject beforeObj = items.optJSONObject(0);
                beforeBw  = asDouble(beforeObj, "coverage_bw_percent");
                beforeFlt = asDouble(beforeObj, "coverage_filtered_percent");
                beforeWf  = asDouble(beforeObj, "coverage_weedfiltered_percent");
            }
            if (items != null && items.length() >= 2) {
                JSONObject afterObj = items.optJSONObject(1);
                afterBw  = asDouble(afterObj, "coverage_bw_percent");
                afterFlt = asDouble(afterObj, "coverage_filtered_percent");
                afterWf  = asDouble(afterObj, "coverage_weedfiltered_percent");
            }
        }

        safeSet(binding.tvBeforeBw,  toPct(beforeBw));
        safeSet(binding.tvBeforeFiltered, toPct(beforeFlt));
        safeSet(binding.tvAfterBw,   toPct(afterBw));
        safeSet(binding.tvAfterFiltered, toPct(afterFlt));

        // Unkrautfilter-Werte
        if (hasWeedFilter) {
            safeSet(binding.tvBeforeWeedFiltered, toPct(beforeWf));
            safeSet(binding.tvAfterWeedFiltered,  toPct(afterWf));
            if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(View.VISIBLE);
            if (binding.rowAfterWeedFiltered  != null) binding.rowAfterWeedFiltered.setVisibility(View.VISIBLE);
        }

        JSONObject delta = root.optJSONObject("delta");
        double dBw  = (delta != null) ? delta.optDouble("coverage_bw_percent_points", Double.NaN) : Double.NaN;
        double dFlt = (delta != null) ? delta.optDouble("coverage_filtered_percent_points", Double.NaN) : Double.NaN;
        double dWf  = (delta != null) ? delta.optDouble("coverage_weedfiltered_percent_points", Double.NaN) : Double.NaN;
        safeSet(binding.tvDeltaBw,  Double.isNaN(dBw)  ? "-" : String.format("%.2f pp", dBw));
        safeSet(binding.tvDeltaFiltered, Double.isNaN(dFlt) ? "-" : String.format("%.2f pp", dFlt));

        if (hasWeedFilter) {
            safeSet(binding.tvDeltaWeedFiltered, Double.isNaN(dWf) ? "-" : String.format("%.2f pp", dWf));
            if (binding.rowDeltaWeedFiltered != null) binding.rowDeltaWeedFiltered.setVisibility(View.VISIBLE);
        }

        // Modus A (ohne Unkrautfilter) → BW-Werte, Modus B (mit Unkrautfilter) → Gefiltert-Werte
        Double cscBefore2 = hasWeedFilter ? beforeFlt : beforeBw;
        Double cscAfter2  = hasWeedFilter ? afterFlt  : afterBw;
        Double cscValue = null;
        if (cscBefore2 != null && cscAfter2 != null && cscBefore2 > 0) {
            cscValue = (cscBefore2 - cscAfter2) / cscBefore2 * 100.0;
        }
        if (cscValue != null) {
            binding.tvCsc.setText(String.format("%.2f %%", cscValue));
            binding.tvRecommendation.setText(cscValue >= 10.0
                    ? "Weniger aggressiv striegeln!"
                    : "Aggressiver striegeln!");
        } else {
            binding.tvCsc.setText("n. a.");
            binding.tvRecommendation.setText("");
        }

        JSONObject combo = root.optJSONObject("combo");
        loadImages(combo);
        String modeLabel = hasWeedFilter ? " | Modus B: Mit Unkrautfilter" : " | Modus A: Ohne Unkrautfilter";
        binding.tvStatus.setText("Analyse abgeschlossen." + modeLabel);
    }

    // ===================================================
    // Hilfsfunktionen
    // ===================================================
    private void loadImages(JSONObject combo) {
        if (combo == null) {
            lastOriginalPath = null;
            lastStackedPath = null;
            Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
            Glide.with(binding.ivStackedPanels).clear(binding.ivStackedPanels);
            return;
        }

        String origPanel = normalizePath(combo.optString("original_panel", null));
        String stacked   = normalizePath(combo.optString("labeled_tripanel_stacked", null));

        Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
        Glide.with(binding.ivStackedPanels).clear(binding.ivStackedPanels);

        if (!TextUtils.isEmpty(origPanel)) {
            lastOriginalPath = origPanel;
            loadInto(binding.ivOriginalPanel, origPanel);
        } else {
            lastOriginalPath = null;
        }

        if (!TextUtils.isEmpty(stacked)) {
            lastStackedPath = stacked;
            loadInto(binding.ivStackedPanels, stacked);
        } else {
            lastStackedPath = null;
        }
    }

    /** Lädt Pfad/URI in eine ImageView (unterstützt file paths, file://, content://) */
    private void loadInto(@NonNull ImageView target, @NonNull String pathOrUri) {
        String p = normalizePath(pathOrUri);
        if (TextUtils.isEmpty(p)) {
            Glide.with(target).clear(target);
            return;
        }

        if (p.startsWith("content://") || p.startsWith("file://")) {
            Glide.with(target)
                    .load(Uri.parse(p))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .fitCenter()
                    .into(target);
        } else {
            File f = new File(p);
            Glide.with(target)
                    .load(f)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .signature(new ObjectKey(f.exists() ? f.lastModified() : System.currentTimeMillis()))
                    .fitCenter()
                    .into(target);
        }
    }

    /** Normalisiert Pfad: trimmt, entfernt "file://", belässt content:// */
    private String normalizePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("file://")) {
            return s.substring(7); // echten Dateipfad herauslösen
        }
        return s;
        // content:// unverändert lassen
    }

    private static Double asDouble(JSONObject obj, String key) {
        if (obj == null) return null;
        double v = obj.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private static String toPct(Double v) {
        return (v == null) ? "-" : String.format("%.2f %%", v);
    }

    private static String toPct(double v) {
        if (Double.isNaN(v)) return "-";
        return String.format("%.2f %%", v);
    }

    private static void safeSet(android.widget.TextView tv, String txt) {
        if (tv != null) tv.setText(txt);
    }

    private void safeResetFields() {
        safeSet(binding.tvBeforeBw, "-");
        safeSet(binding.tvBeforeFiltered, "-");
        safeSet(binding.tvAfterBw, "-");
        safeSet(binding.tvAfterFiltered, "-");
        safeSet(binding.tvDeltaBw, "-");
        safeSet(binding.tvDeltaFiltered, "-");
        safeSet(binding.tvCsc, "n. a.");
        safeSet(binding.tvBeforeWeedFiltered, "-");
        safeSet(binding.tvAfterWeedFiltered, "-");
        safeSet(binding.tvDeltaWeedFiltered, "-");
        if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(View.GONE);
        if (binding.rowAfterWeedFiltered  != null) binding.rowAfterWeedFiltered.setVisibility(View.GONE);
        if (binding.rowDeltaWeedFiltered  != null) binding.rowDeltaWeedFiltered.setVisibility(View.GONE);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // =================================================
    // Fullscreen-Bilddialog mit Pinch-to-Zoom & Drag
    //  -> jetzt: unterstützt file paths, file:// und content://
    // =================================================
    public static class FullscreenImageDialog extends DialogFragment {

        private static final String ARG_PATH = "arg_path";

        public static void show(@NonNull Fragment host, @NonNull String imagePath) {
            FullscreenImageDialog d = new FullscreenImageDialog();
            Bundle b = new Bundle();
            b.putString(ARG_PATH, imagePath);
            d.setArguments(b);
            d.show(host.getParentFragmentManager(), "FullscreenImageDialog");
        }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            if (getDialog() != null && getDialog().getWindow() != null) {
                Window w = getDialog().getWindow();
                w.requestFeature(Window.FEATURE_NO_TITLE);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            return inflater.inflate(R.layout.dialog_fullscreen_image, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            ImageView image = view.findViewById(R.id.fullscreenImage);
            View close = view.findViewById(R.id.closeBtn);
            View scrim = view.findViewById(R.id.scrim);

            String raw = (getArguments() != null) ? getArguments().getString(ARG_PATH) : null;
            if (!TextUtils.isEmpty(raw)) {
                String path = raw.trim();
                try {
                    if (path.startsWith("content://") || path.startsWith("file://")) {
                        Glide.with(image)
                                .load(Uri.parse(path))
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .fitCenter()
                                .into(image);
                    } else {
                        if (path.startsWith("file://")) path = path.substring(7);
                        File f = new File(path);
                        Glide.with(image)
                                .load(f)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .signature(new ObjectKey(f.exists() ? f.lastModified() : System.currentTimeMillis()))
                                .fitCenter()
                                .into(image);
                    }
                } catch (Exception ignore) {
                    // graceful no-op
                }
            }

            enableZoom(image);
            close.setOnClickListener(v -> dismissAllowingStateLoss());
            scrim.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        @Override
        public int getTheme() {
            return android.R.style.Theme_Black_NoTitleBar_Fullscreen;
        }

        private void enableZoom(@NonNull ImageView imageView) {
            imageView.setScaleType(ImageView.ScaleType.MATRIX);

            Matrix matrix = new Matrix();
            Matrix savedMatrix = new Matrix();
            PointF start = new PointF();

            final float[] mVals = new float[9];
            final float[] last = new float[2];

            final int NONE = 0, DRAG = 1, ZOOM = 2;
            final class Mode { int v = NONE; }
            final Mode mode = new Mode();

            final float MIN_ZOOM = 1.0f, MAX_ZOOM = 6.0f;

            ScaleGestureDetector scaleDetector = new ScaleGestureDetector(
                    requireContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            matrix.getValues(mVals);
                            float current = mVals[Matrix.MSCALE_X];
                            float factor = detector.getScaleFactor();
                            float target = current * factor;

                            if (target < MIN_ZOOM) factor = MIN_ZOOM / current;
                            if (target > MAX_ZOOM) factor = MAX_ZOOM / current;

                            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                            imageView.setImageMatrix(matrix);
                            return true;
                        }

                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                            mode.v = ZOOM;
                            return true;
                        }
                    });

            GestureDetector gestureDetector = new GestureDetector(
                    requireContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            matrix.getValues(mVals);
                            float current = mVals[Matrix.MSCALE_X];
                            float target = (current > 1.5f) ? 1.0f : 2.0f;
                            float factor = target / current;
                            matrix.postScale(factor, factor, e.getX(), e.getY());
                            imageView.setImageMatrix(matrix);
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent e) {
                            matrix.reset();
                            imageView.setImageMatrix(matrix);
                        }
                    });

            imageView.setOnTouchListener((v, event) -> {
                gestureDetector.onTouchEvent(event);
                scaleDetector.onTouchEvent(event);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        savedMatrix.set(matrix);
                        start.set(event.getX(), event.getY());
                        last[0] = event.getX();
                        last[1] = event.getY();
                        mode.v = DRAG;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (mode.v == DRAG) {
                            matrix.getValues(mVals);
                            float current = mVals[Matrix.MSCALE_X];
                            if (current > 1.01f) {
                                float dx = event.getX() - last[0];
                                float dy = event.getY() - last[1];
                                matrix.postTranslate(dx, dy);
                                imageView.setImageMatrix(matrix);
                                last[0] = event.getX();
                                last[1] = event.getY();
                            }
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mode.v = NONE;
                        break;
                }
                return true;
            });
        }
    }
}
