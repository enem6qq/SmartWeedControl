package com.example.smartweed;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Login extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private EditText usernameEditText;
    private EditText passwordEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        usernameEditText = findViewById(R.id.UsernameText);
        passwordEditText = findViewById(R.id.PasswordText);

        Button loginButton = findViewById(R.id.LoginButton);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(Login.this, R.string.login_button_pressed, Toast.LENGTH_SHORT).show();
                login();
            }
        });
    }

    private void login() {
        final String usernameInput = usernameEditText.getText().toString().trim();
        final String passwordInput = passwordEditText.getText().toString().trim();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String jsonString = readFile("pw.json");

                    runOnUiThread(() -> {
                        if (jsonString == null || jsonString.isEmpty()) {
                            Toast.makeText(Login.this, R.string.login_json_empty, Toast.LENGTH_LONG).show();
                            return;
                        }

                        handleLoginResponse(jsonString, usernameInput, passwordInput);
                    });

                } catch (IOException e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(Login.this, R.string.login_file_error, Toast.LENGTH_LONG).show()
                    );
                }
            }
        }).start();
    }

    private void handleLoginResponse(String jsonString, String usernameInput, String passwordInput) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            JSONArray users = jsonObject.getJSONArray("benutzer");

            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.getJSONObject(i);
                String name = user.getString("benutzername");
                String pass = user.getString("passwort");

                if (usernameInput.equals(name) && passwordInput.equals(pass)) {
                    Toast.makeText(this, R.string.login_correct_password, Toast.LENGTH_SHORT).show();
                    startMainActivity();
                    return;
                }
            }

            Toast.makeText(this, R.string.login_wrong_credentials, Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.login_json_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void startMainActivity() {
        Intent intent = new Intent(Login.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private String readFile(String filePath) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;

        try {
            InputStream inputStream = getAssets().open(filePath);
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } finally {
            if (bufferedReader != null) {
                bufferedReader.close();
            }
        }

        return stringBuilder.toString();
    }
}
