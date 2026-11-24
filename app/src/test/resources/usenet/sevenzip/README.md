# Synthetic 7z fixtures (SevenZipArchiveTest, ReleaseAssemblerSevenZipTest)

Synthetic bytes only — no media, no account data. Test password: `fixture-only`.
Made with 7-Zip 24.09 (x64) on Windows.

Inputs:
- `a.nfo`: `fixture metadata ` repeated 19 times (a small entry before the video).
- `movie.mkv`: bytes `1A 45 DF A3` (EBML magic) followed by 100000 bytes where byte `i` is
  `(i * 31 + i / 257) % 256` (integer division, `i` from 0). See `SevenZipFixtures.payload()`.

Commands (run inside the input folder):

    7z a stored.7z            a.nfo movie.mkv -m0=Copy  -ms=on
    7z a stored-split.7z      a.nfo movie.mkv -m0=Copy  -ms=on -v40k          (→ .7z.001 … .003)
    7z a encrypted-headers.7z a.nfo movie.mkv -m0=Copy  -ms=on -mhe=on  -pfixture-only
    7z a encrypted-content.7z a.nfo movie.mkv -m0=Copy  -ms=on -mhe=off -pfixture-only
    7z a compressed.7z        a.nfo movie.mkv -m0=LZMA2 -ms=on

7-Zip never makes Copy archives solid (each file is its own folder), so `-ms=on` has no effect on the Copy
fixtures; the header is LZMA-packed in all of them (and AES-encrypted in `encrypted-headers.7z`).
