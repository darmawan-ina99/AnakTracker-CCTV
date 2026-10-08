package com.anak.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView tvStatus, tvInfo;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        prefs = getSharedPreferences("anaktrack", MODE_PRIVATE);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvInfo = (TextView) findViewById(R.id.tvInfo);
        Button btnAman = (Button) findViewById(R.id.btnAman);
        Button btnSos = (Button) findViewById(R.id.btnSos);
        Button btnCctv = (Button) findViewById(R.id.btnCctv);

        tvInfo.setText("Lokasi terkirim otomatis tiap " + Config.INTERVAL_MENIT
            + " menit.\nBaterai HP ikut dilaporkan.\n"
            + "BUKA CCTV = foto dari kamera HP,\nterkirim ke Bapak.\nID: " + Config.CHILD_ID);

        btnAman.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                kirimEvent("aman");
                Toast.makeText(MainActivity.this,
                    "Laporan Aku Aman terkirim", Toast.LENGTH_SHORT).show();
            }
        });

        btnSos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                kirimEvent("sos");
                Toast.makeText(MainActivity.this,
                    "SOS terkirim ke Bapak!", Toast.LENGTH_LONG).show();
            }
        });

        btnCctv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                        Manifest.permission.CAMERA
                    }, 3);
                } else {
                    bukaCctv();
                }
            }
        });

        mintaIzin();

        Intent svc = new Intent(this, TrackerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String last = prefs.getString("last_sent", "belum pernah");
        String foto = prefs.getString("last_photo", "belum pernah");
        tvStatus.setText("Terakhir kirim lokasi:\n" + last
            + "\n\nFoto CCTV terakhir:\n" + foto);
    }

    private void bukaCctv() {
        Intent intent = new Intent(this, CameraActivity.class);
        startActivity(intent);
    }

    private void kirimEvent(String tipe) {
        Intent i = new Intent(this, TrackerService.class);
        i.putExtra("event", tipe);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void mintaIzin() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.POST_NOTIFICATIONS
            }, 2);
        }
        try {
            Intent intent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // abaikan kalau tidak tersedia
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == 3) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                bukaCctv();
            } else {
                Toast.makeText(this, "Izin kamera diperlukan untuk CCTV",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin diberikan", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Izin lokasi diperlukan agar tracker bekerja",
                    Toast.LENGTH_LONG).show();
        }
    }
}
