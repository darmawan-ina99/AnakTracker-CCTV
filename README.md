# AnakTracker CCTV

Aplikasi pelacak anak untuk orang tua: HP anak mengirim lokasi berkala + foto CCTV (kamera depan), orang tua memantau lewat dashboard web.

## Komponen

1. `client/` — aplikasi Android (AIDE, Java) untuk HP anak. Versi 2.2.
   - Kirim lokasi tiap 5 menit (ping), tombol AKU AMAN, tombol SOS.
   - Mode CCTV: foto berkala via kamera depan (base64, auto-prune max 30 di server).
   - Efisien baterai: satu timer, fallback GPS 90 detik, satu laporan per siklus.
2. `server/anakTrack.ts` — backend function (Base44). Endpoint menerima:
   - `action=report` (lokasi), `action=photo` (foto), `action=get` (dashboard).
   - CORS terbuka agar dashboard bisa di-hosting di mana pun.
3. `index.html` — dashboard orang tua. Live di GitHub Pages (repo ini).

## Setup

1. Buat kode rahasia sendiri (acak, mis. 20 karakter). Samakan di 3 tempat:
   - `client/src/com/anak/tracker/Config.java` -> `SECRET_TOKEN`
   - `server/anakTrack.ts` -> `SECRET`
   - Dashboard web: dimasukkan sekali di browser (tersimpan di localStorage).
2. Deploy server: paste `server/anakTrack.ts` sebagai backend function Base44.
3. Build `client/` di AIDE (Android), pasang di HP anak, beri izin lokasi/kamera, buka sekali.
4. Buka dashboard (GitHub Pages repo ini), masukkan kode akses.

## Catatan keamanan

- Kode rahasia TIDAK disimpan di repo ini (sengaja, repo publik).
- Siapa pun yang tahu kode + endpoint bisa melihat lokasi anak: jaga kerahasiaan kode.
- Endpoint di `Config.java` dan `index.html` arahkan ke function kamu sendiri.

## Lisensi

Untuk pemakaian keluarga.
