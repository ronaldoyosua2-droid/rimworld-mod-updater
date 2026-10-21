# Catatan untuk Claude

- Setiap push ke `main` membuat rilis GitHub `build-N`. Deskripsi = paragraf pembuka `.github/release/INTRO.md` + `WHATS_NEW.md` + paragraf penutup `.github/release/OUTRO.md`. **Semuanya dalam bahasa Inggris.**
- **Sebelum setiap push yang mengubah aplikasi, tulis ulang `WHATS_NEW.md`** berisi perubahan build itu saja
  (bahasa Inggris, poin singkat ber-emoji, ramah pengguna). INTRO = satu paragraf pembuka saja.
- Perubahan yang tidak perlu build (dokumen saja) boleh pakai `[skip ci]` di pesan commit.
- Pemilik minta gambar mockup dulu untuk perubahan tampilan, dan baru boleh dikerjakan setelah dia membalas persis `YES`.
