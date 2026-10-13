#!/usr/bin/env python3
"""Reproduce the VNG test oracle using OpenCV 4.10.0, independently of Kotlin.

Requires numpy and opencv-python-headless==4.10.0.84. An isolated wheel directory
may be provided with --opencv-path. Run from any directory; paths default relative
to the repository. --write-fixture explicitly regenerates the committed oracle.
An optional --kotlin-rgb file contains four complete interleaved RGB Float images
(big-endian float32), CFA order RGGB, GRBG, GBRG, BGGR, 33x31 pixels, in DN units.

OpenCV 4.10.0 Bayer2RGB_VNG_8u advances its input by two rows, then writes source
(y+2,x) into output (y,x). This is observable in the returned image's *native*
channel, not merely an internal pipeline delay. We therefore compare Kotlin's
source-coordinate RGB[y,x] with OpenCV RGB[y-2,x]. The valid common area is
source y=4..height-3 and x=2..width-3 (both inclusive); no replicated borders are
compared. Kotlin instead preserves sensor coordinates and uses same-CFA bilinear
interpolation on its two-pixel border, including all pixels of images smaller
than 5 in either dimension. OpenCV falls back to bilinear below 8x8.

The source consists of multiples of 16, avoiding integer-gradient threshold
rounding differences. Its reconstructed range stays strictly within 0..255, so
the comparison does not hide OpenCV saturation. Kotlin intentionally retains
fractional samples, negatives, and values above one: only 8-bit oracle rounding
is tolerated (<=0.501 DN, i.e. <1/255 in normalized units).
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--opencv-path', type=Path)
parser.add_argument('--kotlin-rgb', type=Path)
parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/reports/stacking/vng')
parser.add_argument('--write-fixture', action='store_true')
args = parser.parse_args()
if args.opencv_path:
    sys.path.insert(0, str(args.opencv_path.resolve()))
import cv2
import numpy as np

assert cv2.__version__ == '4.10.0', cv2.__version__
width, height = 33, 31
raw = np.fromfunction(lambda y, x: 16 * (4 + (3*x + 5*y + (x*y % 7)) % 7),
                      (height, width), dtype=int).astype(np.uint8)
names = ['RGGB', 'GRBG', 'GBRG', 'BGGR']
oracle = np.stack([cv2.cvtColor(raw, getattr(cv2, 'COLOR_Bayer'+name+'2RGB_VNG'))
                   for name in names])
fixture_dir = ROOT / 'app/src/test/resources/stacking'
fixture = fixture_dir / 'vng-opencv-4.10.0.rgb8'
metadata = {
    'opencv_version': cv2.__version__, 'wheel': 'opencv-python-headless==4.10.0.84',
    'source_commit': '71d3237a093b60a27601c20e9ee6c3e52154e8b1',
    'source_sha256': 'a35d8384039f47b531906ca03c577e5fd93b4319a36206f797b9d0284285ea91',
    'width': width, 'height': height, 'cfa_order': names,
    'layout': 'four consecutive interleaved RGB unsigned 8-bit images; complete OpenCV returned images',
    'input_formula': 'raw[y,x] = 16*(4+(3*x+5*y+(x*y%7))%7)',
    'comparison': 'Kotlin[y,x] versus OpenCV[y-2,x]; x in 2..30, y in 4..28 inclusive',
    'border': 'OpenCV replicated borders excluded; Kotlin uses same-CFA bilinear two-pixel border',
    'tolerance_dn': 0.501,
}
if args.write_fixture:
    fixture_dir.mkdir(parents=True, exist_ok=True)
    fixture.write_bytes(oracle.tobytes())
    fixture.with_suffix('.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8')
assert fixture.read_bytes() == oracle.tobytes(), 'Committed oracle differs from official OpenCV'

ours = None
if args.kotlin_rgb:
    ours = np.fromfile(args.kotlin_rgb, dtype='>f4').reshape((4, height, width, 3))
results = []
for index, name in enumerate(names):
    rx, ry = index % 2, index // 2
    shifted_mismatch = unshifted_mismatch = native_count = 0
    for y in range(4, height-2):
        for x in range(2, width-2):
            c = 0 if (x % 2, y % 2) == (rx, ry) else 2 if x % 2 != rx and y % 2 != ry else 1
            native_count += 1
            shifted_mismatch += int(oracle[index, y-2, x, c] != raw[y, x])
            unshifted_mismatch += int(oracle[index, y, x, c] != raw[y, x])
            if ours is not None:
                assert ours[index, y, x, c] == raw[y, x]
    record = {'cfa': name, 'native_count': native_count,
              'opencv_shifted_native_mismatch': shifted_mismatch,
              'opencv_unshifted_native_mismatch': unshifted_mismatch}
    assert shifted_mismatch == 0 and unshifted_mismatch > 0, record
    if ours is not None:
        interior = ours[index, 4:height-2, 2:width-2].astype(float)
        delta = interior-oracle[index, 2:height-4, 2:width-2].astype(float)
        record.update(compared_channels=int(delta.size), max_abs_dn=float(abs(delta).max()),
                      interior_min=float(interior.min()), interior_max=float(interior.max()))
        assert record['max_abs_dn'] <= .501 and interior.min() > 0 and interior.max() < 255, record
    results.append(record)
report = dict(metadata, results=results,
              example={'raw_y6_x6': int(raw[6, 6]), 'raw_y8_x6': int(raw[8, 6]),
                       'opencv_y4_x6': oracle[0, 4, 6].tolist(),
                       'opencv_y6_x6': oracle[0, 6, 6].tolist()})
args.output_dir.mkdir(parents=True, exist_ok=True)
(args.output_dir / 'opencv-oracle.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, indent=2))
