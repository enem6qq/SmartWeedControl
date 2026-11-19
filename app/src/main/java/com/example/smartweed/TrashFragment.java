package com.example.smartweed;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;

import java.io.File;
import java.util.List;

public class TrashFragment extends Fragment {

    private TrashManager trashManager;

    private LinearLayout trashItemsContainer;
    private LinearLayout emptyState;
    private TextView tvTrashCount;
    private Button buttonEmptyTrash;
    private AlertDialog activeDialog; // für dismiss in onDestroyView

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trash, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        trashManager = new TrashManager(requireContext());

        trashItemsContainer = view.findViewById(R.id.trashItemsContainer);
        emptyState = view.findViewById(R.id.emptyState);
        tvTrashCount = view.findViewById(R.id.tvTrashCount);
        buttonEmptyTrash = view.findViewById(R.id.buttonEmptyTrash);

        // Auto-Cleanup beim Oeffnen (im Hintergrund, dann Liste aktualisieren)
        runInBackground(() -> trashManager.autoCleanup(), () -> { });

        // Papierkorb leeren Button (auch das Zaehlen liest die Metadaten-Datei
        // -> im Hintergrund, erst danach den Dialog zeigen)
        buttonEmptyTrash.setOnClickListener(v -> new Thread(() -> {
            final int count = trashManager.getTrashCount();
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (count == 0) {
                    Toast.makeText(requireContext(), R.string.trash_already_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                activeDialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.trash_empty_confirm_title)
                        .setMessage(getResources().getQuantityString(
                                R.plurals.trash_empty_confirm_message, count, count))
                        .setPositiveButton(R.string.trash_delete_permanently, (d, w) ->
                                runInBackground(() -> trashManager.emptyTrash(), () ->
                                        Toast.makeText(requireContext(), R.string.trash_emptied, Toast.LENGTH_SHORT).show()))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start());

        refreshTrashList();
    }

    /** Führt eine Papierkorb-Operation im Hintergrund aus und aktualisiert danach die Liste */
    private void runInBackground(Runnable work, Runnable onDone) {
        new Thread(() -> {
            work.run();
            // getActivity() EINMAL holen statt isAdded()+requireActivity():
            // zwischen Check und Aufruf könnte das Fragment detached werden
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                onDone.run();
                refreshTrashList();
            });
        }).start();
    }

    private void refreshTrashList() {
        // Metadaten-Datei + exists()-Checks pro Eintrag laufen im Hintergrund
        new Thread(() -> {
            final List<TrashManager.TrashItem> items = trashManager.getTrashItems();
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || trashItemsContainer == null) return;
                trashItemsContainer.removeAllViews();
                if (items.isEmpty()) {
                    emptyState.setVisibility(View.VISIBLE);
                    trashItemsContainer.setVisibility(View.GONE);
                    buttonEmptyTrash.setVisibility(View.GONE);
                    tvTrashCount.setText(R.string.trash_no_items);
                } else {
                    emptyState.setVisibility(View.GONE);
                    trashItemsContainer.setVisibility(View.VISIBLE);
                    buttonEmptyTrash.setVisibility(View.VISIBLE);
                    tvTrashCount.setText(getResources().getQuantityString(
                            R.plurals.trash_item_count, items.size(), items.size()));

                    for (TrashManager.TrashItem item : items) {
                        addTrashItemView(item);
                    }
                }
            });
        }).start();
    }

    private void addTrashItemView(TrashManager.TrashItem item) {
        View itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_trash, trashItemsContainer, false);

        ImageView ivThumb = itemView.findViewById(R.id.ivTrashThumb);
        TextView tvName = itemView.findViewById(R.id.tvTrashItemName);
        TextView tvDate = itemView.findViewById(R.id.tvTrashItemDate);
        TextView tvSize = itemView.findViewById(R.id.tvTrashItemSize);
        Button btnRestore = itemView.findViewById(R.id.btnRestore);
        Button btnDelete = itemView.findViewById(R.id.btnDeletePermanently);

        // Info setzen (v1.9: Ordner-Einträge = ganze Session/Analyse mit Bildanzahl)
        final String displayName = item.isDirectory
                ? getResources().getQuantityString(R.plurals.trash_folder_item,
                        item.imageCount, item.imageCount, item.originalName)
                : item.originalName;
        tvName.setText(displayName);
        tvDate.setText(getString(R.string.trash_deleted_on, item.getFormattedDate()));

        int daysLeft = item.getDaysRemaining();
        tvSize.setText(getString(R.string.trash_size_and_days, item.getFormattedSize(), daysLeft));

        // Thumbnail laden (bei Ordnern das erste Bild darin)
        File thumb = item.thumbnail;
        if (thumb != null && thumb.isFile()) {
            Glide.with(this)
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .signature(new ObjectKey(thumb.lastModified()))
                    .centerCrop()
                    .into(ivThumb);
        }

        // Fullscreen bei Klick aufs Bild
        ivThumb.setOnClickListener(v -> {
            if (thumb != null && thumb.isFile()) {
                SummaryFragment.FullscreenImageDialog.show(TrashFragment.this, thumb.getAbsolutePath());
            }
        });

        // Wiederherstellen
        btnRestore.setOnClickListener(v -> {
            activeDialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.trash_restore_confirm_title)
                    .setMessage(getString(R.string.trash_restore_confirm_message, displayName))
                    .setPositiveButton(R.string.trash_restore, (d, w) -> {
                        final boolean[] success = new boolean[1];
                        final android.content.Context appCtx = requireContext().getApplicationContext();
                        runInBackground(
                                () -> {
                                    File restored = trashManager.restoreFromTrashTo(item.trashName);
                                    success[0] = restored != null;
                                    // v1.9: Beim Löschen wurden die Galerie-Kopien
                                    // entfernt -> wiederhergestellte Ordner wieder
                                    // in die Galerie legen
                                    if (restored != null && restored.isDirectory()) {
                                        ResultStore.reexportToGallery(appCtx, restored);
                                    }
                                },
                                () -> {
                                    if (success[0]) {
                                        Toast.makeText(requireContext(),
                                                getString(R.string.trash_restored, displayName),
                                                Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(requireContext(),
                                                R.string.trash_restore_failed,
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        // Endgueltig loeschen (Rueckgabewert respektieren: bei Fehlschlag keinen
        // Erfolg vortaeuschen)
        btnDelete.setOnClickListener(v -> {
            activeDialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.trash_delete_confirm_title)
                    .setMessage(getString(R.string.trash_delete_confirm_message, displayName))
                    .setPositiveButton(R.string.trash_delete_permanently, (d, w) -> {
                        final boolean[] success = new boolean[1];
                        runInBackground(
                                () -> success[0] = trashManager.deletePermanently(item.trashName),
                                () -> Toast.makeText(requireContext(),
                                        success[0]
                                                ? getString(R.string.trash_deleted_permanently, displayName)
                                                : getString(R.string.trash_delete_failed),
                                        Toast.LENGTH_SHORT).show());
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        trashItemsContainer.addView(itemView);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Offenen Bestätigungsdialog schließen (sonst WindowLeak bei Rotation)
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }
}
