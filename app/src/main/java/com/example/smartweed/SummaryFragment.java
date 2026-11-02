// app/src/main/java/com/example/smartweed/SummaryFragment.java
package com.example.smartweed;

import android.graphics.Matrix;
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
import android.widget.Toast;

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
import java.util.Locale;

public class SummaryFragment extends Fragment {

    /** Plausibilitätsgrenzen der Bedeckung — nur für den Anzeigetext; ob ein
     *  Bild auffällig ist, entscheidet analysis.py ("coverage_warning"). */
    private static final String COVERAGE_WARN_LOW = "3";
    private static final String COVERAGE_WARN_HIGH = "80";

    private FragmentSummaryBinding binding;
    private AnalysisViewModel analysisVM;
    private AnalysisSettings analysisSettings;
    private android.app.AlertDialog activeDialog; // für dismiss in onDestroyView

    private String lastOriginalPath = null;

    /** Verwerfen erst anbieten, wenn ein Ausgabeordner existiert UND die
     *  Analyse nicht mehr läuft (sonst Race mit dem Python-Thread). */
    private void updateDiscardButton() {
        if (binding == null) return;
        boolean running = Boolean.TRUE.equals(analysisVM.running.getValue());
        boolean hasOutDir = !TextUtils.isEmpty(analysisVM.outDir.getValue());
        binding.btnDiscardAnalysis.setVisibility(
                hasOutDir && !running ? View.VISIBLE : View.GONE);
        binding.btnFinishAnalysis.setVisibility(
                hasOutDir && !running ? View.VISIBLE : View.GONE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSummaryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        analysisVM = new ViewModelProvider(requireActivity()).get(AnalysisViewModel.class);
        analysisSettings = new AnalysisSettings(requireContext());

        binding.progress.setVisibility(View.GONE);
        binding.tvStatus.setText("");
        binding.tvRecommendation.setText("");
        binding.tvCscTarget.setText(getString(R.string.csc_target_range,
                trimNumber(analysisSettings.getCscBandLow()),
                trimNumber(analysisSettings.getCscBandHigh())));

        // Top-Images: Fullscreen
        binding.ivOriginalPanel.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastOriginalPath)) {
                FullscreenImageDialog.show(SummaryFragment.this, lastOriginalPath);
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
            if (!TextUtils.isEmpty(path)) {
                // Statt des internen App-Pfads (Android/data/..., für Nutzer
                // praktisch unauffindbar) den Galerie-Ordner zeigen, in den
                // Übersicht und Auswertungsbilder exportiert werden.
                binding.tvOutDir.setText(getString(R.string.output_gallery_path,
                        MediaExport.galleryPath(MediaExport.analysisSubPath(new File(path)))));
            }
            updateDiscardButton();
        });

        // Fertig: Analyse ist gespeichert -> zurück zur Startseite. popBackStack
        // bis zur Startseite räumt Analyse- und Ergebnisseite vom Stapel, sodass
        // "Zurück" danach nicht wieder hier landet.
        binding.btnFinishAnalysis.setOnClickListener(v -> {
            androidx.navigation.NavController nav =
                    androidx.navigation.fragment.NavHostFragment.findNavController(this);
            if (!nav.popBackStack(R.id.HomeFragment, false)) {
                nav.navigate(R.id.HomeFragment);
            }
        });

        // Analyse verwerfen: Ergebnisbilder in den Papierkorb, zurück zur Analyse-Seite
        binding.btnDiscardAnalysis.setOnClickListener(v -> {
            String path = analysisVM.outDir.getValue();
            if (TextUtils.isEmpty(path)) return;
            // Guard gegen ein Race: outDir ist schon VOR dem Analyse-Ende
            // gesetzt — ein Verwerfen, während der Python-Thread noch in
            // genau dieses Verzeichnis schreibt, hinterließe halb verschobene
            // Ordner bzw. nachlaufend wieder angelegte "verworfene" Ergebnisse
            if (Boolean.TRUE.equals(analysisVM.running.getValue())) return;
            activeDialog = new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.session_delete_confirm_title)
                    .setMessage(R.string.analysis_discard_confirm_message)
                    .setPositiveButton(R.string.session_delete_action, (d, w) -> {
                        // Verschieben in den Papierkorb im Hintergrund (sonst ANR bei vielen Bildern)
                        final android.content.Context appCtx = requireContext().getApplicationContext();
                        binding.btnDiscardAnalysis.setEnabled(false);
                        new Thread(() -> {
                            int moved = new TrashManager(appCtx).moveDirectoryToTrash(new File(path));
                            // getActivity() EINMAL holen statt isAdded()+requireActivity():
                            // zwischen Check und Aufruf könnte das Fragment sonst
                            // detached werden (IllegalStateException)
                            android.app.Activity activity = getActivity();
                            if (activity == null) return;
                            activity.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(),
                                        getResources().getQuantityString(R.plurals.session_deleted, moved, moved),
                                        Toast.LENGTH_SHORT).show();
                                androidx.navigation.fragment.NavHostFragment.findNavController(this).popBackStack();
                            });
                        }).start();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        analysisVM.running.observe(getViewLifecycleOwner(), running -> {
            if (running != null && running) {
                binding.progress.setVisibility(View.VISIBLE);
                binding.tvStatus.setText(R.string.status_analysis_running);
            } else {
                binding.progress.setVisibility(View.GONE);
                // "Abgeschlossen" nur behaupten, wenn wirklich ein Ergebnis da
                // ist: Nach einem Prozess-Tod im Hintergrund startet das
                // ViewModel leer (running=false, resultJson=null) — der alte
                // Code zeigte dann "Analyse abgeschlossen" mit leeren Feldern.
                if (TextUtils.isEmpty(binding.tvStatus.getText())
                        && !TextUtils.isEmpty(analysisVM.resultJson.getValue())) {
                    binding.tvStatus.setText(R.string.status_analysis_done);
                }
            }
            updateDiscardButton();
        });

        // Fortschritt ("Analysiere Bild 2 von 12 …")
        analysisVM.progress.observe(getViewLifecycleOwner(), p -> {
            if (p != null && p.length == 2 && Boolean.TRUE.equals(analysisVM.running.getValue())) {
                binding.tvStatus.setText(getString(R.string.progress_image, p[0], p[1]));
            }
        });

        analysisVM.error.observe(getViewLifecycleOwner(), err -> {
            if (!TextUtils.isEmpty(err)) {
                binding.tvStatus.setText(err);
                binding.tvRecommendation.setText("");
                Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
                safeResetFields();
                lastOriginalPath = null;

                if (binding.pairsContainer != null) binding.pairsContainer.removeAllViews();
                if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);
            }
        });

        // Ergebnis beobachten
        analysisVM.resultJson.observe(getViewLifecycleOwner(), json -> {
            if (TextUtils.isEmpty(json)) return;
            try {
                handleGroupResult(new JSONObject(json.trim()));
            } catch (Exception e) {
                binding.tvStatus.setText(R.string.status_result_parse_error);
                binding.tvRecommendation.setText("");
                Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
                lastOriginalPath = null;
                if (binding.pairsContainer != null) binding.pairsContainer.removeAllViews();
                if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);
            }
        });
    }

    // ==================================================================
    // Gruppen-Ergebnis: N Vorher- vs. M Nachher-Bilder (Mittelwerte)
    // ==================================================================
    private void handleGroupResult(@NonNull JSONObject root) {
        JSONObject avgB = root.optJSONObject("avg_before");
        JSONObject avgA = root.optJSONObject("avg_after");
        if (avgB == null || avgA == null) {
            // Nicht still zurückkehren: sonst bliebe der Status dauerhaft auf
            // "Analysiere Bild N von N …" stehen
            binding.tvStatus.setText(R.string.status_result_parse_error);
            binding.tvRecommendation.setText("");
            safeResetFields();
            return;
        }

        boolean weedFilter = root.optBoolean("weed_filter", false);
        boolean rowMode = root.optBoolean("row_mode", false);
        boolean rowsDetected = rowMode && root.optBoolean("rows_detected", false);
        int nBefore = root.optInt("count_before", 0);
        int nAfter = root.optInt("count_after", 0);

        Double bBw = asDouble(avgB, "bw");
        Double bFlt = asDouble(avgB, "filtered");
        Double bWf = asDouble(avgB, "weedfiltered");
        Double bCrop = asDouble(avgB, "crop");
        Double bWeed = asDouble(avgB, "weed");
        Double aBw = asDouble(avgA, "bw");
        Double aFlt = asDouble(avgA, "filtered");
        Double aWf = asDouble(avgA, "weedfiltered");
        Double aCrop = asDouble(avgA, "crop");
        Double aWeed = asDouble(avgA, "weed");

        safeSet(binding.tvBeforeBw, toPct(bBw));
        safeSet(binding.tvBeforeFiltered, toPct(bFlt));
        safeSet(binding.tvAfterBw, toPct(aBw));
        safeSet(binding.tvAfterFiltered, toPct(aFlt));

        JSONObject delta = root.optJSONObject("delta");
        safeSet(binding.tvDeltaBw, toPp(asDouble(delta, "bw")));
        safeSet(binding.tvDeltaFiltered, toPp(asDouble(delta, "filtered")));

        boolean showWf = (bWf != null || aWf != null);
        if (showWf) {
            safeSet(binding.tvBeforeWeedFiltered, toPct(bWf));
            safeSet(binding.tvAfterWeedFiltered, toPct(aWf));
            safeSet(binding.tvDeltaWeedFiltered, toPp(asDouble(delta, "weedfiltered")));
        }
        int wfVis = showWf ? View.VISIBLE : View.GONE;
        if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(wfVis);
        if (binding.rowAfterWeedFiltered != null) binding.rowAfterWeedFiltered.setVisibility(wfVis);
        if (binding.rowDeltaWeedFiltered != null) binding.rowDeltaWeedFiltered.setVisibility(wfVis);

        // CSC-Basis: Modus C -> Kulturpflanzen-Bedeckung (Reihen), Modus B ->
        // unkrautgefilterte Werte (Fallback Gefiltert), Modus A -> SW-Werte.
        // Gleiche Wahl wie build_group_summary in analysis.py, damit App,
        // Übersichtsbild und Kontroll-Korrektur dieselbe Basis nutzen.
        String basis;
        if (rowsDetected && bCrop != null && aCrop != null) {
            basis = "crop";
        } else if (weedFilter && bWf != null && aWf != null) {
            basis = "weedfiltered";
        } else if (weedFilter) {
            basis = "filtered";
        } else {
            basis = "bw";
        }
        Double cscB = asDouble(avgB, basis);
        Double cscA = asDouble(avgA, basis);

        // v1.8: Kontrollfläche (unbehandelt, gleiches Licht). Kontrollbilder
        // fließen NICHT in die Mittelwerte oben ein; sie korrigieren nur den CSC.
        int nCtrlB = root.optInt("count_control_before", 0);
        int nCtrlA = root.optInt("count_control_after", 0);
        boolean hasControl = nCtrlB > 0 && nCtrlA > 0;
        JSONObject avgCtrlB = root.optJSONObject("avg_control_before");
        JSONObject avgCtrlA = root.optJSONObject("avg_control_after");
        // Kontroll-Basis: wie analysis.py — fehlt der Kontrolle der Wert der
        // CSC-Basis (Modus C: auf der Kontrolle keine Reihen erkannt), wird ihre
        // relative Änderung auf "filtered" bzw. "bw" gemessen
        String controlBasis = root.optString("control_basis", "");
        if (TextUtils.isEmpty(controlBasis)) {
            controlBasis = basis;
            for (String cand : new String[]{basis, "filtered", "bw"}) {
                if (asDouble(avgCtrlB, cand) != null && asDouble(avgCtrlA, cand) != null) {
                    controlBasis = cand;
                    break;
                }
            }
        }
        Double ctrlB = asDouble(avgCtrlB, controlBasis);
        Double ctrlA = asDouble(avgCtrlA, controlBasis);
        Double corrected = asDouble(root, "csc_control_corrected");
        if (corrected == null && hasControl) {
            // Rückfall, falls das JSON den Wert nicht liefert — gleiche Formel,
            // liefert bei unbrauchbarer Kontrolle (z. B. 0 % Bedeckung) null
            corrected = CscCalculator.computeControlCorrected(cscB, cscA, ctrlB, ctrlA);
        }
        Double controlChange = asDouble(root, "control_change_percent");
        if (controlChange == null && hasControl && ctrlB != null && ctrlA != null && ctrlB > 0) {
            controlChange = (ctrlA / ctrlB - 1.0) * 100.0;
        }
        applyCscHero(cscB, cscA, corrected);
        String controlReason = root.optString("control_unavailable_reason", "");
        if (TextUtils.isEmpty(controlReason) && hasControl && corrected == null) {
            if (CscCalculator.compute(cscB, cscA) == null) {
                controlReason = "no_csc";
            } else if (ctrlB == null || ctrlA == null) {
                controlReason = "no_basis";
            } else {
                controlReason = "no_cover";
            }
        }
        applyControlInfo(hasControl, corrected, controlChange, controlReason,
                !controlBasis.equals(basis));

        // Unkraut-Wirkungsgrad (nur Modus C mit erkannten Reihen):
        // relative Abnahme der Unkraut-Bedeckung zwischen den Reihen
        if (rowsDetected && bWeed != null && aWeed != null) {
            Double efficacy = CscCalculator.compute(bWeed, aWeed);
            binding.tvWeedEfficacy.setText(getString(R.string.weed_efficacy, toPct(efficacy)));
            binding.tvWeedEfficacy.setVisibility(View.VISIBLE);
        } else {
            binding.tvWeedEfficacy.setVisibility(View.GONE);
        }

        // Plausibilitäts-Warnungen (Licht / Streuung / Reihen nicht erkannt)
        StringBuilder warn = new StringBuilder();
        if (root.optBoolean("brightness_warning", false)) {
            warn.append(getString(R.string.warning_light));
        }
        if (root.optBoolean("brightness_spread_warning", false)) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getString(R.string.warning_light_spread));
        }
        if (rowMode && !rowsDetected) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getString(R.string.warning_rows));
        }
        if (root.optBoolean("light_mismatch_warning", false)) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getString(R.string.warning_light_mismatch));
        }
        int frameMissing = root.optInt("frame_missing_count", 0);
        if (frameMissing > 0) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getResources().getQuantityString(R.plurals.warning_frame_missing,
                    frameMissing, frameMissing));
        }
        // v1.8: unplausible Bedeckung (< 3 % oder > 80 %) — im Feldversuch
        // waren genau solche Werte Messfehler der alten Segmentierung
        JSONArray itemsArr = root.optJSONArray("items");
        int coverageWarnings = root.has("coverage_warning_count")
                ? root.optInt("coverage_warning_count", 0)
                : countItemsWithKey(itemsArr, "coverage_warning");
        if (coverageWarnings > 0) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getResources().getQuantityString(R.plurals.warning_coverage_implausible,
                    coverageWarnings, coverageWarnings, COVERAGE_WARN_LOW, COVERAGE_WARN_HIGH));
        }
        // v1.8: dichter Bestand — soilref unterschätzt dann (bekannte Grenze),
        // die 80-%-Warnung oben kann das mit soilref nicht erkennen
        int denseWarnings = root.has("dense_warning_count")
                ? root.optInt("dense_warning_count", 0)
                : countItemsWithKey(itemsArr, "dense_warning");
        if (denseWarnings > 0) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getResources().getQuantityString(R.plurals.warning_coverage_dense,
                    denseWarnings, denseWarnings));
        }
        int galleryFailed = root.optInt("gallery_export_failed", 0);
        if (galleryFailed > 0) {
            if (warn.length() > 0) warn.append("\n");
            warn.append(getResources().getQuantityString(R.plurals.warning_gallery_export_failed,
                    galleryFailed, galleryFailed));
        }
        binding.tvAnalysisWarning.setText(warn.toString());
        binding.tvAnalysisWarning.setVisibility(warn.length() > 0 ? View.VISIBLE : View.GONE);

        // v1.8: Welcher Zählrahmen wurde erkannt (grauer U-Rahmen / roter Rahmen)
        int uFrames = 0;
        int redFrames = 0;
        if (itemsArr != null) {
            for (int i = 0; i < itemsArr.length(); i++) {
                JSONObject it = itemsArr.optJSONObject(i);
                if (it == null || !it.optBoolean("frame_found", false)) continue;
                String kind = it.optString("frame_kind", "");
                if ("u_frame".equals(kind)) uFrames++;
                else if ("red".equals(kind)) redFrames++;
            }
        }
        if (uFrames + redFrames > 0) {
            binding.tvFrameInfo.setText(getString(R.string.frame_info_summary, uFrames, redFrames));
            binding.tvFrameInfo.setVisibility(View.VISIBLE);
        } else {
            binding.tvFrameInfo.setVisibility(View.GONE);
        }

        // Übersichtsbild (Vorher-Zeile / Nachher-Zeile)
        JSONObject combo = root.optJSONObject("combo");
        String overview = (combo != null) ? normalizePath(combo.optString("overview", "")) : "";
        Glide.with(binding.ivOriginalPanel).clear(binding.ivOriginalPanel);
        if (!TextUtils.isEmpty(overview)) {
            lastOriginalPath = overview;
            binding.ivOriginalPanel.setVisibility(View.VISIBLE);
            loadInto(binding.ivOriginalPanel, overview);
        } else {
            lastOriginalPath = null;
            binding.ivOriginalPanel.setVisibility(View.GONE);
        }

        renderItems(itemsArr);

        // Modus-Label als Platzhalter im Format-String (statt Konkatenation im
        // Code): jede Sprache kontrolliert die komplette Satzstellung selbst
        String modeLabel = getString(rowMode ? R.string.mode_label_c
                : (weedFilter ? R.string.mode_label_b : R.string.mode_label_a));
        if (nCtrlB > 0 || nCtrlA > 0) {
            binding.tvStatus.setText(getString(R.string.status_analysis_done_group_control,
                    nBefore, nAfter, nCtrlB, nCtrlA, modeLabel));
        } else {
            binding.tvStatus.setText(getString(R.string.status_analysis_done_group, nBefore, nAfter, modeLabel));
        }
    }

    /** Einzelergebnisse pro Bild (erst alle Vorher-, dann alle Nachher-Bilder) */
    private void renderItems(JSONArray items) {
        if (binding.pairsContainer == null) return;
        binding.pairsContainer.removeAllViews();
        if (items == null || items.length() == 0) {
            if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.GONE);
            return;
        }
        if (binding.tvPairsHeader != null) binding.tvPairsHeader.setVisibility(View.VISIBLE);

        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;

            String tag = it.optString("tag", "");
            int idx = it.optInt("index", i) + 1;

            View itemView = getLayoutInflater().inflate(R.layout.item_pair_result, binding.pairsContainer, false);
            android.widget.TextView header = itemView.findViewById(R.id.tvPairHeader);
            android.widget.TextView metrics = itemView.findViewById(R.id.tvPairMetrics);
            ImageView img = itemView.findViewById(R.id.ivPairOriginal);

            header.setText(getString(R.string.pair_header,
                    getString(labelForTag(tag)), idx, it.optString("file", "")));

            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.label_bw)).append(" ")
              .append(toPct(asDouble(it, "coverage_bw_percent")));
            sb.append("   ").append(getString(R.string.label_filtered)).append(" ")
              .append(toPct(asDouble(it, "coverage_filtered_percent")));
            Double wf = asDouble(it, "coverage_weedfiltered_percent");
            if (wf != null) {
                sb.append("   ").append(getString(R.string.label_weed_filtered)).append(" ").append(toPct(wf));
            }
            Double crop = asDouble(it, "coverage_crop_percent");
            Double weed = asDouble(it, "coverage_weed_percent");
            if (crop != null && weed != null) {
                sb.append("\n").append(getString(R.string.label_crop)).append(" ").append(toPct(crop));
                sb.append("   ").append(getString(R.string.label_weed_cov)).append(" ").append(toPct(weed));
            }
            // Zählrahmen-Status nur, wenn der Zuschnitt angefordert war
            if (it.has("frame_found")) {
                int frameLabel;
                if (!it.optBoolean("frame_found", false)) {
                    frameLabel = R.string.label_frame_missing;
                } else if ("u_frame".equals(it.optString("frame_kind", ""))) {
                    frameLabel = R.string.label_frame_found_u;
                } else if ("red".equals(it.optString("frame_kind", ""))) {
                    frameLabel = R.string.label_frame_found_red;
                } else {
                    frameLabel = R.string.label_frame_found;
                }
                sb.append("\n").append(getString(frameLabel));
            }
            // v1.8: Plausibilitäts-Hinweis je Bild ("low" < 3 %, "high" > 80 %)
            String covWarn = it.optString("coverage_warning", "");
            if ("low".equals(covWarn)) {
                sb.append("\n").append(getString(R.string.label_coverage_low, COVERAGE_WARN_LOW));
            } else if ("high".equals(covWarn)) {
                sb.append("\n").append(getString(R.string.label_coverage_high, COVERAGE_WARN_HIGH));
            }
            if (it.optBoolean("dense_warning", false)) {
                sb.append("\n").append(getString(R.string.label_coverage_dense));
            }
            metrics.setText(sb.toString());

            // Bild: Reihen-Overlay bevorzugen, sonst das Analyse-Panel
            JSONObject outputs = it.optJSONObject("outputs");
            String imgPath = null;
            if (outputs != null) {
                imgPath = normalizePath(outputs.optString("row_overlay", ""));
                if (TextUtils.isEmpty(imgPath)) imgPath = normalizePath(outputs.optString("panel", ""));
            }
            if (!TextUtils.isEmpty(imgPath)) {
                img.setVisibility(View.VISIBLE);
                loadInto(img, imgPath);
                final String clickPath = imgPath;
                img.setOnClickListener(v -> FullscreenImageDialog.show(SummaryFragment.this, clickPath));
            }

            binding.pairsContainer.addView(itemView);

            View divider = new View(requireContext());
            divider.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            divider.setBackgroundColor(0x1F000000);
            binding.pairsContainer.addView(divider);
        }
    }

    /** Beschriftung einer Bildgruppe (Tag aus analysis.py) */
    private static int labelForTag(String tag) {
        switch (tag) {
            case "after":          return R.string.label_after;
            case "control_before": return R.string.label_control_before;
            case "control_after":  return R.string.label_control_after;
            default:               return R.string.label_before;
        }
    }

    private static int countItemsWithKey(JSONArray items, String key) {
        if (items == null) return 0;
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.has(key)) n++;
        }
        return n;
    }

    // ===================================================
    // CSC-Hero-Karte (große Empfehlung)
    // ===================================================

    /**
     * @param controlCorrected kontrollbezogener CSC (v1.8) oder null — ist er
     *        gesetzt, wird ER groß angezeigt und für die Empfehlung genutzt,
     *        der rohe CSC erscheint als Zweitwert darunter.
     */
    private void applyCscHero(Double coverageBefore, Double coverageAfter, Double controlCorrected) {
        double bandLow  = analysisSettings.getCscBandLow();
        double bandHigh = analysisSettings.getCscBandHigh();

        binding.tvCscTarget.setText(getString(R.string.csc_target_range,
                trimNumber(bandLow), trimNumber(bandHigh)));

        Double csc = CscCalculator.compute(coverageBefore, coverageAfter);
        CscCalculator.Recommendation reco =
                CscCalculator.recommendForCoverage(coverageBefore, coverageAfter, bandLow, bandHigh);
        if (controlCorrected != null) {
            // "Kein Bewuchs" bleibt vorrangig (kommt aus der Vorher-Bedeckung);
            // sonst entscheidet der kontrollbezogene Wert
            if (reco != CscCalculator.Recommendation.NO_VEGETATION
                    && reco != CscCalculator.Recommendation.NOT_AVAILABLE) {
                reco = CscCalculator.recommend(controlCorrected, bandLow, bandHigh);
            }
            binding.tvCscHeroTitle.setText(R.string.csc_hero_title_control);
            binding.tvCsc.setText(toPct(controlCorrected));
            binding.tvCscRaw.setText(getString(R.string.csc_raw_secondary,
                    csc == null ? getString(R.string.not_available) : toPct(csc)));
            binding.tvCscRaw.setVisibility(View.VISIBLE);
        } else {
            binding.tvCscHeroTitle.setText(R.string.csc_hero_title);
            binding.tvCsc.setText(csc == null ? getString(R.string.not_available) : toPct(csc));
            binding.tvCscRaw.setVisibility(View.GONE);
        }
        binding.tvRecommendation.setText(recommendationText(reco));
        binding.tvRecommendation.setTextColor(recommendationColor(reco));
    }

    /**
     * Erklärt die Kontrollfläche: wie stark sie sich OHNE Striegeln (relativ)
     * verändert hat, bzw. WARUM keine Korrektur möglich war.
     * @param reason "no_csc" | "no_basis" | "no_cover" (analysis.py
     *        control_unavailable_reason), nur relevant, wenn corrected null ist
     * @param basisFallback Kontrolle auf anderer Basis als der CSC gemessen
     */
    private void applyControlInfo(boolean hasControl, Double corrected, Double controlChange,
                                  String reason, boolean basisFallback) {
        if (!hasControl) {
            binding.tvControlInfo.setVisibility(View.GONE);
            return;
        }
        String text;
        if (corrected != null && controlChange != null) {
            text = getString(R.string.control_change_info,
                    String.format(Locale.getDefault(), "%+.1f %%", controlChange));
            if (basisFallback) {
                text = text + "\n" + getString(R.string.control_basis_fallback);
            }
        } else if ("no_csc".equals(reason)) {
            text = getString(R.string.control_correction_unavailable_no_csc);
        } else if ("no_basis".equals(reason)) {
            text = getString(R.string.control_correction_unavailable_no_basis);
        } else {
            text = getString(R.string.control_correction_unavailable);
        }
        binding.tvControlInfo.setText(text);
        binding.tvControlInfo.setVisibility(View.VISIBLE);
    }

    private String recommendationText(CscCalculator.Recommendation reco) {
        switch (reco) {
            case OPTIMAL:          return getString(R.string.recommendation_optimal);
            case MORE_AGGRESSIVE:  return getString(R.string.recommendation_more_aggressive);
            case LESS_AGGRESSIVE:  return getString(R.string.recommendation_less_aggressive);
            case CHECK_IMAGES:     return getString(R.string.recommendation_check_images);
            case NO_VEGETATION:    return getString(R.string.recommendation_no_vegetation);
            default:               return "";
        }
    }

    private int recommendationColor(CscCalculator.Recommendation reco) {
        int colorRes;
        switch (reco) {
            case OPTIMAL:          colorRes = R.color.traktor_green;  break;
            case MORE_AGGRESSIVE:  colorRes = R.color.traktor_blue;   break;
            case LESS_AGGRESSIVE:  colorRes = R.color.warning_red;    break;
            case CHECK_IMAGES:
            case NO_VEGETATION:    colorRes = R.color.status_warning; break;
            default:               colorRes = R.color.text_secondary;
        }
        return requireContext().getColor(colorRes);
    }

    /** Formatiert Zahlen ohne unnötige Nachkommastellen (8.0 → "8") */
    private static String trimNumber(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    // ===================================================
    // Hilfsfunktionen
    // ===================================================

    /** Lädt Pfad/URI in eine ImageView (unterstützt file paths, file://, content://).
     *  Statisch, damit auch der FullscreenImageDialog denselben Ladepfad nutzt. */
    private static void loadInto(@NonNull ImageView target, @NonNull String pathOrUri) {
        String p = normalizePath(pathOrUri);
        if (TextUtils.isEmpty(p)) {
            Glide.with(target).clear(target);
            return;
        }

        if (p.startsWith("content://")) {
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
    private static String normalizePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("file://")) {
            // Uri.getPath() dekodiert %20 & Co. — substring(7) ließ
            // prozent-kodierte Zeichen stehen, die Datei wurde nicht gefunden
            String path = android.net.Uri.parse(s).getPath();
            return path != null ? path : s.substring(7);
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
        return (v == null) ? "-" : String.format(Locale.getDefault(), "%.2f %%", v);
    }

    private static String toPct(double v) {
        if (Double.isNaN(v)) return "-";
        return String.format(Locale.getDefault(), "%.2f %%", v);
    }

    private static String toPp(double v) {
        if (Double.isNaN(v)) return "-";
        return String.format(Locale.getDefault(), "%.2f pp", v);
    }

    private static String toPp(Double v) {
        return (v == null) ? "-" : toPp(v.doubleValue());
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
        safeSet(binding.tvCsc, getString(R.string.not_available));
        safeSet(binding.tvBeforeWeedFiltered, "-");
        safeSet(binding.tvAfterWeedFiltered, "-");
        safeSet(binding.tvDeltaWeedFiltered, "-");
        if (binding.rowBeforeWeedFiltered != null) binding.rowBeforeWeedFiltered.setVisibility(View.GONE);
        if (binding.rowAfterWeedFiltered  != null) binding.rowAfterWeedFiltered.setVisibility(View.GONE);
        if (binding.rowDeltaWeedFiltered  != null) binding.rowDeltaWeedFiltered.setVisibility(View.GONE);
        if (binding.tvWeedEfficacy != null) binding.tvWeedEfficacy.setVisibility(View.GONE);
        if (binding.tvAnalysisWarning != null) binding.tvAnalysisWarning.setVisibility(View.GONE);
        binding.tvCscHeroTitle.setText(R.string.csc_hero_title);
        binding.tvCscRaw.setVisibility(View.GONE);
        binding.tvControlInfo.setVisibility(View.GONE);
        binding.tvFrameInfo.setVisibility(View.GONE);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        // Offenen Bestätigungsdialog schließen (sonst WindowLeak bei Rotation)
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
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
                try {
                    // Gemeinsamer Ladepfad mit dem SummaryFragment (statt der
                    // früheren, teils toten Duplikat-Logik hier im Dialog)
                    loadInto(image, raw);
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
            // Das Bild startet mit fitCenter (ganz sichtbar, zentriert). Erst
            // bei der ersten Berührung wird auf MATRIX umgeschaltet — und zwar
            // mit genau der fitCenter-Matrix als Basis. Vorher wurde sofort
            // MATRIX mit Einheitsmatrix gesetzt: Das Bild lag dann in
            // Originalgröße oben links statt eingepasst in der Mitte.
            final Matrix base = new Matrix();
            final Matrix matrix = new Matrix();
            final float[] mVals = new float[9];
            final float[] last = new float[2];
            final float[] baseScale = {1f};
            final boolean[] dragging = {false};
            final float MAX_ZOOM = 6.0f;

            final Runnable ensureMatrixMode = () -> {
                if (imageView.getScaleType() == ImageView.ScaleType.MATRIX) return;
                base.set(imageView.getImageMatrix());
                base.getValues(mVals);
                baseScale[0] = mVals[Matrix.MSCALE_X] > 0 ? mVals[Matrix.MSCALE_X] : 1f;
                matrix.set(base);
                imageView.setScaleType(ImageView.ScaleType.MATRIX);
                imageView.setImageMatrix(matrix);
            };

            final java.util.function.Consumer<float[]> scaleBy = args -> {
                // args = {factor, focusX, focusY}
                matrix.getValues(mVals);
                float current = mVals[Matrix.MSCALE_X];
                float target = current * args[0];
                if (target <= baseScale[0] * 1.001f) {
                    matrix.set(base);          // ganz herausgezoomt: wieder zentriert
                } else {
                    float factor = Math.min(target, baseScale[0] * MAX_ZOOM) / current;
                    matrix.postScale(factor, factor, args[1], args[2]);
                }
                imageView.setImageMatrix(matrix);
            };

            ScaleGestureDetector scaleDetector = new ScaleGestureDetector(
                    requireContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            ensureMatrixMode.run();
                            scaleBy.accept(new float[]{detector.getScaleFactor(),
                                    detector.getFocusX(), detector.getFocusY()});
                            return true;
                        }
                    });

            GestureDetector gestureDetector = new GestureDetector(
                    requireContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            ensureMatrixMode.run();
                            matrix.getValues(mVals);
                            boolean zoomed = mVals[Matrix.MSCALE_X] > baseScale[0] * 1.5f;
                            if (zoomed) {
                                matrix.set(base);
                                imageView.setImageMatrix(matrix);
                            } else {
                                scaleBy.accept(new float[]{2.5f, e.getX(), e.getY()});
                            }
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent e) {
                            if (imageView.getScaleType() != ImageView.ScaleType.MATRIX) return;
                            matrix.set(base);
                            imageView.setImageMatrix(matrix);
                        }
                    });

            imageView.setOnTouchListener((v, event) -> {
                gestureDetector.onTouchEvent(event);
                scaleDetector.onTouchEvent(event);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        last[0] = event.getX();
                        last[1] = event.getY();
                        dragging[0] = true;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        dragging[0] = false;   // Zwei Finger: Zoom statt Verschieben
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging[0] && !scaleDetector.isInProgress()
                                && imageView.getScaleType() == ImageView.ScaleType.MATRIX) {
                            matrix.getValues(mVals);
                            if (mVals[Matrix.MSCALE_X] > baseScale[0] * 1.01f) {
                                matrix.postTranslate(event.getX() - last[0], event.getY() - last[1]);
                                imageView.setImageMatrix(matrix);
                            }
                        }
                        last[0] = event.getX();
                        last[1] = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging[0] = false;
                        break;
                }
                return true;
            });
        }
    }
}
