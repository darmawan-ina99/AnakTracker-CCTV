import { createClientFromRequest } from 'npm:@base44/sdk@0.8.31';

// AnakTrack - endpoint untuk app AnakTracker CCTV di HP anak + dashboard orang tua.
// Action:
//  - "report": simpan laporan lokasi (ping/aman/sos)
//  - "photo" : simpan foto CCTV (base64 JPEG -> diunggah sebagai file, entity simpan URL)
//  - "get"   : baca data terbaru (untuk dashboard orang tua)
//  - "request_photo" : orang tua minta foto anak SEKARANG (tombol Lihat Anak)
//  - "pollcmd": HP anak cek apakah ada perintah menunggu
//  - "cmddone": HP anak lapor perintah selesai
// Semua action wajib membawa token rahasia yang sama dengan Config.java di HP.

Deno.serve(async (req) => {
  // Token rahasia: HARUS sama dengan Config.SECRET_TOKEN di HP anak
  const SECRET = "ATK-Sriamur-7K2m9Q4x";
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

  // Cek token anti data palsu (spasi diabaikan, huruf besar/kecil diabaikan)
  const masuk = String(p.token || "").replace(/\s+/g, "").toLowerCase();
  if (masuk !== SECRET) {
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

      // FIX V2.2.1: field entity dibatasi ~10KB, jadi foto diunggah sebagai FILE,
      // entity hanya menyimpan URL. Fallback base64 hanya untuk foto sangat kecil.
      // semua foto diunggah sebagai FILE (aman untuk ukuran besar);
      // fallback field base64 hanya jika upload gagal DAN foto kecil (<9KB)
      let fotoUrl = "";
      try {
        const bytes = Uint8Array.from(atob(p.photo_base64), (c) => c.charCodeAt(0));
        const file = new File([bytes], "cctv_" + Date.now() + ".jpg", { type: "image/jpeg" });
        const hasil = await svc.integrations.Core.UploadFile({ file });
        const r = hasil?.data ?? hasil;
        fotoUrl = r?.url || r?.file_url || (typeof r === "string" ? r : "");
      } catch (e) {
        fotoUrl = ""; // gagal upload: coba fallback base64 di bawah
      }
      const kecil = p.photo_base64.length < 9000;

      await svc.entities.AnakPhoto.create({
        child_id: p.child_id ?? "anak1",
        photo_url: fotoUrl,
        photo_base64: !fotoUrl && kecil ? p.photo_base64 : undefined,
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

    // ==== PERINTAH FOTO DARI ORANG TUA (tombol Lihat Anak) ====
    if (action === "request_photo") {
      await svc.entities.AnakCommand.create({
        child_id: p.child_id ?? "anak1",
        cmd: "photo",
        status: "pending",
      });
      return reply({ success: true, action: "request_photo" });
    }

    if (action === "pollcmd") {
      const semua = (await svc.entities.AnakCommand.list()) || [];
      const pending = semua.filter((c) =>
        (c.data?.child_id || c.child_id || "anak1") === (p.child_id ?? "anak1") &&
        ((c.data?.status || c.status) === "pending"));
      if (pending.length === 0) {
        return reply({ success: true, cmd: null });
      }
      // ambil paling baru, tandai claimed
      const terbaru = pending.slice().sort((a, b) =>
        String(b.created_date || "").localeCompare(String(a.created_date || "")))[0];
      await svc.entities.AnakCommand.update(terbaru.id || terbaru._id, { status: "claimed" });
      return reply({ success: true, cmd: terbaru.data?.cmd || terbaru.cmd, id: terbaru.id || terbaru._id });
    }

    if (action === "cmddone") {
      const semua = (await svc.entities.AnakCommand.list()) || [];
      const claimed = semua.filter((c) => (c.data?.status || c.status) === "claimed");
      for (const c of claimed) {
        await svc.entities.AnakCommand.update(c.id || c._id, { status: "done" });
      }
      return reply({ success: true, action: "cmddone", ditutup: claimed.length });
    }

    // ==== BACA DATA (dashboard orang tua) ====
    if (action === "get") {
      const semuaLok = (await svc.entities.AnakLocation.list()) || [];
      const lokasi = semuaLok.slice()
        .sort((a, b) => String(b.created_date || "").localeCompare(String(a.created_date || "")))
        .slice(0, 25);
      const semuaCmd = (await svc.entities.AnakCommand.list()) || [];
      const cmdAktif = semuaCmd.filter((c) =>
        ["pending", "claimed"].includes(String(c.data?.status || c.status)))
        .sort((a, b) => String(b.created_date || "").localeCompare(String(a.created_date || "")))[0] || null;
      const semuaFoto = (await svc.entities.AnakPhoto.list()) || [];
      const foto = semuaFoto.slice()
        .sort((a, b) => String(b.created_date || "").localeCompare(String(a.created_date || "")))[0] || null;
      return reply({
        success: true,
        locations: lokasi,
        photo: foto,
        cmd: cmdAktif ? {
          cmd: cmdAktif.data?.cmd || cmdAktif.cmd,
          status: cmdAktif.data?.status || cmdAktif.status,
          created_date: cmdAktif.created_date,
        } : null,
      });
    }

    return reply({ success: false, message: "action tidak dikenal" }, 400);
  } catch (e) {
    return reply({ success: false, message: "gagal simpan: " + (e?.message || e) }, 500);
  }
});
