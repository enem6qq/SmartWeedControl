package com.example.smartweed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Hält die App während einer laufenden Analyse am Leben (v1.9).
 *
 * Die Analyse rechnet in einem Hintergrund-Thread des App-Prozesses. Ohne
 * diesen Dienst pausierte sie, sobald das Gerät in den Ruhezustand ging, und
 * Android konnte den Prozess einfrieren oder beenden, wenn der Nutzer in eine
 * andere App wechselte — bei vielen Rahmenbildern (mehrere Sekunden je Bild)
 * ging das Ergebnis dann verloren. Der Dienst
 *   - läuft als Vordergrund-Dienst mit Benachrichtigung (Prozess bleibt wichtig),
 *   - hält einen Teil-WakeLock (CPU bleibt wach, auch bei ausgeschaltetem Display).
 *
 * Bewusst defensiv gebaut: Der Dienst wird mit startService() gestartet, solange
 * die App sichtbar ist (die Analyse startet per Tastendruck) — NICHT mit
 * startForegroundService(). So gibt es keine Pflicht, innerhalb weniger
 * Sekunden startForeground() zu rufen; schlägt irgendetwas fehl (fehlende
 * Rechte auf einem Hersteller-ROM o. Ä.), läuft die Analyse einfach wie bis
 * v1.8 weiter, statt dass Android die App beendet.
 */
public class AnalysisKeepAliveService extends Service {

    private static final String TAG = "AnalysisKeepAlive";
    private static final String CHANNEL_ID = "analysis_running";
    private static final int NOTIFICATION_ID = 1901;
    /** Sicherheitsnetz: Der WakeLock läuft spätestens nach dieser Zeit ab */
    private static final long WAKELOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L;

    /** true, solange eine Analyse läuft (wird von beliebigen Threads gesetzt) */
    private static volatile boolean wanted = false;
    /** laufende Instanz; nur auf dem Main-Thread gelesen und geschrieben */
    private static AnalysisKeepAliveService instance;

    private PowerManager.WakeLock wakeLock;

    /** Beim Start der Analyse aufrufen (App ist dabei sichtbar). */
    public static void start(Context context) {
        wanted = true;
        Context app = context.getApplicationContext();
        try {
            app.startService(new Intent(app, AnalysisKeepAliveService.class));
        } catch (RuntimeException e) {
            // z. B. IllegalStateException, falls die App doch nicht im
            // Vordergrund ist — dann eben ohne Schutz (Verhalten wie bis v1.8)
            Log.w(TAG, "Dienst konnte nicht gestartet werden", e);
        }
    }

    /** Am Ende der Analyse aufrufen (beliebiger Thread, auch im Fehlerfall). */
    public static void stop() {
        wanted = false;
        // Auf dem Main-Thread, damit es sich nicht mit onCreate/onStartCommand
        // überschneidet. Ist der Dienst noch gar nicht erzeugt, beendet er
        // sich in onStartCommand selbst (wanted == false).
        new Handler(Looper.getMainLooper()).post(() -> {
            AnalysisKeepAliveService s = instance;
            if (s != null && !wanted) s.shutdown();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        // Benachrichtigung in der in der App gewählten Sprache
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!wanted) {
            shutdown();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground fehlgeschlagen – Analyse läuft ohne Hintergrund-Schutz weiter", e);
        }
        acquireWakeLock();
        // NOT_STICKY: Nach einem Prozess-Tod gibt es keine Analyse mehr, die
        // geschützt werden müsste — der Dienst soll dann nicht neu starten.
        return START_NOT_STICKY;
    }

    /** Android 15+: Zeitlimit des Vordergrund-Diensts erreicht — sauber beenden. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        shutdown();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel_analysis),
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_leaf)
                .setContentTitle(getString(R.string.notification_analysis_title))
                .setContentText(getString(R.string.notification_analysis_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        // Der Launcher-Intent holt die BESTEHENDE Activity nach vorn. Ein
        // direkter Intent auf MainActivity könnte sie neu erzeugen — das
        // ViewModel mit dem laufenden Ergebnis wäre dann weg.
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return builder.build();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartWeed:analysis");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "WakeLock nicht verfügbar", e);
        }
    }

    private void shutdown() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException e) {
            Log.w(TAG, "stopForeground fehlgeschlagen", e);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "WakeLock-Freigabe fehlgeschlagen", e);
            }
        }
        wakeLock = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
