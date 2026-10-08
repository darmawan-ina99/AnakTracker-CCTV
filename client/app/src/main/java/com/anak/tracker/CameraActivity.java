package com.anak.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class CameraActivity extends Activity {

    private static final int REQUEST_CAMERA = 30;

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private boolean autoMode = false; // V2.2: dipicu tombol Lihat Anak dari orang tua
    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private boolean frontCamera = false;
    private String cameraId;
    private ImageReader imageReader;
    private Button btnCapture;
    private boolean uploading = false;

    private final TextureView.SurfaceTextureListener surfaceListener =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                bukaKamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        };

    private final CameraDevice.StateCallback cameraCallback =
        new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) {
                cameraDevice = camera;
                tampilkanPreview();
            }
            @Override public void onDisconnected(CameraDevice camera) {
                camera.close();
                cameraDevice = null;
            }
            @Override public void onError(CameraDevice camera, int error) {
                camera.close();
                cameraDevice = null;
                Toast.makeText(CameraActivity.this, "Kamera gagal dibuka", Toast.LENGTH_SHORT).show();
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        autoMode = getIntent().getBooleanExtra("auto", false);
        buatTampilan();
        startCameraThread();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void buatTampilan() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(surfaceListener);
        root.addView(textureView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (autoMode) {
            TextView t = new TextView(this);
            t.setText(autoMode ? "Mengambil foto..." : "");
            t.setTextColor(0xFFFFFFFF);
            t.setPadding(24, 24, 24, 24);
            root.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            textureView.getLayoutParams().height = 1; // preview mini (kamera tetap perlu surface)
        }
        btnCapture = new Button(this);
        btnCapture.setText("AMBIL FOTO & KIRIM");
        btnCapture.setTextSize(16f);
        btnCapture.setBackgroundColor(0xFF16A34A);
        btnCapture.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams pCapture = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pCapture.setMargins(16, 24, 16, 0);
        if (autoMode) { btnCapture.setVisibility(View.GONE); }
        root.addView(btnCapture, pCapture);

        Button switchButton = new Button(this);
        switchButton.setText("GANTI KAMERA");
        LinearLayout.LayoutParams pSwitch = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pSwitch.setMargins(16, 8, 16, 16);
        root.addView(switchButton, pSwitch);

        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ambilFoto();
            }
        });

        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                frontCamera = !frontCamera;
                tutupKamera();
                bukaKamera();
            }
        });

        setContentView(root);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void bukaKamera() {
        if (cameraDevice != null) return; // FIX V2.1: kamera sudah terbuka
        if (cameraHandler == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = cariCameraId();
            if (cameraId == null) {
                Toast.makeText(this, "Kamera tidak tersedia", Toast.LENGTH_SHORT).show();
                return;
            }
            siapkanImageReader();
            cameraManager.openCamera(cameraId, cameraCallback, cameraHandler);
        } catch (SecurityException e) {
            Toast.makeText(this, "Izin kamera ditolak", Toast.LENGTH_SHORT).show();
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Tidak dapat mengakses kamera", Toast.LENGTH_SHORT).show();
        }
    }

    private void siapkanImageReader() throws CameraAccessException {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int w = 1280, h = 720;
        if (map != null) {
            android.util.Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes != null && sizes.length > 0) {
                // FIX V2.1: pilih ukuran terbesar dengan lebar <= 1920
                android.util.Size pilih = sizes[0];
                for (android.util.Size s : sizes) {
                    if (s.getWidth() <= 1920 && s.getWidth() >= pilih.getWidth()) {
                        pilih = s;
                    }
                }
                w = pilih.getWidth();
                h = pilih.getHeight();
            }
        }
        imageReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader reader) {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null) {
                        if (uploading) { img.close(); img = null; return; } // FIX V2.1
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] jpeg = new byte[buf.remaining()];
                        buf.get(jpeg);
                        kirimFoto(jpeg);
                    }
                } finally {
                    if (img != null) img.close();
                }
            }
        }, cameraHandler);
    }

    private String cariCameraId() throws CameraAccessException {
        String fallback = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (frontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
            if (!frontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
            fallback = id;
        }
        return fallback;
    }

    private void tampilkanPreview() {
        if (cameraDevice == null || !textureView.isAvailable() || imageReader == null) return;

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(textureView.getWidth(), textureView.getHeight());
            Surface surface = new Surface(texture);

            final CaptureRequest.Builder builder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(
                Arrays.asList(surface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try {
                            builder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        } catch (CameraAccessException e) {
                            Toast.makeText(CameraActivity.this, "Preview kamera gagal", Toast.LENGTH_SHORT).show();
                        }
                        if (autoMode) {
                            cameraHandler.postDelayed(new Runnable() {
                                @Override public void run() { ambilFoto(); }
                            }, 3000); // tungup kamera warm-up + fokus
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        Toast.makeText(CameraActivity.this, "Konfigurasi kamera gagal", Toast.LENGTH_SHORT).show();
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Preview kamera gagal", Toast.LENGTH_SHORT).show();
        }
    }

    // ==== AMBIL FOTO (CCTV) ====
    private void ambilFoto() {
        if (uploading) return;
        if (captureSession == null || cameraDevice == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CaptureRequest.Builder b =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(imageReader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, hitungOrientasi());
            captureSession.capture(b.build(), null, cameraHandler);
            if (btnCapture != null) {
                btnCapture.setEnabled(false);
                btnCapture.setText("MENGIRIM...");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengambil foto", Toast.LENGTH_SHORT).show();
        }
    }

    private int hitungOrientasi() {
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            if (rot == android.view.Surface.ROTATION_0) degrees = 0;
            else if (rot == android.view.Surface.ROTATION_90) degrees = 90;
            else if (rot == android.view.Surface.ROTATION_180) degrees = 180;
            else if (rot == android.view.Surface.ROTATION_270) degrees = 270;
            return (sensor + degrees) % 360;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==== KOMPRES + KIRIM ====
    private void kirimFoto(byte[] jpegAsli) {
        if (uploading) return;
        uploading = true;
        final byte[] jpegKecil = kecilkan(jpegAsli);

        final double[] lokasi = lokasiSekarang();

        new Thread() {
            @Override public void run() {
                boolean ok = false;
                try {
                    String b64 = Base64.encodeToString(jpegKecil, Base64.NO_WRAP);

                    JSONObject payload = new JSONObject();
                    payload.put("child_id", Config.CHILD_ID);
                    payload.put("token", Config.SECRET_TOKEN);
                    payload.put("event_type", "cctv");
                    payload.put("photo_base64", b64);
                    payload.put("latitude", lokasi[0]);
                    payload.put("longitude", lokasi[1]);
                    payload.put("device_info", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);

                    JSONObject body = new JSONObject();
                    body.put("action", "photo");
                    body.put("payload", payload);

                    ok = TrackerService.httpPost(body) != null;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean sukses = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        uploading = false;
                        btnCapture.setEnabled(true);
                        btnCapture.setText("AMBIL FOTO & KIRIM");
                        if (sukses) {
                            Toast.makeText(CameraActivity.this,
                                "Foto terkirim ke Bapak", Toast.LENGTH_LONG).show();
                            SharedPreferences.Editor ed = getSharedPreferences("anaktrack", MODE_PRIVATE).edit();
                            ed.putString("last_photo",
                                new SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
                                    .format(new Date()));
                            ed.apply();
                        } else {
                            Toast.makeText(CameraActivity.this,
                                "Gagal kirim. Cek koneksi, lalu coba lagi", Toast.LENGTH_LONG).show();
                        }
                        if (autoMode) {
                            // lapor perintah selesai lalu tutup otomatis
                            new Thread() {
                                @Override public void run() {
                                    try {
                                        JSONObject payload = new JSONObject();
                                        payload.put("child_id", Config.CHILD_ID);
                                        payload.put("token", Config.SECRET_TOKEN);
                                        JSONObject body = new JSONObject();
                                        body.put("action", "cmddone");
                                        body.put("payload", payload);
                                        TrackerService.httpPost(body);
                                    } catch (Exception e) { /* abaikan */ }
                                }
                            }.start();
                            finish();
                        }
                    }
                });
            }
        }.start();
    }

    private byte[] kecilkan(byte[] jpegAsli) {
        try {
            Bitmap bm = BitmapFactory.decodeByteArray(jpegAsli, 0, jpegAsli.length);
            if (bm == null) return jpegAsli;
            int w = bm.getWidth(), h = bm.getHeight();
            if (w > Config.PHOTO_MAX_WIDTH) {
                int nh = (int) (h * ((double) Config.PHOTO_MAX_WIDTH / w));
                Bitmap kecil = Bitmap.createScaledBitmap(bm, Config.PHOTO_MAX_WIDTH, nh, true);
                bm.recycle();
                bm = kecil;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, Config.PHOTO_QUALITY, bos);
            bm.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return jpegAsli;
        }
    }

    private double[] lokasiSekarang() {
        double[] out = {0, 0};
        try {
            android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String prov : new String[]{
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.PASSIVE_PROVIDER}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException se) { /* abaikan */ }
            }
            if (best != null) {
                out[0] = best.getLatitude();
                out[1] = best.getLongitude();
            }
        } catch (Exception e) { /* abaikan */ }
        return out;
    }

    private void tutupKamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        // imageReader ditutup lewat siapkanImageReader saat buka kamera berikutnya
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable()) bukaKamera();
            } else {
                Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraThread == null) startCameraThread();
        if (textureView != null && textureView.isAvailable()) bukaKamera();
    }

    @Override
    protected void onPause() {
        tutupKamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tutupKamera();
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        super.onDestroy();
    }
}
