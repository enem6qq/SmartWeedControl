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

        // In den Papierkorb verschieben (mit Bestätigung). Das Kopieren läuft im
        // Hintergrund — bei Sessions mit vielen Fotos würde es sonst den
        // UI-Thread einfrieren (ANR).
        btnDelete.setOnClickListener(v -> activeDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_delete_confirm_title)
                .setMessage(getString(R.string.session_delete_confirm_message, session.dir.getName()))
                .setPositiveButton(R.string.session_delete_action, (d, w) -> {
                    btnDelete.setEnabled(false);
                    // Auch die Zeile sperren: Ein Tap würde sonst die Analyse
                    // einer Session öffnen, deren Bilder gerade parallel in
                    // den Papierkorb wandern
                    itemView.setEnabled(false);
                    itemView.setAlpha(0.5f);
                    new Thread(() -> {
                        int moved = trashManager.moveDirectoryToTrash(session.dir);
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
                .show());

        sessionsContainer.addView(itemView);
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
                    itemView.setEnabled(false);
                    itemView.setAlpha(0.5f);
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
