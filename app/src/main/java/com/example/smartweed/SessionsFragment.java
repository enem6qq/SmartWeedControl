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
import java.util.Locale;

/**
 * "Meine Aufnahmen": Übersicht aller Sessions (Feldeinsätze).
 * Pro Session: Vorschaubild, Bildanzahl, Öffnen (springt in die Analyse)
 * und In-den-Papierkorb-Verschieben.
 */
public class SessionsFragment extends Fragment {

    private LinearLayout sessionsContainer;
    private LinearLayout emptyState;
    private TrashManager trashManager;

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
        sessionsContainer.removeAllViews();
        List<SessionStore.Session> sessions = SessionStore.listSessions();

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
    }

    private void addSessionView(SessionStore.Session session) {
        View itemView = getLayoutInflater().inflate(R.layout.item_session, sessionsContainer, false);

        ImageView ivThumb = itemView.findViewById(R.id.ivSessionThumb);
        TextView tvName = itemView.findViewById(R.id.tvSessionName);
        TextView tvInfo = itemView.findViewById(R.id.tvSessionInfo);
        ImageButton btnDelete = itemView.findViewById(R.id.btnSessionDelete);

        tvName.setText(session.dir.getName());

        StringBuilder info = new StringBuilder(getString(R.string.session_photo_count,
                session.beforeCount, session.afterCount));
        if (session.analysisCount > 0) {
            info.append(String.format(Locale.getDefault(), " · %d ", session.analysisCount))
                .append(getString(R.string.analysis_fragment_label));
        }
        tvInfo.setText(info.toString());

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
            Bundle args = new Bundle();
            args.putString("imageDir", session.dir.getAbsolutePath());
            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_sessionsFragment_to_AnalysisFragment, args);
        });

        // In den Papierkorb verschieben (mit Bestätigung)
        btnDelete.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_delete_confirm_title)
                .setMessage(getString(R.string.session_delete_confirm_message, session.dir.getName()))
                .setPositiveButton(R.string.session_delete_action, (d, w) -> {
                    int moved = trashManager.moveDirectoryToTrash(session.dir);
                    Toast.makeText(requireContext(),
                            getString(R.string.session_deleted, moved),
                            Toast.LENGTH_SHORT).show();
                    refreshSessions();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        sessionsContainer.addView(itemView);
    }
}
