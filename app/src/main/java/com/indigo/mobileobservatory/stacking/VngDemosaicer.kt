/*M///////////////////////////////////////////////////////////////////////////////////////
//
//  IMPORTANT: READ BEFORE DOWNLOADING, COPYING, INSTALLING OR USING.
//
//  By downloading, copying, installing or using the software you agree to this license.
//  If you do not agree to this license, do not download, install,
//  copy or use the software.
//
//
//                           License Agreement
//                For Open Source Computer Vision Library
//
// Copyright (C) 2000-2008, Intel Corporation, all rights reserved.
// Copyright (C) 2009-2010, Willow Garage Inc., all rights reserved.
// Copyright (C) 2014, Itseez Inc., all rights reserved.
// Third party copyrights are property of their respective owners.
//
// Redistribution and use in source and binary forms, with or without modification,
// are permitted provided that the following conditions are met:
//
//   * Redistribution's of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//
//   * Redistribution's in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//
//   * The name of the copyright holders may not be used to endorse or promote products
//     derived from this software without specific prior written permission.
//
// This software is provided by the copyright holders and contributors "as is" and
// any express or implied warranties, including, but not limited to, the implied
// warranties of merchantability and fitness for a particular purpose are disclaimed.
// In no event shall the Intel Corporation or contributors be liable for any direct,
// indirect, incidental, special, exemplary, or consequential damages
// (including, but not limited to, procurement of substitute goods or services;
// loss of use, data, or profits; or business interruption) however caused
// and on any theory of liability, whether in contract, strict liability,
// or tort (including negligence or otherwise) arising in any way out of
// the use of this software, even if advised of the possibility of such damage.
//
//M*/

/********************************* COPYRIGHT NOTICE *******************************\
  Original code for Bayer->BGR/RGB conversion is provided by Dirk Schaefer
  from MD-Mathematische Dienste GmbH. Below is the copyright notice:

    IMPORTANT: READ BEFORE DOWNLOADING, COPYING, INSTALLING OR USING.
    By downloading, copying, installing or using the software you agree
    to this license. If you do not agree to this license, do not download,
    install, copy or use the software.

    Contributors License Agreement:

      Copyright (c) 2002,
      MD-Mathematische Dienste GmbH
      Im Defdahl 5-10
      44141 Dortmund
      Germany
      www.md-it.de

    Redistribution and use in source and binary forms,
    with or without modification, are permitted provided
    that the following conditions are met:

    Redistributions of source code must retain
    the above copyright notice, this list of conditions and the following disclaimer.
    Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.
    The name of Contributor may not be used to endorse or promote products
    derived from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
    THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
    PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
    OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
    HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
    STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
    THE POSSIBILITY OF SUCH DAMAGE.
\**********************************************************************************/


package com.indigo.mobileobservatory.stacking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Floating-point adaptation of OpenCV 4.10.0's scalar Bayer2RGB_VNG_8u.
 * Pinned source: opencv/opencv@71d3237a093b60a27601c20e9ee6c3e52154e8b1,
 * modules/imgproc/src/demosaicing.cpp. See assets/THIRD_PARTY_OPENCV_VNG.txt.
 *
 * Uses the source's eight directional gradients and selected-neighbour colour differences.
 * Changes: Double arithmetic over linear Float samples, no integer threshold floor/rounding/
 * saturation, direct source coordinates (no upstream two-row input displacement), and a
 * same-CFA bilinear two-pixel border instead of replicated output rows. Native samples remain
 * bit-exact, including calibrated negatives and values above one. No full-frame scratch.
 */
object VngDemosaicer {
    /** Additional peak storage beyond caller's CFA and returned RGB arrays. */
    fun extraBytes(width: Int, height: Int): Long {
        validateDimensions(width, height)
        return if (width < 5 || height < 5) 4096L else width.toLong() * 3 * 7 * 8 + 4096L
    }

