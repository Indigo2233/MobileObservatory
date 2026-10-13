/*
 * DDFAPD / Menon (2007), including the refining step.
 * Kotlin port of Colour - Demosaicing 0.2.7, menon2007.py:
 * https://github.com/colour-science/colour-demosaicing/blob/8e01cc06f111eda944ff8955bc9579e894ff142a/colour_demosaicing/bayer/demosaicing/menon2007.py
 * Copyright 2015 Colour Developers. SPDX-License-Identifier: BSD-3-Clause
 * Full copyright, conditions and disclaimer: assets/THIRD_PARTY_COLOUR_DEMOSAICING.txt.
 * Modifications: interleaved Float32 output, bounded reusable Double scratch,
 * explicit CFA phase, row-level cancellation, no NumPy/SciPy dependency.
 */
package com.indigo.mobileobservatory.stacking

import kotlin.math.abs

/** Directional filtering AND a posteriori decision, followed by all three colour-difference
 * refinement steps. No clipping, white balance, quantization or display transfer function.
 * Border filters use whole-sample symmetry (SciPy mode="mirror" / NumPy pad="reflect");
 * the asymmetric direction-cost kernel instead uses zero extension, matching the reference.
 */
object DdfapdDemosaicer {
    /** Four Double planes and one Byte direction plane; excludes input and returned RGB. */
    fun extraBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0)
        return Math.addExact(Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), 33L), 4096L)
    }

    fun demosaic(src: FloatArray, width: Int, height: Int, redX: Int, redY: Int,
                 cancelCheck: () -> Unit = {}): LinearImage {
        require(width >= 2 && height >= 2) { "A Bayer image needs at least 2 x 2 pixels" }
        require(width.toLong() * height <= LinearImage.MAX_PIXELS && width.toLong() * height == src.size.toLong())
        require(redX in 0..1 && redY in 0..1)
        cancelCheck()
        val count = src.size
        val horizontal = DoubleArray(count)
        val vertical = DoubleArray(count)
        val deltaH = DoubleArray(count)
        val deltaV = DoubleArray(count)
        val direction = ByteArray(count) // 1 = horizontal, including equal-cost ties.
        val rgb = FloatArray(count * 3)

        // Five-tap green interpolation h0 + h1, then C = R-G or B-G.
        // Green samples have zero chroma in the decision planes, as in the paper/reference.
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val p = y * width + x
                val value = src[p].toDouble()
                require(value.isFinite()) { "Non-finite Bayer sample" }
                val colour = colour(x, y, redX, redY)
                rgb[p * 3 + colour] = src[p]
                if (colour == 1) continue
                val gh = .5 * (sample(src,width,height,x-1,y) + sample(src,width,height,x+1,y)) +
                    .5 * value - .25 * (sample(src,width,height,x-2,y) + sample(src,width,height,x+2,y))
                val gv = .5 * (sample(src,width,height,x,y-1) + sample(src,width,height,x,y+1)) +
                    .5 * value - .25 * (sample(src,width,height,x,y-2) + sample(src,width,height,x,y+2))
                horizontal[p] = value - gh
                vertical[p] = value - gv
            }
        }
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val p = y * width + x
                deltaH[p] = abs(horizontal[p] - horizontal[y * width + mirror(x+2,width)])
                deltaV[p] = abs(vertical[p] - vertical[mirror(y+2,height) * width + x])
            }
        }
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val p = y * width + x
                val chooseH = horizontalCost(deltaH,width,height,x,y) <= verticalCost(deltaV,width,height,x,y)
                direction[p] = if (chooseH) 1 else 0
                if (colour(x,y,redX,redY) != 1)
                    rgb[p*3+1] = (src[p] - if (chooseH) horizontal[p] else vertical[p]).toFloat()
            }
        }

        // At green sites each colour comes from neighbouring native samples on its own axis.
        // Only green sites are written, so these neighbours do not change during this pass.
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) if (colour(x,y,redX,redY) == 1) {
                val p = y * width + x
                val redHorizontal = (y and 1) == redY
                val r0 = neighbour(x,y,if (redHorizontal) -1 else 0,if (redHorizontal) 0 else -1,width,height)
                val r1 = neighbour(x,y,if (redHorizontal) 1 else 0,if (redHorizontal) 0 else 1,width,height)
                val b0 = neighbour(x,y,if (redHorizontal) 0 else -1,if (redHorizontal) -1 else 0,width,height)
                val b1 = neighbour(x,y,if (redHorizontal) 0 else 1,if (redHorizontal) 1 else 0,width,height)
                val g = rgb[p*3+1].toDouble()
                rgb[p*3] = (g + .5*(difference(rgb,r0,0,1)+difference(rgb,r1,0,1))).toFloat()
                rgb[p*3+2] = (g + .5*(difference(rgb,b0,2,1)+difference(rgb,b1,2,1))).toFloat()
            }
        }
        // R at blue sites and B at red sites use the selected direction and colour differences.
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val own = colour(x,y,redX,redY)
                if (own == 1) continue
                val p = y*width+x
                val dx = if (direction[p].toInt() == 1) 1 else 0
                val dy = 1-dx
                val a = neighbour(x,y,-dx,-dy,width,height)
                val b = neighbour(x,y,dx,dy,width,height)
                val missing = 2-own
                rgb[p*3+missing] = (rgb[p*3+own] + .5*(difference(rgb,a,missing,own)+difference(rgb,b,missing,own))).toFloat()
            }
        }

        // Refining 1/3: reconstruct green from three-tap directional colour differences.
        // Reuse the two decision planes; frozen differences prevent scan-order dependence.
        differences(rgb,horizontal,vertical,width,height,cancelCheck)
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val own = colour(x,y,redX,redY)
                if (own == 1) continue
                val p = y*width+x
                val dx = if (direction[p].toInt() == 1) 1 else 0
                val dy = 1-dx
                val a = neighbour(x,y,-dx,-dy,width,height)
                val b = neighbour(x,y,dx,dy,width,height)
                val chroma = if (own == 0) horizontal else vertical
                rgb[p*3+1] = (rgb[p*3+own] - (chroma[a]+chroma[p]+chroma[b])/3.0).toFloat()
            }
        }

        // Refining 2/3: update R and B at green sites using the refined green plane.
        differences(rgb,horizontal,vertical,width,height,cancelCheck)
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) if (colour(x,y,redX,redY) == 1) {
                val p = y*width+x
                val redHorizontal = (y and 1) == redY
                val r0 = neighbour(x,y,if (redHorizontal) -1 else 0,if (redHorizontal) 0 else -1,width,height)
                val r1 = neighbour(x,y,if (redHorizontal) 1 else 0,if (redHorizontal) 0 else 1,width,height)
                val b0 = neighbour(x,y,if (redHorizontal) 0 else -1,if (redHorizontal) -1 else 0,width,height)
                val b1 = neighbour(x,y,if (redHorizontal) 0 else 1,if (redHorizontal) 1 else 0,width,height)
                val g = rgb[p*3+1].toDouble()
                rgb[p*3] = (g + .5*(horizontal[r0]+horizontal[r1])).toFloat()
                rgb[p*3+2] = (g + .5*(vertical[b0]+vertical[b1])).toFloat()
            }
        }

        // Refining 3/3: three-tap R-B differences at the opposite-colour locations.
        // Freeze R-B before either channel is updated, exactly as in the reference.
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val p = y*width+x
                horizontal[p] = difference(rgb,p,0,2)
            }
        }
        for (y in 0 until height) {
            cancelCheck()
            for (x in 0 until width) {
                val own = colour(x,y,redX,redY)
                if (own == 1) continue
                val p = y*width+x
                val dx = if (direction[p].toInt() == 1) 1 else 0
                val dy = 1-dx
                val a = neighbour(x,y,-dx,-dy,width,height)
                val b = neighbour(x,y,dx,dy,width,height)
                val chroma = (horizontal[a]+horizontal[p]+horizontal[b])/3.0
                if (own == 2) rgb[p*3] = (rgb[p*3+2]+chroma).toFloat()
                else rgb[p*3+2] = (rgb[p*3]-chroma).toFloat()
            }
        }
        cancelCheck()
        return LinearImage(width,height,3,rgb)
    }

    private fun colour(x: Int, y: Int, rx: Int, ry: Int): Int = when {
        (x and 1) == rx && (y and 1) == ry -> 0
        (x and 1) != rx && (y and 1) != ry -> 2
        else -> 1
    }

    private fun mirror(index: Int, size: Int): Int {
        if (index in 0 until size) return index
        val period = 2*(size-1)
        val folded = ((index % period)+period) % period
        return if (folded < size) folded else period-folded
    }

    private fun neighbour(x: Int, y: Int, dx: Int, dy: Int, w: Int, h: Int) = mirror(y+dy,h)*w+mirror(x+dx,w)
    private fun sample(a: FloatArray, w: Int, h: Int, x: Int, y: Int) = a[mirror(y,h)*w+mirror(x,w)].toDouble()
    private fun zero(a: DoubleArray, w: Int, h: Int, x: Int, y: Int) = if (x in 0 until w && y in 0 until h) a[y*w+x] else 0.0
    private fun difference(rgb: FloatArray, p: Int, a: Int, b: Int) = rgb[p*3+a].toDouble()-rgb[p*3+b].toDouble()

    // scipy.ndimage.convolve reverses its kernel. These are the eight nonzero taps
    // of the reference's asymmetric 5x5 k and transpose(k), with zero outside the frame.
    private fun horizontalCost(a: DoubleArray,w: Int,h: Int,x: Int,y: Int): Double =
        zero(a,w,h,x,y+2)+zero(a,w,h,x-2,y+2)+zero(a,w,h,x-1,y+1)+
        3*zero(a,w,h,x,y)+3*zero(a,w,h,x-2,y)+zero(a,w,h,x-1,y-1)+
        zero(a,w,h,x,y-2)+zero(a,w,h,x-2,y-2)

    private fun verticalCost(a: DoubleArray,w: Int,h: Int,x: Int,y: Int): Double =
        zero(a,w,h,x+2,y)+zero(a,w,h,x+2,y-2)+zero(a,w,h,x+1,y-1)+
        3*zero(a,w,h,x,y)+3*zero(a,w,h,x,y-2)+zero(a,w,h,x-1,y-1)+
        zero(a,w,h,x-2,y)+zero(a,w,h,x-2,y-2)

    private fun differences(rgb: FloatArray, redGreen: DoubleArray, blueGreen: DoubleArray,
                            w: Int,h: Int,cancelCheck: () -> Unit) {
        for (y in 0 until h) {
            cancelCheck()
            for (x in 0 until w) {
                val p = y*w+x
                redGreen[p] = difference(rgb,p,0,1)
                blueGreen[p] = difference(rgb,p,2,1)
            }
        }
    }
}
