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

        // Papierkorb leeren Button
        buttonEmptyTrash.setOnClickListener(v -> {
            int count = trashManager.getTrashCount();
            if (count == 0) {
                Toast.makeText(requireContext(), R.string.trash_already_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.trash_empty_confirm_title)
                    .setMessage(getString(R.string.trash_empty_confirm_message, count))
                    .setPositiveButton(R.string.trash_delete_permanently, (d, w) ->
                            runInBackground(() -> trashManager.emptyTrash(), () ->
                                    Toast.makeText(requireContext(), R.string.trash_emptied, Toast.LENGTH_SHORT).show()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        refreshTrashList();
    }

    /** Führt eine Papierkorb-Operation im Hintergrund aus und aktualisiert danach die Liste */
    private void runInBackground(Runnable work, Runnable onDone) {
        new Thread(() -> {
            work.run();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                onDone.run();
                refreshTrashList();
            });
        }).start();
    }

    private void refreshTrashList() {
        trashItemsContainer.removeAllViews();
        List<TrashManager.TrashItem> items = trashManager.getTrashItems();

        if (items.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            trashItemsContainer.setVisibility(View.GONE);
            buttonEmptyTrash.setVisibility(View.GONE);
            tvTrashCount.setText(R.string.trash_no_items);
        } else {
            emptyState.setVisibility(View.GONE);
            trashItemsContainer.setVisibility(View.VISIBLE);
            buttonEmptyTrash.setVisibility(View.VISIBLE);
            tvTrashCount.setText(getString(R.string.trash_item_count, items.size()));

            for (TrashManager.TrashItem item : items) {
                addTrashItemView(item);
            }
        }
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

        // Info setzen
        tvName.setText(item.originalName);
        tvDate.setText(getString(R.string.trash_deleted_on, item.getFormattedDate()));

        int daysLeft = item.getDaysRemaining();
        tvSize.setText(getString(R.string.trash_size_and_days, item.getFormattedSize(), daysLeft));

        // Thumbnail laden
        File trashFile = item.trashFile;
        if (trashFile != null && trashFile.exists()) {
            Glide.with(this)
                    .load(trashFile)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .signature(new ObjectKey(trashFile.lastModified()))
                    .centerCrop()
                    .into(ivThumb);
        }

        // Fullscreen bei Klick aufs Bild
        ivThumb.setOnClickListener(v -> {
            if (trashFile != null && trashFile.exists()) {
                SummaryFragment.FullscreenImageDialog.show(TrashFragment.this, trashFile.getAbsolutePath());
            }
        });

        // Wiederherstellen
        btnRestore.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.trash_restore_confirm_title)
                    .setMessage(getString(R.string.trash_restore_confirm_message, item.originalName))
                    .setPositiveButton(R.string.trash_restore, (d, w) -> {
                        final boolean[] success = new boolean[1];
                        runInBackground(
                                () -> success[0] = trashManager.restoreFromTrash(item.trashName),
                                () -> {
                                    if (success[0]) {
                                        Toast.makeText(requireContext(),
                                                getString(R.string.trash_restored, item.originalName),
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

        // Endgueltig loeschen
        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.trash_delete_confirm_title)
                    .setMessage(getString(R.string.trash_delete_confirm_message, item.originalName))
                    .setPositiveButton(R.string.trash_delete_permanently, (d, w) ->
                            runInBackground(
                                    () -> trashManager.deletePermanently(item.trashName),
                                    () -> Toast.makeText(requireContext(),
                                            getString(R.string.trash_deleted_permanently, item.originalName),
                                            Toast.LENGTH_SHORT).show()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        trashItemsContainer.addView(itemView);
    }
}
