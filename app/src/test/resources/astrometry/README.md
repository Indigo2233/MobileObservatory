# Sparse CFA plate-solve regression

`weak-cfa.fits.gz` preserves the original 3856 × 2180, 8-bit RGGB camera frame
provided for the 2026-10-25 plate-solve investigation. Exposure is 2 s, pixel size
2.9 μm and focal length 450 mm. No observer identity or site coordinates are present.

- Original FITS SHA-256: `e3522dbf8b8ecacdc55b7738508ea8a772cecaa1e92a237057fb35463c7cbf47`.
- Independently verified 2×2 UInt16-sum pixel payload SHA-256:
  `4265d556ea58ba5c813c4cac6ce0821f864e0310d2b732e2ac12c15376468726`.
- ASTAP directly detects 4 stars. Integer 2×2 summation detects 7, with 15 matched
  quads when a full D20 database is available.
- Verified on Windows CLI 2026.09.19 and the actual Android arm64 CLI 2026.05.18.
  The Android native solve took about 1.2 s and returned RA 26.33455630945°,
  Dec −13.69720215663°.

JVM tests compare the streamed output with the independently produced payload and
check the WCS transform `p_original = 2*p_sum − 0.5`. Native Android tests reuse the
same original frame through the production runner and assert the 4-to-7-star retry.
The isolated native test intentionally lacks the matching catalog tiles and must
remain failed after detecting 7 stars. Full-database validation is recorded locally
under `bin/Diagnostics/fits-e3522db`.

The resource is included in test APK assets only. It is excluded from release APKs.
