package com.anak.tracker;

public class Config {
    // ==== PENGATURAN UTAMA ====
    // ID anak: satu kata tanpa spasi, contoh: "anak1"
    public static final String CHILD_ID = "anak1";

    // Endpoint API - SUDAH PINDAH ke server asisten (9 Okt 2026).
    // Tidak perlu vesper lagi. Server ini sudah diuji terima kiriman HP.
    public static final String ENDPOINT = "https://superagent-e5dc0f54.base44.app/functions/anakTrack";

    // Endpoint lama (vesper) - tidak dipakai lagi
    // public static final String ENDPOINT = "https://vesper-921ea8a1.base44.app/functions/anakTrack";

    // Token rahasia: HARUS sama dengan yang di setting function anakTrack
    // (di sisi OkTa). Ini pencegah orang luar kirim lokasi/foto palsu.
    public static final String SECRET_TOKEN = "GANTI-DENGAN-KODE-RAHASIA-KAMU";

    // Cek perintah "Lihat Anak" dari orang tua (detik) - V2.2
    public static final int POLL_PERINTAH_DETIK = 60;

    // Interval kirim lokasi otomatis (menit)
    public static final int INTERVAL_MENIT = 5;

    // CCTV: lebar maks foto (px) dan kualitas JPEG (0-100)
    public static final int PHOTO_MAX_WIDTH = 640;
    public static final int PHOTO_QUALITY = 70;
}
