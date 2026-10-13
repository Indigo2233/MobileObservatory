"""Independent stdlib FITS check; run after StackFitsWriterTest.

python scripts/verify_stack_fits.py app/build/reports/stacking/precision-rgb.fits
No production parsing code or third-party FITS package is reused.
"""
import json
import math
import pathlib
import struct
import sys


def read_header(handle):
    cards = {}
    for _ in range(12000):
        raw = handle.read(80)
        assert len(raw) == 80, "truncated header"
        card = raw.decode("ascii")
        keyword = card[:8].strip()
        if keyword == "END":
            handle.seek((handle.tell() + 2879) // 2880 * 2880)
            return cards
        if card[8:10] == "= ":
            cards[keyword] = card[10:].strip().strip("'").strip()
    raise AssertionError("missing END card")


def main(path):
    path = pathlib.Path(path)
    assert path.stat().st_size % 2880 == 0
    expected = [-.01, 0, 1 / 65535, 2 / 65535, .1, .2, .3, .4, .5,
                .6, .7, .8, .9, 1, 1.2, .001, .002, .003]
    with path.open("rb") as handle:
        header = read_header(handle)
        assert header["SIMPLE"] == "T"
        assert [int(header[k]) for k in ["BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3"]] == [-32, 3, 3, 2, 3]
        assert header["NCOMBINE"] == "19" and float(header["EXPTIME"]) == 9.5
        assert "BZERO" not in header and "BSCALE" not in header
        planar = struct.unpack(">18f", handle.read(18 * 4))
        interleaved = [planar[c * 6 + i] for i in range(6) for c in range(3)]
        error = max(abs(a - b) for a, b in zip(expected, interleaved))
        assert all(math.isfinite(v) for v in interleaved)
        assert error < 5e-8, f"sample error {error}"
        assert interleaved[2] != interleaved[3], "adjacent 16-bit input samples collapsed"
        assert interleaved[0] < 0 and interleaved[14] > 1, "linear dynamic range clipped"
        handle.seek((handle.tell() + 2879) // 2880 * 2880)
        extension = read_header(handle)
        assert extension["EXTNAME"] == "STACKMETA" and extension["XTENSION"] == "IMAGE"
        metadata = json.loads(handle.read(int(extension["NAXIS1"])).decode("utf-8"))
        assert metadata["source"] == "独立验证.ser"
        assert json.loads(metadata["processing"]) == {"curve": 1, "mode": "linear"}
    report = dict(file=str(path), independent_reader="Python stdlib struct/json",
                  planar_rgb=True, bitpix=-32, samples=18, max_absolute_error=error,
                  precision_preserved=True, linear_range_unclipped=True, metadata_utf8=True)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    path.with_suffix(".verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/stacking/precision-rgb.fits")
