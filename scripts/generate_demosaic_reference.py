"""Generate the independent DDFAPD golden fixture used by the JVM tests.

Requires colour-demosaicing==0.2.7, colour-science==0.4.7, scipy==1.16.2 and numpy.
Reference: Colour v0.2.7 / commit 8e01cc06f111eda944ff8955bc9579e894ff142a.
The numerical reference is BSD-3-Clause; its notice ships in the app assets.
Run from the repository root. Only the fixture is written; no app code is imported.
"""
from pathlib import Path
import hashlib
import inspect
import json
import numpy as np
import colour_demosaicing
from colour_demosaicing import demosaicing_CFA_Bayer_Menon2007


def main():
    assert colour_demosaicing.__version__ == "0.2.7"
    source = Path(inspect.getfile(demosaicing_CFA_Bayer_Menon2007))
    cases = []
    for width, height in [(9, 7), (12, 10), (5, 4), (2, 2)]:
        # Integer arithmetic provides a reproducible non-symmetric mosaic; quantize once
        # to actual input Float32 before the external implementation promotes to Double.
        indices = np.arange(width * height, dtype=np.int64)
        samples = (((indices * 137 + indices * indices * 29 + 47) % 1021) / 613.0 - .25).astype(np.float32)
        for pattern in ("RGGB", "GRBG", "GBRG", "BGGR"):
            expected = demosaicing_CFA_Bayer_Menon2007(samples.reshape(height, width), pattern, refining_step=True)
            cases.append(dict(width=width, height=height, pattern=pattern,
                              input=samples.astype(float).tolist(), expected=expected.ravel().tolist()))
    target = Path("app/src/test/resources/stacking/ddfapd-menon2007-reference.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(dict(reference="colour-demosaicing 0.2.7; refining_step=True",
        commit="8e01cc06f111eda944ff8955bc9579e894ff142a",
        sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(), cases=cases),
        ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{target}: {len(cases)} cases; {target.stat().st_size} bytes")


if __name__ == "__main__":
    main()
