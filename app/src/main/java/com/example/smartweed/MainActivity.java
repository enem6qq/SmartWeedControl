package com.example.smartweed;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.smartweed.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    // Android <11: WRITE_EXTERNAL_STORAGE als Runtime-Permission anfordern
    private final ActivityResultLauncher<String> writePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this,
                            R.string.toast_storage_permission_needed,
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // Android 11+: MANAGE_EXTERNAL_STORAGE anfordern, damit Bilder in /Pictures/SmartWeed/ liegen
        requestStoragePermission();
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: MANAGE_EXTERNAL_STORAGE ueber Einstellungen anfordern
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this,
                        R.string.toast_allow_file_access,
                        Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            // Android 9-10: WRITE_EXTERNAL_STORAGE als Runtime-Permission anfordern
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_language) {
            showLanguageDialog();
            return true;
        }

        if (id == R.id.action_settings) {
            Navigation.findNavController(this, R.id.nav_host_fragment_content_main)
                    .navigate(R.id.settingsFragment);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showLanguageDialog() {
        String currentLang = LocaleHelper.getLanguage(this);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_language_selection);

        // Make dialog background transparent and set proper size
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            // Calculate dialog width as 85% of screen width, but at least 280dp
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            float density = getResources().getDisplayMetrics().density;
            int minWidth = (int) (280 * density); // 280dp minimum
            int dialogWidth = Math.max(minWidth, (int) (screenWidth * 0.85));

            dialog.getWindow().setLayout(
                    dialogWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            // Apply dim effect
            dialog.getWindow().setDimAmount(0.7f);
        }

        // Get views
        LinearLayout optionEnglish = dialog.findViewById(R.id.option_english);
        LinearLayout optionGerman = dialog.findViewById(R.id.option_german);
        LinearLayout optionFrench = dialog.findViewById(R.id.option_french);
        ImageView checkEnglish = dialog.findViewById(R.id.check_english);
        ImageView checkGerman = dialog.findViewById(R.id.check_german);
        ImageView checkFrench = dialog.findViewById(R.id.check_french);
        View btnCancel = dialog.findViewById(R.id.btn_cancel);

        // Set initial selection
        if (LocaleHelper.LANGUAGE_ENGLISH.equals(currentLang)) {
            optionEnglish.setBackgroundResource(R.drawable.bg_language_item_selected);
            checkEnglish.setVisibility(View.VISIBLE);
        } else if (LocaleHelper.LANGUAGE_FRENCH.equals(currentLang)) {
            optionFrench.setBackgroundResource(R.drawable.bg_language_item_selected);
            checkFrench.setVisibility(View.VISIBLE);
        } else {
            optionGerman.setBackgroundResource(R.drawable.bg_language_item_selected);
            checkGerman.setVisibility(View.VISIBLE);
        }

        optionEnglish.setOnClickListener(v -> applyLanguage(dialog, LocaleHelper.LANGUAGE_ENGLISH, currentLang));
        optionGerman.setOnClickListener(v -> applyLanguage(dialog, LocaleHelper.LANGUAGE_GERMAN, currentLang));
        optionFrench.setOnClickListener(v -> applyLanguage(dialog, LocaleHelper.LANGUAGE_FRENCH, currentLang));

        // Cancel button click
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** Wechselt die Sprache (falls anders als aktuell) und startet die Activity neu */
    private void applyLanguage(Dialog dialog, String newLang, String currentLang) {
        if (!newLang.equals(currentLang)) {
            LocaleHelper.setLocale(this, newLang);
            Toast.makeText(this, R.string.language_changed, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            recreate();
        } else {
            dialog.dismiss();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}
