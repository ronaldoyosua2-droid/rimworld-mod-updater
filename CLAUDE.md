# Catatan untuk Claude

- Setiap push ke `main` membuat rilis GitHub `build-N`. Deskripsinya = `.github/release/INTRO.md` + `WHATS_NEW.md`.
- **Sebelum setiap push yang mengubah aplikasi, tulis ulang `WHATS_NEW.md`** berisi perubahan build itu saja
  (bahasa Indonesia, poin singkat ber-emoji, ramah pengguna). Perbarui `INTRO.md` kalau ada fitur besar baru.
- Perubahan yang tidak perlu build (dokumen saja) boleh pakai `[skip ci]` di pesan commit.
- Pemilik minta gambar mockup dulu untuk perubahan tampilan, dan baru boleh dikerjakan setelah dia membalas persis `YES`.
