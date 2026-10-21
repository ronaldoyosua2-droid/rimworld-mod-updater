# Catatan untuk Claude

- Setiap push ke `main` membuat rilis GitHub `build-N`. Deskripsi = paragraf pembuka `.github/release/INTRO.md` + `WHATS_NEW.md` + paragraf penutup `.github/release/OUTRO.md`. **Semuanya dalam bahasa Inggris.**
- **Sebelum setiap push yang mengubah aplikasi, tulis ulang `WHATS_NEW.md`** berisi perubahan build itu saja
  (bahasa Inggris, poin singkat ber-emoji, ramah pengguna). INTRO = satu paragraf pembuka saja.
- Perubahan yang tidak perlu build (dokumen saja) boleh pakai `[skip ci]` di pesan commit.
- **Aturan YES:** setiap perubahan aplikasi (fitur baru, perubahan tampilan, DAN perbaikan bug) baru boleh dikerjakan/di-push setelah pemilik membalas persis `YES` (huruf besar semua, tanpa typo; `yes`/`Yes`/`yed` tidak dihitung).
  - Perubahan tampilan: kirim gambar mockup dulu.
  - Perbaikan bug: jelaskan dulu penyebab dan rencana perbaikannya.
