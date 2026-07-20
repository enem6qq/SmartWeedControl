package com.example.smartweed;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.example.smartweed.databinding.FragmentCameraBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kamera-Seite: Vorher-/Nachher-Fotos aufnehmen oder importieren.
 * Die Bilder liegen im app-eigenen Speicher (keine Speicher-Berechtigung
 * nötig); Fotos werden zusätzlich in die Galerie exportiert.
 */
public class CameraFragment extends Fragment {

    private FragmentCameraBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private File sessionDir;
    private File beforeDir;
    private File afterDir;
    private String sessionDirName; // Timestamp-basierter Unterordner für diese Session

    // Kamera-Permission
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) startCameraPreview();
            });

    // Import-Dialog
    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) askImportTarget(uri);
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCameraBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Session-Ordner mit lesbarem Timestamp erstellen (minutengenau: zwei kurz
        // aufeinanderfolgende Besuche der Kamera-Seite landen in derselben Session)
        sessionDirName = new SimpleDateFormat(SessionStore.SESSION_NAME_PATTERN, Locale.getDefault()).format(new Date());
        sessionDir = new File(SessionStore.getBaseDir(requireContext()), sessionDirName);
        beforeDir = new File(sessionDir, SessionStore.BEFORE_DIR_NAME);
        afterDir = new File(sessionDir, SessionStore.AFTER_DIR_NAME);
        ensureDirectories();

        // Button-Elevation & kleine Press-Animation
        float elev = getResources().getDisplayMetrics().density * 3f;
        ViewCompat.setElevation(binding.buttonTakePhotoBefore, elev);
        ViewCompat.setElevation(binding.buttonTakePhotoAfter, elev);
        ViewCompat.setElevation(binding.buttonImportImage, elev);
        ViewCompat.setElevation(binding.buttonStartAnalysis, elev);

        setPressFeedback(binding.buttonTakePhotoBefore);
        setPressFeedback(binding.buttonTakePhotoAfter);
        setPressFeedback(binding.buttonImportImage);
        setPressFeedback(binding.buttonStartAnalysis);

        // Kamera-Preview automatisch starten
        if (hasCameraPermission()) {
            startCameraPreview();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }

        binding.buttonTakePhotoBefore.setOnClickListener(v -> takePhoto(SessionStore.BEFORE_DIR_NAME));
        binding.buttonTakePhotoAfter.setOnClickListener(v -> takePhoto(SessionStore.AFTER_DIR_NAME));

        // Bild importieren (WhatsApp/Dateien etc.) — SAF braucht keine Berechtigung
        binding.buttonImportImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            pickImageLauncher.launch(Intent.createChooser(intent, getString(R.string.chooser_pick_image)));
        });

        // Analyse starten -> zum AnalysisFragment navigieren (Pfad mitgeben)
        binding.buttonStartAnalysis.setOnClickListener(v -> navigateToAnalysis());

        // Intro-Animation (Preview + Buttons sanft einblenden)
        prepForIntro(binding.previewCard, -18f);
        prepForIntro(binding.buttonTakePhotoBefore, 22f);
        prepForIntro(binding.buttonTakePhotoAfter, 24f);
        prepForIntro(binding.buttonImportImage, 26f);
        prepForIntro(binding.buttonStartAnalysis, 30f);
        view.post(this::runIntroAnimations);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            // Lifecycle-Guard: Der Provider kann fertig werden, nachdem der Nutzer
            // die Seite bereits verlassen hat — dann ist binding null.
            if (binding == null || !isAdded()) return;
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().build();

                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Camera", "Kamera-Start fehlgeschlagen", e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /** Navigiert zur Analyse-Seite und übergibt den Session-Bilderordner */
    private void navigateToAnalysis() {
        NavController nav = Navigation.findNavController(requireView());
        // Doppelklick-/Doppelnavigations-Guard: nur navigieren, wenn wir noch
        // wirklich auf der Kamera-Seite stehen.
        if (nav.getCurrentDestination() == null
                || nav.getCurrentDestination().getId() != R.id.CameraFragment) {
            return;
        }
        Bundle args = new Bundle();
        args.putString("imageDir", sessionDir.getAbsolutePath());
        nav.navigate(R.id.action_CameraFragment_to_AnalysisFragment, args);
    }

    /** Erstellt die Session-Ordner (app-eigener Speicher, keine Berechtigung nötig) */
    private void ensureDirectories() {
        if (!sessionDir.exists()) sessionDir.mkdirs();
        if (!beforeDir.exists()) beforeDir.mkdirs();
        if (!afterDir.exists()) afterDir.mkdirs();
    }

    private void takePhoto(String subfolder) {
        if (imageCapture == null) return;

        // Millisekunden im Namen: zwei Fotos in derselben Sekunde überschreiben sich nicht
        String filename = "photo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".jpg";

        File targetDir = SessionStore.BEFORE_DIR_NAME.equals(subfolder) ? beforeDir : afterDir;
        if (!targetDir.exists()) targetDir.mkdirs();
        File photoFile = new File(targetDir, filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        String label = getString(SessionStore.BEFORE_DIR_NAME.equals(subfolder)
                ? R.string.label_before : R.string.label_after);
        // Application-Context vorab cachen: der Callback feuert asynchron und darf
        // nicht auf requireContext() eines evtl. schon abgelösten Fragments zugreifen.
        final Context appContext = requireContext().getApplicationContext();
        final String toastText = getString(R.string.toast_photo_saved, label,
                "SmartWeed/" + sessionDirName + "/" + subfolder);

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(appContext),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        // Foto zusätzlich in die Galerie exportieren (Hintergrund)
                        new Thread(() -> MediaExport.exportToGallery(
                                appContext, photoFile, sessionDirName + "/" + subfolder)).start();

                        Toast.makeText(appContext, toastText, Toast.LENGTH_SHORT).show();
                        // UI-Feedback nur, wenn die View noch lebt
                        if (binding != null && isAdded()) {
                            binding.buttonTakePhotoBefore.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e("Camera", "Foto speichern fehlgeschlagen", exception);
                        Toast.makeText(appContext, R.string.toast_photo_save_error, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** Ins Session-Schema einsortieren: Vorher- oder Nachher-Bild? */
    private void askImportTarget(Uri uri) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_target_title)
                .setPositiveButton(R.string.label_before, (d, w) -> copyImportedImage(uri, beforeDir))
                .setNegativeButton(R.string.label_after, (d, w) -> copyImportedImage(uri, afterDir))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void copyImportedImage(Uri uri, File targetDir) {
        try {
            ContentResolver resolver = requireContext().getContentResolver();
            String name = getFileName(uri);

            if (!targetDir.exists()) targetDir.mkdirs();
            File destFile = new File(targetDir, name);
            // Kollision vermeiden: existiert der Name schon, eindeutig machen
            if (destFile.exists()) {
                int dot = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext = (dot > 0) ? name.substring(dot) : "";
                destFile = new File(targetDir, base + "_" + System.currentTimeMillis() + ext);
            }

            try (InputStream inputStream = resolver.openInputStream(uri);
                 OutputStream outputStream = new FileOutputStream(destFile)) {
                if (inputStream == null) throw new java.io.IOException("openInputStream lieferte null");
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }

            Toast.makeText(requireContext(),
                    getString(R.string.toast_image_imported,
                            "SmartWeed/" + sessionDirName + "/" + targetDir.getName(), destFile.getName()),
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.toast_import_error, Toast.LENGTH_SHORT).show();
            Log.e("Import", "Fehler beim Kopieren", e);
        }
    }

    private String getFileName(Uri uri) {
        String result = "imported_" + System.currentTimeMillis() + ".jpg";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            }
        }
        return result;
    }

    // ---- Kleine Helfer für Animationen ----
    private final DecelerateInterpolator easeOut = new DecelerateInterpolator();

    private void prepForIntro(@NonNull View v, float translateYdp) {
        float dy = translateYdp * getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
    }

    private void runIntroAnimations() {
        animateIn(binding.previewCard, 60);
        animateIn(binding.buttonTakePhotoBefore, 120);
        animateIn(binding.buttonTakePhotoAfter, 160);
        animateIn(binding.buttonImportImage, 200);
        animateIn(binding.buttonStartAnalysis, 240);
    }

    private void animateIn(@NonNull View v, long delay) {
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(360)
                .setInterpolator(easeOut)
                .start();
    }

    private void setPressFeedback(@NonNull View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).setInterpolator(easeOut).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(easeOut).start();
                    break;
            }
            return false;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
