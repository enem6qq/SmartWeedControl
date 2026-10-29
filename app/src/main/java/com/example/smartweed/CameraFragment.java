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
    // App-lebenslanger Executor für Import-Kopien & Galerie-Export: bewusst
    // STATISCH und nie heruntergefahren. Ein per-Fragment-Executor mit
    // shutdown() in onDestroyView crashte reproduzierbar: der
    // takePicture-Callback läuft auf dem Main-Executor des App-Context und
    // feuert auch NACH Zerstörung der View — execute() auf dem bereits
    // heruntergefahrenen Executor warf eine RejectedExecutionException auf
    // dem Main-Thread (Foto aufnehmen + sofort zurück navigieren). Der
    // Galerie-Export soll in dem Fall ja gerade noch fertig laufen.
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private android.app.AlertDialog activeDialog; // für dismiss in onDestroyView

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
                    // Mehrfachauswahl kommt als ClipData, Einzelauswahl als getData()
                    Intent data = result.getData();
                    java.util.List<Uri> uris = new java.util.ArrayList<>();
                    android.content.ClipData clip = data.getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            Uri u = clip.getItemAt(i).getUri();
                            if (u != null && !uris.contains(u)) uris.add(u);
                        }
                    }
                    if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
                    if (!uris.isEmpty()) askImportTarget(uris);
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

        // Session-Name mit lesbarem Timestamp (minutengenau: zwei kurz
        // aufeinanderfolgende Besuche der Kamera-Seite landen in derselben Session).
        // Locale.US: Dateisystem-Namen dürfen nicht von locale-abhängigen Ziffern
        // abhängen. Über savedInstanceState gerettet, damit eine Rotation über
        // eine Minutengrenze die Aufnahme nicht in zwei Sessions splittet.
        // Der sessionDirName-null-Guard deckt zusätzlich den BACK-STACK-Fall ab:
        // Beim Zurückkehren von der Analyse-Seite läuft onViewCreated erneut mit
        // savedInstanceState == null, die Fragment-Instanz (und damit das Feld)
        // lebt aber weiter — ohne Guard bekämen die Nachher-Fotos eine NEUE
        // Session und die Analyse sähe die Vorher-Bilder nicht mehr.
        if (savedInstanceState != null && savedInstanceState.getString(STATE_SESSION_NAME) != null) {
            sessionDirName = savedInstanceState.getString(STATE_SESSION_NAME);
        } else if (sessionDirName == null) {
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
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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

            } catch (ExecutionException | InterruptedException | IllegalArgumentException
                     | androidx.camera.core.CameraInfoUnavailableException e) {
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
        // Präfix vorher_/nachher_ statt photo_: Auch außerhalb des Ordners (z. B.
        // per Messenger geteilt) bleibt erkennbar, welcher Zeitpunkt es ist.
        String filename = subfolder + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";

        File targetDir = isBefore ? beforeDir : afterDir;
        if (!targetDir.exists()) targetDir.mkdirs();
        File photoFile = new File(targetDir, filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        String label = getString(isBefore ? R.string.label_before : R.string.label_after);
        // Application-Context vorab cachen: der Callback feuert asynchron und darf
        // nicht auf requireContext() eines evtl. schon abgelösten Fragments zugreifen.
        final Context appContext = requireContext().getApplicationContext();
        final String toastText = getString(R.string.toast_photo_saved, label,
                MediaExport.galleryPath(sessionDirName + "/" + subfolder));

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(appContext),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        // Foto zusätzlich in die Galerie exportieren (Hintergrund)
                        IO_EXECUTOR.execute(() -> MediaExport.exportToGallery(
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

    /** Ins Session-Schema einsortieren: Vorher- oder Nachher-Bild? Bei
     *  Mehrfachauswahl gilt die Wahl für alle gewählten Bilder. */
    private void askImportTarget(java.util.List<Uri> uris) {
        String title = uris.size() > 1
                ? getResources().getQuantityString(R.plurals.import_target_title_multi, uris.size(), uris.size())
                : getString(R.string.import_target_title);
        activeDialog = new android.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setPositiveButton(R.string.label_before, (d, w) -> copyImportedImages(uris, beforeDir))
                .setNegativeButton(R.string.label_after, (d, w) -> copyImportedImages(uris, afterDir))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void copyImportedImages(java.util.List<Uri> uris, File targetDir) {
        // Dialog-Klicks können das Fragment theoretisch überleben — dann gäbe
        // requireContext() eine IllegalStateException statt eines sauberen Abbruchs
        if (!isAdded()) return;
        // Namen + Context auf dem UI-Thread ermitteln, die (potenziell mehrere MB
        // großen) Kopien dann im Hintergrund — sonst droht Jank/ANR.
        final ContentResolver resolver = requireContext().getContentResolver();
        final Context appContext = requireContext().getApplicationContext();
        final java.util.List<Uri> okUris = new java.util.ArrayList<>();
        final java.util.List<String> okNames = new java.util.ArrayList<>();
        int unsupported = 0;
        for (Uri uri : uris) {
            String picked = getFileName(uri);
            // HEIC/HEIF (Standard bei vielen iPhones und manchen Android-Kameras)
            // kann die Analyse (OpenCV) nicht lesen — und die Session-Ansicht zählt
            // nur lesbare Formate. Ohne Hinweis wäre das Bild "spurlos" importiert.
            String type = null;
            try { type = resolver.getType(uri); } catch (RuntimeException ignored) { }
            if (!SessionStore.isImageFile(picked) && type != null) {
                // Manche Anbieter liefern Namen ohne Endung ("IMG_1234") — dann
                // die Endung aus dem MIME-Typ ergänzen statt abzulehnen
                if (type.equals("image/jpeg")) picked += ".jpg";
                else if (type.equals("image/png")) picked += ".png";
                else if (type.equals("image/webp")) picked += ".webp";
                else if (type.equals("image/bmp")) picked += ".bmp";
            }
            if (!SessionStore.isImageFile(picked)) { unsupported++; continue; }
            okUris.add(uri);
            okNames.add(picked);
        }
        if (unsupported > 0) {
            Toast.makeText(appContext, R.string.toast_import_unsupported_format, Toast.LENGTH_LONG).show();
        }
        if (okUris.isEmpty()) return;

        final String gallerySub = sessionDirName + "/" + targetDir.getName();
        final String galleryPath = MediaExport.galleryPath(gallerySub);
        final String singleFmt = okUris.size() == 1
                ? getString(R.string.toast_image_imported, galleryPath, okNames.get(0)) : null;
        final android.content.res.Resources res = getResources();

        IO_EXECUTOR.execute(() -> {
            int copied = 0;
            int failed = 0;
            if (!targetDir.exists()) targetDir.mkdirs();
            for (int i = 0; i < okUris.size(); i++) {
                String name = okNames.get(i);
                try {
                    File destFile = new File(targetDir, name);
                    // Kollision vermeiden: existiert der Name schon, durchnummerieren
                    if (destFile.exists()) {
                        int dot = name.lastIndexOf('.');
                        String base = (dot > 0) ? name.substring(0, dot) : name;
                        String ext = (dot > 0) ? name.substring(dot) : "";
                        int n = 2;
                        do { destFile = new File(targetDir, base + "_" + n++ + ext); } while (destFile.exists());
                    }
                    try (InputStream inputStream = resolver.openInputStream(okUris.get(i));
                         OutputStream outputStream = new FileOutputStream(destFile)) {
                        if (inputStream == null) throw new java.io.IOException("openInputStream lieferte null");
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                    }
                    copied++;
                    // Auch importierte Bilder in den Session-Ordner der Galerie legen,
                    // damit dort ALLE Bilder eines Feldeinsatzes beisammen liegen.
                    MediaExport.exportToGallery(appContext, destFile, gallerySub);
                } catch (Exception e) {
                    failed++;
                    Log.e("Import", "Fehler beim Kopieren: " + name, e);
                }
            }
            final int fCopied = copied;
            final int fFailed = failed;
            ContextCompat.getMainExecutor(appContext).execute(() -> {
                if (fCopied > 0) {
                    String msg = (fCopied == 1 && singleFmt != null) ? singleFmt
                            : res.getQuantityString(R.plurals.toast_images_imported, fCopied, fCopied, galleryPath);
                    Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
                }
                if (fFailed > 0) {
                    Toast.makeText(appContext, R.string.toast_import_error, Toast.LENGTH_SHORT).show();
                }
            });
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
            } catch (RuntimeException e) {
                // Grant entzogen / Provider weg: Ersatzname statt Absturz
                Log.w("Import", "Dateiname nicht lesbar: " + uri, e);
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
        // view.post kann einen Frame nach Zerstörung der View feuern
        if (binding == null) return;
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
        // Offene Dialoge schließen (sonst WindowLeak bei Rotation).
        // IO_EXECUTOR läuft bewusst weiter — siehe Kommentar an der Deklaration.
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }
}
