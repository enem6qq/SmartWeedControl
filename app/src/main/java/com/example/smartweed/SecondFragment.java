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

import com.example.smartweed.databinding.FragmentSecondBinding;
import com.google.common.util.concurrent.ListenableFuture;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.PyException;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecondFragment extends Fragment {

    private FragmentSecondBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private ExecutorService bgExecutor;

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
                    runPythonAnalysis();
                } else {
                    Toast.makeText(requireContext(), "Bilder-Zugriff verweigert", Toast.LENGTH_LONG).show();
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
                            "Speicher-Berechtigung wird zum Speichern benötigt",
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
        binding = FragmentSecondBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraExecutor = Executors.newSingleThreadExecutor();
        bgExecutor = Executors.newSingleThreadExecutor();

        // Session-Ordner mit Timestamp erstellen (jede Kamera-Session bekommt eigenen Ordner)
        sessionDirName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartWeed");
        publicImageDir = new File(baseDir, sessionDirName);

        // Getrennte Unterordner für Vorher- und Nachher-Bilder
        beforeDir = new File(publicImageDir, "vorher");
        afterDir = new File(publicImageDir, "nachher");

        // Ordner nur erstellen wenn Schreibberechtigung vorhanden (Android <11 braucht Runtime-Permission)
        if (hasWritePermission()) {
            ensureDirectories();
        }

        // Ordner dem MediaScanner melden, damit sie sofort im Picker/Galerie erscheinen
        MediaScannerConnection.scanFile(requireContext(),
                new String[]{
                        baseDir.getAbsolutePath(),
                        publicImageDir.getAbsolutePath(),
                        beforeDir.getAbsolutePath(),
                        afterDir.getAbsolutePath()
                }, null, null);

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
            pickImageLauncher.launch(Intent.createChooser(intent, "Bild auswählen"));
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
                Bundle args = new Bundle();
                args.putString("imageDir", publicImageDir.getAbsolutePath());
                androidx.navigation.NavController nav =
                        androidx.navigation.Navigation.findNavController(requireView());
                nav.navigate(R.id.action_SecondFragment_to_AnalysisFragment, args);
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

    /** Prüft ob Schreibzugriff auf externen Speicher vorhanden ist */
    private boolean hasWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return true; // Android 11+: wird über MANAGE_EXTERNAL_STORAGE in MainActivity behandelt
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

        String filename = "photo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg";

        // Direkt in den app-eigenen Session-Unterordner speichern (vorher/nachher)
        File targetDir = "vorher".equals(subfolder) ? beforeDir : afterDir;
        if (!targetDir.exists()) targetDir.mkdirs();
        File photoFile = new File(targetDir, filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        String label = "vorher".equals(subfolder) ? "Vorher" : "Nachher";
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
                                label + "-Bild gespeichert in SmartWeed/" + sessionDirName + "/" + subfolder,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(requireContext(), "Fehler beim Speichern", Toast.LENGTH_SHORT).show();
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

        try {
            ContentResolver resolver = requireContext().getContentResolver();
            String name = getFileName(uri);
            InputStream inputStream = resolver.openInputStream(uri);

            // Direkt in den app-eigenen Session-Ordner speichern
            if (!publicImageDir.exists()) publicImageDir.mkdirs();
            File destFile = new File(publicImageDir, name);
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
                    "Bild importiert in SmartWeed/" + sessionDirName + ": " + name,
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Fehler beim Import", Toast.LENGTH_SHORT).show();
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

    // ==============================
    // Python-Analyse (Ordner)
    // ==============================
    private void runPythonAnalysis() {
        if (!publicImageDir.exists()) {
            Toast.makeText(requireContext(), "Ordner fehlt: " + publicImageDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            Log.e("SmartWeedAnalysis", "Folder does not exist: " + publicImageDir.getAbsolutePath());
            return;
        }
        File[] imgs = publicImageDir.listFiles(f ->
                f.isFile() && hasAnySuffix(f.getName(), ".jpg", ".jpeg", ".png", ".bmp", ".webp"));
        int count = (imgs == null) ? 0 : imgs.length;
        if (count == 0) {
            Toast.makeText(requireContext(), "Keine Bilder in " + publicImageDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            Log.w("SmartWeedAnalysis", "No images found in folder: " + publicImageDir.getAbsolutePath());
            return;
        }

        Toast.makeText(requireContext(), "Analyse gestartet …", Toast.LENGTH_SHORT).show();
        Log.i("SmartWeedAnalysis", "Starting analysis in: " + publicImageDir.getAbsolutePath() + " (#images=" + count + ")");

        bgExecutor.execute(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(requireContext()));
                }
                Python py = Python.getInstance();

                try {
                    PyObject sys = py.getModule("sys");
                    Log.i("SmartWeedAnalysis", "Python version: " + sys.get("version").toString());
                    Log.i("SmartWeedAnalysis", "sys.path: " + sys.get("path").toString());
                } catch (Exception ignore) {}

                PyObject module = py.getModule("analysis");

                try { module.callAttr("chaquopy_probe"); } catch (Exception ignored) {}

                PyObject result = module.callAttr("analyze_folder", publicImageDir.getAbsolutePath());

                String json = (result == null) ? "" : result.toString();
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Analyse fertig", Toast.LENGTH_SHORT).show();
                    Log.i("SmartWeedAnalysis", json);
                });

            } catch (PyException pyEx) {
                String msg = firstLine(pyEx.getMessage());
                Log.e("SmartWeedAnalysis", "Python error: " + pyEx.getMessage(), pyEx);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Python-Fehler: " + msg, Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                Log.e("SmartWeedAnalysis", "Analyse-Fehler JAVA", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Analyse fehlgeschlagen: " + firstLine(e.getMessage()), Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    private static boolean hasAnySuffix(String name, String... exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String e : exts) if (lower.endsWith(e)) return true;
        return false;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0) ? s.substring(0, i) : s;
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
        if (bgExecutor != null) bgExecutor.shutdown();
    }
}
