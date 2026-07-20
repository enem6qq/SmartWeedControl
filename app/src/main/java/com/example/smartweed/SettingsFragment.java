package com.example.smartweed;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.Locale;

/**
 * Experten-Einstellungen für die Bildanalyse und den CSC-Zielbereich.
 * Erreichbar über den Menüpunkt "Einstellungen" in der Toolbar.
 *
 * Die Werte sind nur nach Admin-Anmeldung änderbar (siehe {@link AdminAuth}),
 * damit die Analyse-Parameter nicht versehentlich verstellt werden. Ansehen
 * ist für alle möglich.
 */
public class SettingsFragment extends Fragment {

    private AnalysisSettings settings;

    private EditText etMinSize;
    private EditText etHueLow;
    private EditText etHueHigh;
    private EditText etCscLow;
    private EditText etCscHigh;
    private RadioGroup rgMethod;
    private Button save;
    private Button reset;
    private TextView tvAdminLockInfo;
    private Button btnAdminLogin;
    private Button btnAdminLogout;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        settings = new AnalysisSettings(requireContext());

        etMinSize = view.findViewById(R.id.etMinSize);
        etHueLow  = view.findViewById(R.id.etHueLow);
        etHueHigh = view.findViewById(R.id.etHueHigh);
        etCscLow  = view.findViewById(R.id.etCscLow);
        etCscHigh = view.findViewById(R.id.etCscHigh);
        rgMethod  = view.findViewById(R.id.rgMethod);
        save  = view.findViewById(R.id.buttonSaveSettings);
        reset = view.findViewById(R.id.buttonResetSettings);
        tvAdminLockInfo = view.findViewById(R.id.tvAdminLockInfo);
        btnAdminLogin = view.findViewById(R.id.btnAdminLogin);
        btnAdminLogout = view.findViewById(R.id.btnAdminLogout);

        populateFields();

        save.setOnClickListener(v -> saveFields());

        reset.setOnClickListener(v -> {
            settings.reset();
            populateFields();
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
        });

        btnAdminLogin.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), Login.class)));

        btnAdminLogout.setOnClickListener(v -> {
            AdminAuth.logout(requireContext());
            applyAdminState();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Nach Rückkehr vom Login-Screen den Freischalt-Zustand aktualisieren
        applyAdminState();
    }

    /** Schaltet die Eingabefelder je nach Admin-Status frei oder sperrt sie */
    private void applyAdminState() {
        boolean admin = AdminAuth.isAdmin(requireContext());

        setInputsEnabled(admin);
        save.setEnabled(admin);
        reset.setEnabled(admin);
        save.setAlpha(admin ? 1f : 0.5f);
        reset.setAlpha(admin ? 1f : 0.5f);

        tvAdminLockInfo.setText(admin ? getString(R.string.admin_area_unlocked)
                : getString(R.string.admin_area_locked));
        btnAdminLogin.setVisibility(admin ? View.GONE : View.VISIBLE);
        btnAdminLogout.setVisibility(admin ? View.VISIBLE : View.GONE);
    }

    private void setInputsEnabled(boolean enabled) {
        etMinSize.setEnabled(enabled);
        etHueLow.setEnabled(enabled);
        etHueHigh.setEnabled(enabled);
        etCscLow.setEnabled(enabled);
        etCscHigh.setEnabled(enabled);
        for (int i = 0; i < rgMethod.getChildCount(); i++) {
            View child = rgMethod.getChildAt(i);
            if (child instanceof RadioButton) child.setEnabled(enabled);
        }
    }

    private void populateFields() {
        etMinSize.setText(String.valueOf(settings.getMinSize()));
        etHueLow.setText(String.valueOf(settings.getHueLow()));
        etHueHigh.setText(String.valueOf(settings.getHueHigh()));
        etCscLow.setText(String.format(Locale.US, "%.1f", settings.getCscBandLow()));
        etCscHigh.setText(String.format(Locale.US, "%.1f", settings.getCscBandHigh()));
        rgMethod.check(AnalysisSettings.METHOD_HSV.equals(settings.getMethod())
                ? R.id.rbMethodHsv : R.id.rbMethodExg);
    }

    private void saveFields() {
        if (!AdminAuth.isAdmin(requireContext())) return;
        try {
            int minSize = Integer.parseInt(etMinSize.getText().toString().trim());
            int hueLow  = Integer.parseInt(etHueLow.getText().toString().trim());
            int hueHigh = Integer.parseInt(etHueHigh.getText().toString().trim());
            // Dezimaltrennzeichen tolerant behandeln (Komma und Punkt)
            double cscLow  = Double.parseDouble(etCscLow.getText().toString().trim().replace(',', '.'));
            double cscHigh = Double.parseDouble(etCscHigh.getText().toString().trim().replace(',', '.'));

            if (settings.save(minSize, hueLow, hueHigh, cscLow, cscHigh)) {
                settings.setMethod(rgMethod.getCheckedRadioButtonId() == R.id.rbMethodHsv
                        ? AnalysisSettings.METHOD_HSV : AnalysisSettings.METHOD_EXG);
                Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.settings_invalid, Toast.LENGTH_LONG).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), R.string.settings_invalid, Toast.LENGTH_LONG).show();
        }
    }
}
