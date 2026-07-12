package com.example.smartweed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.Locale;

/**
 * Experten-Einstellungen für die Bildanalyse und den CSC-Zielbereich.
 * Erreichbar über den Menüpunkt "Einstellungen" in der Toolbar.
 */
public class SettingsFragment extends Fragment {

    private AnalysisSettings settings;

    private EditText etMinSize;
    private EditText etHueLow;
    private EditText etHueHigh;
    private EditText etCscLow;
    private EditText etCscHigh;
    private RadioGroup rgMethod;

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
        Button save  = view.findViewById(R.id.buttonSaveSettings);
        Button reset = view.findViewById(R.id.buttonResetSettings);

        populateFields();

        save.setOnClickListener(v -> saveFields());

        reset.setOnClickListener(v -> {
            settings.reset();
            populateFields();
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
        });
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
