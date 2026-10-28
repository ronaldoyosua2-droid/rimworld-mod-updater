# Catatan untuk Claude

- Setiap push ke `main` membuat rilis GitHub `build-N`. Deskripsi = paragraf pembuka `.github/release/INTRO.md` + `WHATS_NEW.md` + paragraf penutup `.github/release/OUTRO.md`. **Semuanya dalam bahasa Inggris.**
- **Sebelum setiap push yang mengubah aplikasi, tulis ulang `WHATS_NEW.md`** berisi perubahan build itu saja
  (bahasa Inggris, poin singkat ber-emoji, ramah pengguna). INTRO = satu paragraf pembuka saja.
- Perubahan yang tidak perlu build (dokumen saja) boleh pakai `[skip ci]` di pesan commit.
- **Aturan YES:** setiap perubahan aplikasi (fitur baru, perubahan tampilan, DAN perbaikan bug) baru boleh dikerjakan/di-push setelah pemilik membalas persis `YES` (huruf besar semua, tanpa typo; `yes`/`Yes`/`yed` tidak dihitung).
  - Perubahan tampilan: kirim gambar mockup dulu.
  - Perbaikan bug: jelaskan dulu penyebab dan rencana perbaikannya.
- Nama aplikasi: **ModSync Update**. Lisensi: All rights reserved (LICENSE). Logo: `docs/banner.png`, ikon `res/mipmap-*/ic_launcher.png`, layar pembuka `res/drawable-nodpi/splash.jpg`.
- Tanda tangan APK: `signing/release.jks.enc` (AES-256, pbkdf2 200000) dibuka di CI dengan secret `SIGNING_PASS`, alias `modsync`. Jangan pernah commit keystore/password asli.
