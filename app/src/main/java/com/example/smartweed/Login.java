package com.example.smartweed;

import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Admin-Anmeldung: schaltet die Experten-Einstellungen frei.
 * Die App selbst ist ohne Anmeldung voll nutzbar — dieser Screen wird nur
 * aus den Einstellungen heraus geöffnet. Das Passwort wird ausschließlich
 * als gesalzener SHA-256-Hash geprüft (siehe {@link AdminAuth}).
 */
public class Login extends AppCompatActivity {

    private EditText passwordEditText;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        passwordEditText = findViewById(R.id.PasswordText);
        Button loginButton = findViewById(R.id.LoginButton);

        loginButton.setOnClickListener(v -> {
            String password = passwordEditText.getText().toString();
            if (AdminAuth.login(this, password)) {
                Toast.makeText(this, R.string.admin_login_success, Toast.LENGTH_SHORT).show();
                finish();
            } else {
                passwordEditText.setText("");
                Toast.makeText(this, R.string.admin_wrong_password, Toast.LENGTH_LONG).show();
            }
        });
    }
}
