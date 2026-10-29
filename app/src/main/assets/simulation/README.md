# Virtual observation image source

`imx585-field.raw8.gz` matches the portable test fixture in `app/src/test/resources/guide/` byte for byte (SHA-256 `71d5b14724645fc43d0eb401a8b83556759b67fee8c34434d8e4cca313174355`). It is the user's 1024 × 768 IMX585 RGGB crop. Acquisition and source-file provenance are recorded in that fixture's README and JSON.

Interactive simulation converts CFA cells to luminance, translates and periodically tiles the field. Simulated camera pose supplies the synthetic solutions; this image is not a full-sky catalog or evidence of ASTAP accuracy.

AAPT expands the `.gz` source during packaging. Android reads the packaged asset as `simulation/imx585-field.raw8` (786,432 bytes), without GZIP decoding. The end-to-end virtual connection test exercises this packaged asset.
