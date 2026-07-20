package com.example.smartweed;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Offline-Admin-Anmeldung. Die App ist ohne Anmeldung voll nutzbar; der
 * Admin-Status schaltet lediglich die Experten-Einstellungen frei.
 *
 * Es wird KEIN Klartext-Passwort gespeichert oder ausgeliefert — nur ein
 * gesalzener SHA-256-Hash. Passwort ändern:
 *   printf 'SmartWeedControl.v14.salt:NEUES_PASSWORT' | sha256sum
 * und den Hex-Wert unten in ADMIN_HASH eintragen.
 */
public final class AdminAuth {

    private static final String PREFS_NAME = "admin_prefs";
    private static final String KEY_IS_ADMIN = "is_admin";

    private static final String SALT = "SmartWeedControl.v14.salt:";
    private static final String ADMIN_HASH =
            "317e9c8a47753000038b97a469ccc5d24e6395c006a12fee72fd134bb64d7e3c";

    private AdminAuth() { }

    public static boolean isAdmin(Context context) {
        return prefs(context).getBoolean(KEY_IS_ADMIN, false);
    }

    /** @return true, wenn das Passwort stimmt (Admin-Status wird gespeichert) */
    public static boolean login(Context context, String password) {
        if (password == null) return false;
        String hash = sha256Hex(SALT + password);
        if (ADMIN_HASH.equalsIgnoreCase(hash)) {
            prefs(context).edit().putBoolean(KEY_IS_ADMIN, true).apply();
            return true;
        }
        return false;
    }

    public static void logout(Context context) {
        prefs(context).edit().putBoolean(KEY_IS_ADMIN, false).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 ist auf Android immer verfügbar
            throw new IllegalStateException(e);
        }
    }
}