    fun demosaic(src: FloatArray, width: Int, height: Int, redX: Int, redY: Int,
                 cancelCheck: () -> Unit = {}): LinearImage {
        validateDimensions(width, height)
        require(redX in 0..1 && redY in 0..1) { "Invalid output CFA phase" }
        require(src.size.toLong() == width.toLong() * height) { "Invalid CFA buffer length" }
        cancelCheck()
        for (i in src.indices) {
            if (i % 16384 == 0) cancelCheck()
            require(src[i].isFinite()) { "Nonfinite CFA sample" }
        }
        val dst = FloatArray(src.size * 3)
        // Complete same-colour neighbours at image edges, never parity-changing clamping.
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                if (x % 1024 == 0) cancelCheck()
                if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) continue
                val p = y * width + x
                val own = color(x, y, redX, redY)
                for (c in 0..2) {
                    if (c == own) { dst[p * 3 + c] = src[p]; continue }
                    var sum = 0.0; var count = 0
                    for (yy in max(0, y - 1)..min(height - 1, y + 1)) {
                        for (xx in max(0, x - 1)..min(width - 1, x + 1)) {
                            if (color(xx, yy, redX, redY) == c) { sum += src[yy * width + xx]; count++ }
                        }
                    }
                    check(count > 0)
                    dst[p * 3 + c] = finiteFloat(sum / count)
                }
            }
        }
        if (width < 5 || height < 5) return LinearImage(width, height, 3, dst)

        // Seven gradient/sum planes in a three-row ring, shared by all eight directions.
        val rowStride = width * 7
        val gradients = DoubleArray(rowStride * 3)
        fun row(index: Int) = (index % 3) * rowStride
        fun gradientRow(y: Int) {
            val base = row(y)
            for (x in 1 until width - 1) {
                if (x % 1024 == 0) cancelCheck()
                val p = y * width + x
                val nw = src[p - width - 1].toDouble(); val n = src[p - width].toDouble(); val ne = src[p - width + 1].toDouble()
                val w = src[p - 1].toDouble(); val e = src[p + 1].toDouble()
                val sw = src[p + width - 1].toDouble(); val s = src[p + width].toDouble(); val se = src[p + width + 1].toDouble()
                val g = base + x
                gradients[g] = abs(nw - sw) + 2 * abs(n - s) + abs(ne - se)
                gradients[g + width] = abs(nw - ne) + 2 * abs(w - e) + abs(sw - se)
                gradients[g + width * 2] = 2 * abs(ne - sw)
                gradients[g + width * 3] = 2 * abs(nw - se)
                gradients[g + width * 4] = gradients[g + width * 2] + abs(n - w) + abs(s - e)
                gradients[g + width * 5] = gradients[g + width * 3] + abs(n - e) + abs(s - w)
                gradients[g + width * 6] = (n + w + e + s) * .5
            }
        }
        gradientRow(1); gradientRow(2)
        val direction = DoubleArray(8)
        val w = width; val w2 = width * 2
        for (y in 2 until height - 2) {
            cancelCheck()
            gradientRow(y + 1)
            for (x in 2 until width - 2) {
                if (x % 1024 == 0) cancelCheck()
                val p = y * w + x
                val b0 = row(y - 1) + x; val b1 = row(y) + x; val b2 = row(y + 1) + x
                val own = color(x, y, redX, redY)
                direction[0] = gradients[b0] + gradients[b1] // N
                direction[1] = gradients[b1] + gradients[b2] // S
                direction[2] = gradients[b1 + w - 1] + gradients[b1 + w] // W
                direction[3] = gradients[b1 + w] + gradients[b1 + w + 1] // E
                if (own != 1) {
                    direction[4] = gradients[b0 + w * 4 + 1] + gradients[b1 + w * 4] // NE
                    direction[5] = gradients[b1 + w * 4] + gradients[b2 + w * 4 - 1] // SW
                    direction[6] = gradients[b0 + w * 5 - 1] + gradients[b1 + w * 5] // NW
                    direction[7] = gradients[b1 + w * 5] + gradients[b2 + w * 5 + 1] // SE
                } else {
                    direction[4] = gradients[b0 + w2] + gradients[b0 + w2 + 1] + gradients[b1 + w2] + gradients[b1 + w2 + 1]
                    direction[5] = gradients[b1 + w2] + gradients[b1 + w2 - 1] + gradients[b2 + w2] + gradients[b2 + w2 - 1]
                    direction[6] = gradients[b0 + w * 3] + gradients[b0 + w * 3 - 1] + gradients[b1 + w * 3] + gradients[b1 + w * 3 - 1]
                    direction[7] = gradients[b1 + w * 3] + gradients[b1 + w * 3 + 1] + gradients[b2 + w * 3] + gradients[b2 + w * 3 + 1]
                }
                var smallest = direction[0]; var largest = direction[0]
                for (i in 1..7) { smallest = min(smallest, direction[i]); largest = max(largest, direction[i]) }
                // No 8-bit 1-DN threshold floor: selection is invariant to linear signal scale.
                val threshold = smallest + largest * .5
                var rs = 0.0; var gs = 0.0; var bs = 0.0; var used = 0
                val center = src[p].toDouble()
                fun s(offset: Int) = src[p + offset].toDouble()
                for (d in 0..7) {
                    if (largest != 0.0 && direction[d] >= threshold) continue
                    used++
                    if (own != 1) {
                        when (d) {
                            0 -> { rs += s(-w2) + center; gs += s(-w) * 2; bs += s(-w - 1) + s(-w + 1) }
                            1 -> { rs += s(w2) + center; gs += s(w) * 2; bs += s(w - 1) + s(w + 1) }
                            2 -> { rs += s(-2) + center; gs += s(-1) * 2; bs += s(-w - 1) + s(w - 1) }
                            3 -> { rs += s(2) + center; gs += s(1) * 2; bs += s(-w + 1) + s(w + 1) }
                            4 -> { rs += s(-w2 + 2) + center; gs += gradients[b0 + w * 6 + 1]; bs += s(-w + 1) * 2 }
                            5 -> { rs += s(w2 - 2) + center; gs += gradients[b2 + w * 6 - 1]; bs += s(w - 1) * 2 }
                            6 -> { rs += s(-w2 - 2) + center; gs += gradients[b0 + w * 6 - 1]; bs += s(-w - 1) * 2 }
                            7 -> { rs += s(w2 + 2) + center; gs += gradients[b2 + w * 6 + 1]; bs += s(w + 1) * 2 }
                        }
                    } else {
                        when (d) {
                            0 -> { rs += s(-w2 - 1) + s(-w2 + 1); gs += s(-w2) + center; bs += s(-w) * 2 }
                            1 -> { rs += s(w2 - 1) + s(w2 + 1); gs += s(w2) + center; bs += s(w) * 2 }
                            2 -> { rs += s(-1) * 2; gs += s(-2) + center; bs += s(-w - 2) + s(w - 2) }
                            3 -> { rs += s(1) * 2; gs += s(2) + center; bs += s(-w + 2) + s(w + 2) }
                            4 -> { rs += s(-w2 + 1) + s(1); gs += s(-w + 1) * 2; bs += s(-w) + s(-w + 2) }
                            5 -> { rs += s(w2 - 1) + s(-1); gs += s(w - 1) * 2; bs += s(w) + s(w - 2) }
                            6 -> { rs += s(-w2 - 1) + s(-1); gs += s(-w - 1) * 2; bs += s(-w - 2) + s(-w) }
                            7 -> { rs += s(w2 + 1) + s(1); gs += s(w + 1) * 2; bs += s(w + 2) + s(w) }
                        }
                    }
                }
                check(used > 0)
                val scale = .5 / used
                if (own != 1) {
                    dst[p * 3 + own] = src[p]
                    dst[p * 3 + 1] = finiteFloat(center + (gs - rs) * scale)
                    dst[p * 3 + (2 - own)] = finiteFloat(center + (bs - rs) * scale)
                } else {
                    val horizontal = if (y and 1 == redY) 0 else 2
                    dst[p * 3 + 1] = src[p]
                    dst[p * 3 + horizontal] = finiteFloat(center + (rs - gs) * scale)
                    dst[p * 3 + (2 - horizontal)] = finiteFloat(center + (bs - gs) * scale)
                }
            }
        }
        cancelCheck()
        return LinearImage(width, height, 3, dst)
    }

    private fun color(x: Int, y: Int, redX: Int, redY: Int): Int = when {
        (x and 1) == redX && (y and 1) == redY -> 0
        (x and 1) != redX && (y and 1) != redY -> 2
        else -> 1
    }
    private fun finiteFloat(value: Double): Float = value.toFloat().also { require(it.isFinite()) { "Demosaic output exceeds Float range" } }
    private fun validateDimensions(width: Int, height: Int) {
        require(width >= 2 && height >= 2 && width.toLong() * height <= LinearImage.MAX_PIXELS) { "Invalid Bayer dimensions" }
    }
}
