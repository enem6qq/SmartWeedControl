package com.example.smartweed;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;

import java.util.List;

/**
 * "Meine Aufnahmen": Übersicht aller Sessions (Feldeinsätze).
 * Pro Session: Vorschaubild, Bildanzahl, Öffnen (springt in die Analyse)
 * und In-den-Papierkorb-Verschieben.
 */
public class SessionsFragment extends Fragment {

    private LinearLayout sessionsContainer;
    private LinearLayout emptyState;
    private TrashManager trashManager;
    private AlertDialog activeDialog; // für dismiss in onDestroyView

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sessions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        trashManager = new TrashManager(requireContext());
        sessionsContainer = view.findViewById(R.id.sessionsContainer);
        emptyState = view.findViewById(R.id.sessionsEmptyState);

        refreshSessions();
    }

    private void refreshSessions() {
        // Sessions im Hintergrund einlesen (listSessions läuft rekursiv über
        // alle Analyse-/Detail-Ordner — auf dem UI-Thread droht Jank)
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            final List<SessionStore.Session> sessions = SessionStore.listSessions(appCtx);
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || sessionsContainer == null) return;
                sessionsContainer.removeAllViews();
                if (sessions.isEmpty()) {
                    emptyState.setVisibility(View.VISIBLE);
                    sessionsContainer.setVisibility(View.GONE);
                    return;
                }
                emptyState.setVisibility(View.GONE);
                sessionsContainer.setVisibility(View.VISIBLE);
                for (SessionStore.Session session : sessions) {
                    addSessionView(session);
                }
            });
        }).start();
    }

    private void addSessionView(SessionStore.Session session) {
        View itemView = getLayoutInflater().inflate(R.layout.item_session, sessionsContainer, false);

        ImageView ivThumb = itemView.findViewById(R.id.ivSessionThumb);
        TextView tvName = itemView.findViewById(R.id.tvSessionName);
        TextView tvInfo = itemView.findViewById(R.id.tvSessionInfo);
        ImageButton btnDelete = itemView.findViewById(R.id.btnSessionDelete);
        ImageButton btnRename = itemView.findViewById(R.id.btnSessionRename);

        tvName.setText(session.dir.getName());

        // Vollständige Format-Strings statt Konkatenation im Code: jede Sprache
        // kontrolliert die Satzstellung selbst
        String info = getString(R.string.session_photo_count,
                session.beforeCount, session.afterCount);
        if (session.analysisCount > 0) {
            info = getString(R.string.session_info_with_analyses, info, session.analysisCount);
        }
        tvInfo.setText(info);

        if (session.thumbnail != null) {
            Glide.with(this)
                    .load(session.thumbnail)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .signature(new ObjectKey(session.thumbnail.lastModified()))
                    .centerCrop()
                    .into(ivThumb);
        }

        // Antippen der Zeile = Session in der Analyse öffnen
        itemView.setOnClickListener(v -> {
            androidx.navigation.NavController nav = NavHostFragment.findNavController(this);
            // Doppelklick-Guard: nur navigieren, wenn wir noch auf dieser Seite stehen
            if (nav.getCurrentDestination() == null
                    || nav.getCurrentDestination().getId() != R.id.sessionsFragment) {
                return;
            }
            Bundle args = new Bundle();
            args.putString("imageDir", session.dir.getAbsolutePath());
            nav.navigate(R.id.action_sessionsFragment_to_AnalysisFragment, args);
        });

        btnRename.setOnClickListener(v -> showRenameDialog(session.dir, itemView));

        // v1.9: Fotos zu DIESER Aufnahme hinzufügen (z. B. die Nachher-Fotos
        // nach dem Striegeln) — die Kamera-Seite benutzt dann diese Session
        itemView.findViewById(R.id.btnSessionAddPhotos).setOnClickListener(v -> {
            androidx.navigation.NavController nav = NavHostFragment.findNavController(this);
            if (nav.getCurrentDestination() == null
                    || nav.getCurrentDestination().getId() != R.id.sessionsFragment) {
                return;
            }
            Bundle args = new Bundle();
            args.putString(CameraFragment.ARG_SESSION_NAME, session.dir.getName());
            nav.navigate(R.id.action_sessionsFragment_to_CameraFragment, args);
        });

        // v1.9: gespeicherte Ergebnisse (ergebnis.json) wieder anzeigen
        TextView btnResults = itemView.findViewById(R.id.btnSessionResults);
        if (!session.resultDirs.isEmpty()) {
            btnResults.setVisibility(View.VISIBLE);
            btnResults.setText(getString(R.string.session_show_results, session.resultDirs.size()));
            btnResults.setOnClickListener(v -> showResultsChooser(session));
        }

        // In den Papierkorb verschieben (mit Bestätigung). Das Verschieben läuft im
        // Hintergrund — bei Sessions mit vielen Fotos würde es sonst den
        // UI-Thread einfrieren (ANR).
        btnDelete.setOnClickListener(v -> {
            // v1.9: Bis v1.8 blieben alle Bilder der Session in der Galerie
            // liegen. Standard ist jetzt: auch dort entfernen (abwählbar). Die
            // Bilder bleiben 30 Tage im Papierkorb und kommen beim
            // Wiederherstellen auch wieder in die Galerie.
            final android.widget.CheckBox cbGallery = new android.widget.CheckBox(requireContext());
            cbGallery.setText(R.string.session_delete_also_gallery);
            cbGallery.setChecked(true);
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(cbGallery);
            final android.content.Context appCtx = requireContext().getApplicationContext();
            activeDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_delete_confirm_title)
                .setMessage(getString(R.string.session_delete_confirm_message, session.dir.getName()))
                .setView(box)
                .setPositiveButton(R.string.session_delete_action, (d, w) -> {
                    final boolean alsoGallery = cbGallery.isChecked();
                    final String sessionName = session.dir.getName();
                    btnDelete.setEnabled(false);
                    // Auch die Zeile sperren: Ein Tap würde sonst die Analyse
                    // einer Session öffnen, deren Bilder gerade parallel in
                    // den Papierkorb wandern
                    setRowEnabled(itemView, false);
                    new Thread(() -> {
                        int moved = trashManager.moveDirectoryToTrash(session.dir);
                        // Galerie erst NACH dem erfolgreichen Verschieben
                        // aufräumen: Scheitert das Verschieben, bleibt alles da
                        if (alsoGallery && !session.dir.exists()) {
                            MediaExport.deleteGalleryFolder(appCtx, sessionName);
                        }
                        // getActivity() EINMAL holen statt isAdded()+requireActivity():
                        // zwischen Check und Aufruf könnte das Fragment detached werden
                        android.app.Activity activity = getActivity();
                        if (activity == null) return;
                        activity.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(),
                                    getResources().getQuantityString(R.plurals.session_deleted, moved, moved),
                                    Toast.LENGTH_SHORT).show();
                            refreshSessions();
                        });
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });

        sessionsContainer.addView(itemView);
    }

    /** Sperrt eine Zeile samt ihrer Aktionen, während die Session im
     *  Hintergrund verschoben oder umbenannt wird. */
    private static void setRowEnabled(View itemView, boolean enabled) {
        itemView.setEnabled(enabled);
        itemView.setAlpha(enabled ? 1f : 0.5f);
        for (int id : new int[]{R.id.btnSessionAddPhotos, R.id.btnSessionResults,
                R.id.btnSessionRename, R.id.btnSessionDelete}) {
            View v = itemView.findViewById(id);
            if (v != null) v.setEnabled(enabled);
        }
    }

    /** v1.9: Eine gespeicherte Analyse der Session auswählen (bei nur einer direkt öffnen). */
    private void showResultsChooser(SessionStore.Session session) {
        if (session.resultDirs.size() == 1) {
            openSavedResult(session.resultDirs.get(0));
            return;
        }
        String[] names = new String[session.resultDirs.size()];
        for (int i = 0; i < names.length; i++) names[i] = session.resultDirs.get(i).getName();
        activeDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_results_title)
                .setItems(names, (d, which) -> openSavedResult(session.resultDirs.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Lädt ergebnis.json einer Analyse und zeigt es auf der Ergebnisseite. */
    private void openSavedResult(java.io.File analysisDir) {
        final AnalysisViewModel vm = new androidx.lifecycle.ViewModelProvider(requireActivity())
                .get(AnalysisViewModel.class);
        // Während eine Analyse läuft, gehört das ViewModel ihr — sonst würde
        // ihr Ergebnis gleich die geöffnete Ansicht überschreiben
        if (Boolean.TRUE.equals(vm.running.getValue())) {
            Toast.makeText(requireContext(), R.string.status_analysis_running, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            final String json = ResultStore.load(analysisDir);
            boolean wf = false;
            if (json != null) {
                try {
                    wf = new org.json.JSONObject(json).optBoolean("weed_filter", false);
                } catch (org.json.JSONException ignored) {
                    // load() hat das JSON bereits geparst — hier nicht erreichbar
                }
            }
            final boolean weedFilter = wf;
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (json == null) {
                    Toast.makeText(requireContext(), R.string.toast_result_not_readable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (Boolean.TRUE.equals(vm.running.getValue())) return;
                androidx.navigation.NavController nav = NavHostFragment.findNavController(this);
                if (nav.getCurrentDestination() == null
                        || nav.getCurrentDestination().getId() != R.id.sessionsFragment) {
                    return;
                }
                vm.error.setValue(null);
                vm.progress.setValue(null);
                vm.weedFilter.setValue(weedFilter);
                vm.outDir.setValue(analysisDir.getAbsolutePath());
                vm.resultJson.setValue(json);
                nav.navigate(R.id.action_sessionsFragment_to_SummaryFragment);
            });
        }).start();
    }

    /** Session-Ordner umbenennen (App-Speicher + Galerie-Ordner). */
    private void showRenameDialog(java.io.File dir, View itemView) {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setSingleLine(true);
        input.setText(dir.getName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.session_rename_hint);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        activeDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_rename_title)
                .setView(box)
                .setPositiveButton(R.string.session_rename_action, (d, w) -> {
                    String newName = SessionStore.sanitizeSessionName(input.getText().toString());
                    String oldName = dir.getName();
                    if (newName == null) {
                        Toast.makeText(requireContext(), R.string.session_rename_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (newName.equals(oldName)) return;
                    final android.content.Context appCtx = requireContext().getApplicationContext();
                    // Groß-/Kleinschreibung allein ändern: auf FAT/FUSE-Speichern
                    // ist das derselbe Ordner -> als "vergeben" werten wäre falsch
                    boolean onlyCase = newName.equalsIgnoreCase(oldName);
                    if (!onlyCase && SessionStore.sessionNameTaken(appCtx, newName)) {
                        Toast.makeText(requireContext(),
                                getString(R.string.session_rename_exists, newName), Toast.LENGTH_LONG).show();
                        return;
                    }
                    setRowEnabled(itemView, false);
                    new Thread(() -> {
                        java.io.File target = new java.io.File(dir.getParentFile(), newName);
                        boolean ok;
                        if (onlyCase) {
                            // Umweg über einen Zwischennamen, sonst scheitert renameTo
                            java.io.File tmp = new java.io.File(dir.getParentFile(),
                                    oldName + "_tmp" + System.currentTimeMillis());
                            ok = dir.renameTo(tmp) && tmp.renameTo(target);
                        } else {
                            ok = dir.renameTo(target);
                        }
                        int galleryFailed = ok ? MediaExport.renameGalleryFolder(appCtx, oldName, newName) : 0;
                        // v1.9: gemerkte Kamera-Session nachziehen (Fortsetzen-Angebot)
                        if (ok) SessionStore.renameActiveSession(appCtx, oldName, newName);
                        android.app.Activity activity = getActivity();
                        if (activity == null) return;
                        activity.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            String msg;
                            if (!ok) msg = getString(R.string.session_rename_failed);
                            else if (galleryFailed > 0) msg = getString(R.string.session_renamed_gallery_partial, newName);
                            else msg = getString(R.string.session_renamed, newName);
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                            refreshSessions();
                        });
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        sessionsContainer = null;
        emptyState = null;
        // Offenen Bestätigungsdialog schließen (sonst WindowLeak bei Rotation)
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }
}
