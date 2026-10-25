# UInt16 PNG fixtures

Generated independently of the Android codec using the desktop JDK ImageIO PNG writer:

```
java scripts/tests/GeneratePng16Fixtures.java app/src/test/resources/media-u16
```

Each image is 19 × 13. `external-<interlaced>-<channels>.png` covers grayscale/RGB with optional alpha,
ordinary/Adam7. Channel samples are `(x*137 + y*277 + channel*10009) & 65535`.
These files test importing externally filtered/interlaced 16-bit PNGs. Export
tests independently inspect big-endian samples in inflated PNG scanlines.
