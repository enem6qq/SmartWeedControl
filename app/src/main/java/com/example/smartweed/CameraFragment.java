package com.example.smartweed;

import android.Manifest;
import android.database.Cursor;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.media.MediaScannerConnection;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
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

import com.example.smartweed.databinding.FragmentCameraBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraFragment extends Fragment {

    private FragmentCameraBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private File publicImageDir;
    private File beforeDir;
    private File afterDir;
    private String sessionDirName; // Timestamp-basierter Unterordner für diese Session

    // Wartende Aktionen nach Permission-Grant (Android <11)
    private String pendingPhotoSubfolder;
    private Uri pendingImportUri;

    // ===== RUNTIME-PERMISSION für Medien (Android 13+ vs. älter) =====
    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final ActivityResultLauncher<String> mediaPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    navigateToAnalysis();
                } else {
                    Toast.makeText(requireContext(), R.string.toast_media_access_denied, Toast.LENGTH_LONG).show();
                }
            });

    // Android <11: WRITE_EXTERNAL_STORAGE Runtime-Permission
    private final ActivityResultLauncher<String> writePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    ensureDirectories();
                    if (pendingPhotoSubfolder != null) {
                        String sub = pendingPhotoSubfolder;
                        pendingPhotoSubfolder = null;
                        takePhoto(sub);
                    } else if (pendingImportUri != null) {
                        Uri uri = pendingImportUri;
                        pendingImportUri = null;
                        importImageToSmartWeedFolder(uri);
                    }
                } else {
                    pendingPhotoSubfolder = null;
                    pendingImportUri = null;
                    Toast.makeText(requireContext(),
                            R.string.toast_storage_permission_save,
                            Toast.LENGTH_LONG).show();
                }
            });

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
                    importImageToSmartWeedFolder(uri);
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

        // Session-Ordner mit lesbarem Timestamp erstellen (Minutengenau: zwei kurz
        // aufeinanderfolgende Besuche der Kamera-Seite landen in derselben Session)
        sessionDirName = new SimpleDateFormat(SessionStore.SESSION_NAME_PATTERN, Locale.getDefault()).format(new Date());
        File baseDir = SessionStore.getBaseDir();
        publicImageDir = new File(baseDir, sessionDirName);

        // Getrennte Unterordner für Vorher- und Nachher-Bilder
        beforeDir = new File(publicImageDir, "vorher");
        afterDir = new File(publicImageDir, "nachher");

        // Ordner nur erstellen wenn Schreibberechtigung vorhanden (Android <11 braucht Runtime-Permission)
        if (hasWritePermission()) {
            ensureDirectories();
        }

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
        if (hasPermissions()) {
            startCameraPreview();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }

        // Foto Vorher aufnehmen
        binding.buttonTakePhotoBefore.setOnClickListener(v -> {
            takePhoto("vorher");
        });

        // Foto Nachher aufnehmen
        binding.buttonTakePhotoAfter.setOnClickListener(v -> {
            takePhoto("nachher");
        });

        // Bild importieren (WhatsApp/Dateien etc.)
        binding.buttonImportImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            pickImageLauncher.launch(Intent.createChooser(intent, getString(R.string.chooser_pick_image)));
        });

        // Analyse starten -> zum AnalysisFragment navigieren (Pfad mitgeben)
        if (binding.buttonStartAnalysis != null) {
            binding.buttonStartAnalysis.setOnClickListener(v -> {
                if (!hasMediaPermission()) {
                    // für Android 13+ separat READ_MEDIA_IMAGES anfragen
                    if (Build.VERSION.SDK_INT >= 33) {
                        mediaPermLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
                    } else {
                        mediaPermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                    }
                    return;
                }
                navigateToAnalysis();
            });
        }

        // Intro-Animation (Preview + Buttons sanft einblenden)
        prepForIntro(binding.previewCard, -18f);
        prepForIntro(binding.buttonTakePhotoBefore, 22f);
        prepForIntro(binding.buttonTakePhotoAfter, 24f);
        prepForIntro(binding.buttonImportImage, 26f);
        prepForIntro(binding.buttonStartAnalysis, 30f);
        view.post(this::runIntroAnimations);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().build();

                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /** Navigiert zur Analyse-Seite und übergibt den Session-Bilderordner */
    private void navigateToAnalysis() {
        Bundle args = new Bundle();
        args.putString("imageDir", publicImageDir.getAbsolutePath());
        androidx.navigation.NavController nav =
                androidx.navigation.Navigation.findNavController(requireView());
        nav.navigate(R.id.action_CameraFragment_to_AnalysisFragment, args);
    }

    /** Prüft ob Schreibzugriff auf externen Speicher vorhanden ist */
    private boolean hasWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: MANAGE_EXTERNAL_STORAGE wird in MainActivity angefragt
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Erstellt die Session-Ordner (erneut), z.B. nach Permission-Grant */
    private void ensureDirectories() {
        if (!publicImageDir.exists()) publicImageDir.mkdirs();
        if (!beforeDir.exists()) beforeDir.mkdirs();
        if (!afterDir.exists()) afterDir.mkdirs();
    }

    private void takePhoto(String subfolder) {
        if (imageCapture == null) return;

        // Android <11: Schreibberechtigung prüfen
        if (!hasWritePermission()) {
            pendingPhotoSubfolder = subfolder;
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        // Millisekunden im Namen: zwei Fotos in derselben Sekunde überschreiben sich nicht
        String filename = "photo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".jpg";

        // Direkt in den app-eigenen Session-Unterordner speichern (vorher/nachher)
        File targetDir = "vorher".equals(subfolder) ? beforeDir : afterDir;
        if (!targetDir.exists()) targetDir.mkdirs();
        File photoFile = new File(targetDir, filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        String label = getString("vorher".equals(subfolder) ? R.string.label_before : R.string.label_after);
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        // MediaScanner benachrichtigen, damit das Bild sofort in Galerie/Picker sichtbar ist
                        MediaScannerConnection.scanFile(requireContext(),
                                new String[]{photoFile.getAbsolutePath()},
                                new String[]{"image/jpeg"},
                                null);
                        binding.buttonTakePhotoBefore.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        Toast.makeText(requireContext(),
                                getString(R.string.toast_photo_saved, label,
                                        "SmartWeed/" + sessionDirName + "/" + subfolder),
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(requireContext(), R.string.toast_photo_save_error, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void importImageToSmartWeedFolder(Uri uri) {
        // Android <11: Schreibberechtigung prüfen
        if (!hasWritePermission()) {
            pendingImportUri = uri;
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        // Ins Session-Schema einsortieren: Vorher- oder Nachher-Bild?
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
            InputStream inputStream = resolver.openInputStream(uri);

            if (!targetDir.exists()) targetDir.mkdirs();
            File destFile = new File(targetDir, name);
            // Kollision vermeiden: existiert der Name schon, eindeutig machen
            if (destFile.exists()) {
                int dot = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext = (dot > 0) ? name.substring(dot) : "";
                destFile = new File(targetDir, base + "_" + System.currentTimeMillis() + ext);
            }
            FileOutputStream outputStream = new FileOutputStream(destFile);

            byte[] buffer = new byte[4096];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.close();
            inputStream.close();

            // MediaScanner benachrichtigen, damit das Bild sofort in Galerie/Picker sichtbar ist
            MediaScannerConnection.scanFile(requireContext(),
                    new String[]{destFile.getAbsolutePath()},
                    new String[]{"image/jpeg"},
                    null);

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
