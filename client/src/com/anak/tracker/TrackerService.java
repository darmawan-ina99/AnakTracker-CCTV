package com.anak.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TrackerService extends Service {

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private boolean sudahDijadwalkan = false; // FIX V2.1: cegah penjadwalan dobel
    private boolean laporanTerkirim = false;   // FIX V2.1: kirim sekali per siklus
    private LocationManager locMan;
    private double lastLat = 0, lastLng = 0;
    private float lastAcc = 0;
    private long lastFixTime = 0; // V2: waktu fix lokasi asli

    @Override
    public void onCreate() {
        super.onCreate();
        locMan = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mulaiForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String event = intent != null ? intent.getStringExtra("event") : null;

        if ("aman".equals(event)) {
            ambilLokasiLaluKirim("aman");
        } else if ("sos".equals(event)) {
            ambilLokasiLaluKirim("sos");
        } else {
            // start biasa (termasuk restart otomatis): kirim sekarang lalu jadwalkan rutin
            ambilLokasiLaluKirim("ping");
            jadwalkan();
        }
        return START_STICKY;
    }

    private void jadwalkan() {
        if (sudahDijadwalkan) return; // FIX V2.1: cukup satu timer
        sudahDijadwalkan = true;
        if (tick != null) handler.removeCallbacks(tick);
        tick = new Runnable() {
            @Override
            public void run() {
                ambilLokasiLaluKirim("ping");
                handler.postDelayed(this, Config.INTERVAL_MENIT * 60 * 1000L);
            }
        };
        handler.postDelayed(tick, Config.INTERVAL_MENIT * 60 * 1000L);
    }

    // ==== AMBIL LOKASI ====
    private void ambilLokasiLaluKirim(final String tipe) {
        laporanTerkirim = false;
        Location terbaik = lokasiTerakhir();
        if (terbaik != null) {
            lastLat = terbaik.getLatitude();
            lastLng = terbaik.getLongitude();
            lastAcc = terbaik.getAccuracy();
            lastFixTime = terbaik.getTime();
            kirimDenganJaga(tipe);
        } else {
            // FIX V2.1: kalau 90 detik GPS belum dapat fix, kirim apa adanya
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    kirimDenganJaga(tipe);
                }
            }, 90000);
            // minta update sekali
            try {
                locMan.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                    new LocationListener() {
                        public void onLocationChanged(Location l) {
                            lastLat = l.getLatitude();
                            lastLng = l.getLongitude();
                            lastAcc = l.getAccuracy();
                            lastFixTime = l.getTime();
                            kirimDenganJaga(tipe);
                        }
                        public void onStatusChanged(String p, int s, Bundle b) {}
                        public void onProviderEnabled(String p) {}
                        public void onProviderDisabled(String p) {}
                    }, Looper.getMainLooper());
            } catch (SecurityException e) {
                kirimDenganJaga(tipe); // kirim apapun tanpa lokasi
            } catch (IllegalArgumentException e) {
                cobaNetwork(tipe);
            }
        }
    }

    // FIX V2.1: pastikan satu siklus hanya kirim satu laporan
    private void kirimDenganJaga(String tipe) {
        if (laporanTerkirim) return;
        laporanTerkirim = true;
        kirim(tipe);
    }

    private void cobaNetwork(final String tipe) {
        try {
            locMan.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                new LocationListener() {
                    public void onLocationChanged(Location l) {
                        lastLat = l.getLatitude();
                        lastLng = l.getLongitude();
                        lastAcc = l.getAccuracy();
                        lastFixTime = l.getTime();
                        kirimDenganJaga(tipe);
                    }
                    public void onStatusChanged(String p, int s, Bundle b) {}
                    public void onProviderEnabled(String p) {}
                    public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
        } catch (Exception e) {
            kirim(tipe);
        }
    }

    private Location lokasiTerakhir() {
        Location best = null;
        try {
            for (String prov : new String[]{
                    LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER}) {
                Location l = locMan.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (SecurityException e) { /* belum ada izin */ }
        return best;
    }

    // ==== KIRIM KE SERVER ====
    private void kirim(final String tipe) {
        final double lat = lastLat, lng = lastLng;
        final float acc = lastAcc;
        final long fixTime = lastFixTime;
        final int batt = ambilBaterai();

        new Thread() {
            @Override
            public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("child_id", Config.CHILD_ID);
                    payload.put("token", Config.SECRET_TOKEN); // V2: anti lokasi palsu
                    payload.put("latitude", lat);
                    payload.put("longitude", lng);
                    payload.put("accuracy", (double) acc);
                    payload.put("fix_time", fixTime); // V2: umur data lokasi
                    payload.put("fix_age_menit", fixTime > 0
                        ? Math.round((System.currentTimeMillis() - fixTime) / 60000.0) : -1);
                    payload.put("battery", batt);
                    payload.put("event_type", tipe);
                    payload.put("device_info", Build.MANUFACTURER + " " + Build.MODEL);

                    JSONObject body = new JSONObject();
                    body.put("action", "report");
                    body.put("payload", payload);

                    String resp = httpPost(body);
                    if (resp != null) {
                        String now = new SimpleDateFormat("dd MMM yyyy HH:mm:ss",
                            Locale.getDefault()).format(new Date());
                        SharedPreferences.Editor ed =
                            getSharedPreferences("anaktrack", MODE_PRIVATE).edit();
                        ed.putString("last_sent", now + " (" + tipe + ")");
                        ed.apply();
                    }
                } catch (Exception e) {
                    // offline: coba lagi dipanggil interval berikutnya
                }
            }
        }.start();
    }

    // ==== HTTP POST bersama (dipakai report & foto CCTV) ====
    public static String httpPost(JSONObject body) {
        try {
            URL url = new URL(Config.ENDPOINT);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200 ? "ok" : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int ambilBaterai() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    // ==== NOTIFIKASI FOREGROUND ====
    private void mulaiForeground() {
        String channelId = "anaktrack";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channelId,
                "AnakTracker", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, channelId);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("AnakTracker aktif")
            .setContentText("Lokasi terkirim berkala - jangan ditutup")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi);

        startForeground(1, b.build());
    }

    @Override
    public void onDestroy() {
        if (tick != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
