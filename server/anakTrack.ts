import { createClientFromRequest } from 'npm:@base44/sdk@0.8.31';

// AnakTrack - endpoint untuk app AnakTracker CCTV di HP anak + dashboard orang tua.
// Action:
//  - "report": simpan laporan lokasi (ping/aman/sos)
//  - "photo" : simpan foto CCTV (base64 JPEG)
//  - "get"   : baca data terbaru (untuk dashboard orang tua)
// Semua action wajib membawa token rahasia yang sama dengan Config.java di HP.

Deno.serve(async (req) => {
  // Token rahasia: HARUS sama dengan Config.SECRET_TOKEN di HP anak
  const SECRET = "GANTI-DENGAN-KODE-RAHASIA-KAMU"; // HARUS sama dengan Config.SECRET_TOKEN di HP anak
  const jsonHeaders = {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
  };
  const reply = (obj, status = 200) =>
    new Response(JSON.stringify(obj), { status, headers: jsonHeaders });

  // CORS preflight (dari halaman GitHub Pages / hosting lain)
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type",
        "Access-Control-Max-Age": "86400",
      },
    });
  }

  let body;
  try {
    body = await req.json();
  } catch (e) {
    return reply({ success: false, message: "JSON tidak valid" }, 400);
  }

  const action = body?.action;
  const p = body?.payload || {};

  // Cek token anti data palsu
  if (p.token !== SECRET) {
    return reply({ success: false, message: "token salah" }, 403);
  }

  const base44 = createClientFromRequest(req);
  const svc = base44.asServiceRole || base44;

  try {
    if (action === "report") {
      await svc.entities.AnakLocation.create({
        child_id: p.child_id ?? "anak1",
        latitude: Number(p.latitude) || 0,
        longitude: Number(p.longitude) || 0,
        accuracy: Number(p.accuracy) || 0,
        fix_time: Number(p.fix_time) || 0,
        fix_age_menit: Number(p.fix_age_menit ?? -1),
        battery: Number(p.battery ?? -1),
        event_type: p.event_type || "ping",
        device_info: p.device_info || "",
      });
      return reply({ success: true, action: "report", mode: base44.asServiceRole ? "serviceRole" : "fallback" });
    }

    if (action === "photo") {
      if (!p.photo_base64) {
        return reply({ success: false, message: "foto kosong" }, 400);
      }
      await svc.entities.AnakPhoto.create({
        child_id: p.child_id ?? "anak1",
        photo_base64: p.photo_base64,
        latitude: Number(p.latitude) || 0,
        longitude: Number(p.longitude) || 0,
        device_info: p.device_info || "",
      });

      // Rapikan: simpan maksimal 30 foto terakhir (hapus sisanya)
      try {
        const semua = (await svc.entities.AnakPhoto.list()) || [];
        const urut = semua.slice().sort((a, b) =>
          String(b.created_date || "").localeCompare(String(a.created_date || "")));
        const ids = urut.map((r) => r.id || r._id).filter(Boolean);
        for (const id of ids.slice(30)) {
          await svc.entities.AnakPhoto.delete(id);
        }
      } catch (e) {
        // pruning gagal tidak boleh gagalkan laporan foto
      }

      return reply({ success: true, action: "photo", mode: base44.asServiceRole ? "serviceRole" : "fallback" });
    }

    // ==== BACA DATA (dashboard orang tua) ====
    if (action === "get") {
      const semuaLok = (await svc.entities.AnakLocation.list()) || [];
      const lokasi = semuaLok.slice()
        .sort((a, b) => String(b.created_date || "").localeCompare(String(a.created_date || "")))
        .slice(0, 25);
      const semuaFoto = (await svc.entities.AnakPhoto.list()) || [];
      const foto = semuaFoto.slice()
        .sort((a, b) => String(b.created_date || "").localeCompare(String(a.created_date || "")))[0] || null;
      return reply({
        success: true,
        locations: lokasi,
        photo: foto,
      });
    }

    return reply({ success: false, message: "action tidak dikenal" }, 400);
  } catch (e) {
    return reply({ success: false, message: "gagal simpan: " + (e?.message || e) }, 500);
  }
});
