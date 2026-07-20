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

    private static final String STATE_SESSION_NAME = "sessionDirName";

    private FragmentCameraBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService ioExecutor; // Import-Kopien & Galerie-Export im Hintergrund

    private File sessionDir;
    private File beforeDir;
    private File afterDir;
    private String sessionDirName; // Timestamp-basierter Unterordner für diese Session

    // Kamera-Permission
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCameraPreview();
                } else if (isAdded()) {
                    // Ohne Feedback bliebe nur ein schwarzer Preview zurück
                    Toast.makeText(requireContext(),
                            R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
                }
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

        ioExecutor = Executors.newSingleThreadExecutor();

        // Session-Name mit lesbarem Timestamp (minutengenau: zwei kurz
        // aufeinanderfolgende Besuche der Kamera-Seite landen in derselben Session).
        // Locale.US: Dateisystem-Namen dürfen nicht von locale-abhängigen Ziffern
        // abhängen. Über savedInstanceState gerettet, damit eine Rotation über
        // eine Minutengrenze die Aufnahme nicht in zwei Sessions splittet.
        if (savedInstanceState != null && savedInstanceState.getString(STATE_SESSION_NAME) != null) {
            sessionDirName = savedInstanceState.getString(STATE_SESSION_NAME);
        } else {
            sessionDirName = new SimpleDateFormat(SessionStore.SESSION_NAME_PATTERN, Locale.US).format(new Date());
        }
        sessionDir = new File(SessionStore.getBaseDir(requireContext()), sessionDirName);
        beforeDir = new File(sessionDir, SessionStore.BEFORE_DIR_NAME);
        afterDir = new File(sessionDir, SessionStore.AFTER_DIR_NAME);
        // Ordner werden erst beim ersten Foto/Import angelegt (lazy) — sonst
        // hinterlässt jeder Besuch der Kamera-Seite eine leere Session.

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

                // Das Manifest erlaubt die Installation ohne Kamera
                // (uses-feature required=false) — ohne diesen Guard würde
                // bindToLifecycle auf solchen Geräten crashen.
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Toast.makeText(requireContext(),
                            R.string.camera_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }

                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().build();

                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);

            } catch (ExecutionException | InterruptedException | IllegalArgumentException e) {
                Log.e("Camera", "Kamera-Start fehlgeschlagen", e);
                if (isAdded()) {
                    Toast.makeText(requireContext(),
                            R.string.camera_unavailable, Toast.LENGTH_LONG).show();
                }
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

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SESSION_NAME, sessionDirName);
    }

    private void takePhoto(String subfolder) {
        if (imageCapture == null) return;

        final boolean isBefore = SessionStore.BEFORE_DIR_NAME.equals(subfolder);
        // Millisekunden im Namen: zwei Fotos in derselben Sekunde überschreiben sich
        // nicht (Locale.US für locale-unabhängige Dateisystem-Namen)
        String filename = "photo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";

        File targetDir = isBefore ? beforeDir : afterDir;
        if (!targetDir.exists()) targetDir.mkdirs();
        File photoFile = new File(targetDir, filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        String label = getString(isBefore ? R.string.label_before : R.string.label_after);
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
                        ioExecutor.execute(() -> MediaExport.exportToGallery(
                                appContext, photoFile, sessionDirName + "/" + subfolder));

                        Toast.makeText(appContext, toastText, Toast.LENGTH_SHORT).show();
                        // UI-Feedback nur, wenn die View noch lebt — auf dem Button,
                        // der tatsächlich gedrückt wurde
                        if (binding != null && isAdded()) {
                            (isBefore ? binding.buttonTakePhotoBefore : binding.buttonTakePhotoAfter)
                                    .performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
        // Name + Context auf dem UI-Thread ermitteln, die (potenziell mehrere MB
        // große) Kopie dann im Hintergrund — sonst droht Jank/ANR.
        final ContentResolver resolver = requireContext().getContentResolver();
        final Context appContext = requireContext().getApplicationContext();
        final String name = getFileName(uri);
        final String importedFmt = getString(R.string.toast_image_imported,
                "SmartWeed/" + sessionDirName + "/" + targetDir.getName(), name);

        ioExecutor.execute(() -> {
            try {
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

                ContextCompat.getMainExecutor(appContext).execute(() ->
                        Toast.makeText(appContext, importedFmt, Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                Log.e("Import", "Fehler beim Kopieren", e);
                ContextCompat.getMainExecutor(appContext).execute(() ->
                        Toast.makeText(appContext, R.string.toast_import_error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = "imported_" + System.currentTimeMillis() + ".jpg";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String display = cursor.getString(index);
                        if (display != null && !display.isEmpty()) result = display;
                    }
                }
            }
        }
        // DISPLAY_NAME stammt von einem fremden Content-Provider: Pfadanteile
        // ("../", "/") entschärfen, sonst könnte ein präparierter Name außerhalb
        // des Zielordners schreiben (Path Traversal).
        result = new File(result).getName().replace("..", "_");
        if (result.isEmpty() || ".".equals(result)) {
            result = "imported_" + System.currentTimeMillis() + ".jpg";
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
        // Laufende Import-/Export-Aufgaben zu Ende bringen, dann herunterfahren
        if (ioExecutor != null) ioExecutor.shutdown();
    }
}
