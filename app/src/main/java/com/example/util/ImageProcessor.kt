package com.example.util

import android.graphics.*
import kotlin.math.*

object ImageProcessor {

    // --- CAMERA RAW ADJUSTMENTS ---
    fun applyCameraRaw(
        src: Bitmap,
        exposure: Float,      // -2.0 to 2.0
        contrast: Float,      // -1.0 to 1.0
        highlights: Float,    // -1.0 to 1.0
        shadows: Float,       // -1.0 to 1.0
        whites: Float,        // -1.0 to 1.0
        blacks: Float,        // -1.0 to 1.0
        clarity: Float,       // 0.0 to 1.0 (approximated with simple edge contrast)
        vibrance: Float,      // -1.0 to 1.0
        saturation: Float,    // 0.0 to 2.0
        temperature: Float,   // -1.0 to 1.0 (yellow <-> blue)
        tint: Float,          // -1.0 to 1.0 (magenta <-> green)
        noiseReduction: Float, // 0.0 to 1.0
        sharpness: Float      // 0.0 to 1.0
    ): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Precompute RAW adjustment variables
        // Exp: 2^exposure factor
        val expFactor = 2.0f.pow(exposure)
        val contFactor = contrast + 1.0f
        val satFactor = saturation

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color shr 24) and 0xFF
            var r = (color shr 16) and 0xFF
            var g = (color shr 8) and 0xFF
            var b = color and 0xFF

            // 1. Exposure
            var rF = r * expFactor
            var gF = g * expFactor
            var bF = b * expFactor

            // 2. Temperature & Tint
            // Temp shifts red/yellow (warm) vs blue (cool)
            if (temperature > 0) {
                rF += temperature * 25f
                bF -= temperature * 15f
            } else if (temperature < 0) {
                bF += -temperature * 25f
                rF -= -temperature * 15f
            }
            // Tint shifts green vs magenta (red+blue)
            if (tint > 0) {
                gF += tint * 20f
                rF -= tint * 10f
                bF -= tint * 10f
            } else if (tint < 0) {
                rF += -tint * 15f
                bF += -tint * 15f
                gF -= -tint * 15f
            }

            // 3. Contrast (around middle gray 127.5)
            rF = 127.5f + (rF - 127.5f) * contFactor
            gF = 127.5f + (gF - 127.5f) * contFactor
            bF = 127.5f + (bF - 127.5f) * contFactor

            // 4. Highlights / Shadows, Whites / Blacks
            val luma = 0.299f * rF + 0.587f * gF + 0.114f * bF
            if (luma > 128f) {
                // Highlight adjustment
                val highlightEffect = (luma - 128f) / 127f
                val hMod = 1f + highlights * 0.5f * highlightEffect
                val wMod = 1f + whites * 0.4f * highlightEffect
                rF *= (hMod * wMod)
                gF *= (hMod * wMod)
                bF *= (hMod * wMod)
            } else {
                // Shadow adjustment
                val shadowEffect = (128f - luma) / 128f
                val sMod = 1f + shadows * 0.5f * shadowEffect
                val bMod = 1f + blacks * 0.4f * shadowEffect
                rF *= (sMod * bMod)
                gF *= (sMod * bMod)
                bF *= (sMod * bMod)
            }

            // 5. Saturation & Vibrance (Vibrance boosts less saturated pixels more)
            var rBound = clamp(rF, 0f, 255f)
            var gBound = clamp(gF, 0f, 255f)
            var bBound = clamp(bF, 0f, 255f)

            val maxVal = maxOf(rBound, maxOf(gBound, bBound))
            val minVal = minOf(rBound, minOf(gBound, bBound))
            val currentLuma = 0.299f * rBound + 0.587f * gBound + 0.114f * bBound
            val currentSat = if (maxVal == 0f) 0f else (maxVal - minVal) / maxVal

            // Adjust based on Saturation + Vibrance
            val activeSatFactor = satFactor + vibrance * (1f - currentSat)
            rBound = currentLuma + (rBound - currentLuma) * activeSatFactor
            gBound = currentLuma + (gBound - currentLuma) * activeSatFactor
            bBound = currentLuma + (bBound - currentLuma) * activeSatFactor

            pixels[i] = (a shl 24) or (clamp(rBound, 0f, 255f).toInt() shl 16) or (clamp(gBound, 0f, 255f).toInt() shl 8) or clamp(bBound, 0f, 255f).toInt()
        }

        dest.setPixels(pixels, 0, width, 0, 0, width, height)

        // Apply secondary passes (Noise Reduction or Sharpness)
        var result = dest
        if (noiseReduction > 0f) {
            result = applySmoothBlur(result, (noiseReduction * 5f).toInt().coerceAtLeast(1))
        }
        if (sharpness > 0f) {
            result = applySharpen(result, sharpness)
        }

        return result
    }

    // --- BLUR FILTERS (FLU) ---

    // Simple Box Blur / Smooth Blur
    fun applySmoothBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val size = radius * 2 + 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var sumA = 0
                var count = 0

                for (ky in -radius..radius) {
                    val pxY = (y + ky).coerceIn(0, height - 1)
                    for (kx in -radius..radius) {
                        val pxX = (x + kx).coerceIn(0, width - 1)
                        val col = pixels[pxY * width + pxX]
                        sumA += (col shr 24) and 0xFF
                        sumR += (col shr 16) and 0xFF
                        sumG += (col shr 8) and 0xFF
                        sumB += col and 0xFF
                        count++
                    }
                }
                val avgR = sumR / count
                val avgG = sumG / count
                val avgB = sumB / count
                val avgA = sumA / count
                outPixels[y * width + x] = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applyGaussianBlur(src: Bitmap, radius: Int): Bitmap {
        // Approximating Gaussian Blur via multiple Box Blur iterations (very fast and typical)
        var result = src
        val passes = 3
        val subRadius = (radius / passes).coerceAtLeast(1)
        for (i in 0 until passes) {
            result = applySmoothBlur(result, subRadius)
        }
        return result
    }

    fun applyMotionBlur(src: Bitmap, distance: Int, angleDegrees: Float): Bitmap {
        if (distance <= 0) return src
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val rads = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(rads)
        val dy = sin(rads)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumA = 0f
                var count = 0

                for (i in -distance..distance) {
                    val pxX = (x + (i * dx).roundToInt()).coerceIn(0, width - 1)
                    val pxY = (y + (i * dy).roundToInt()).coerceIn(0, height - 1)
                    val col = pixels[pxY * width + pxX]
                    sumA += (col shr 24) and 0xFF
                    sumR += (col shr 16) and 0xFF
                    sumG += (col shr 8) and 0xFF
                    sumB += col and 0xFF
                    count++
                }

                outPixels[y * width + x] = (clamp(sumA / count, 0f, 255f).toInt() shl 24) or
                        (clamp(sumR / count, 0f, 255f).toInt() shl 16) or
                        (clamp(sumG / count, 0f, 255f).toInt() shl 8) or
                        clamp(sumB / count, 0f, 255f).toInt()
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applyRadialBlur(src: Bitmap, amount: Float): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val stepCount = 8

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumA = 0f

                val dx = x - centerX
                val dy = y - centerY

                for (step in 0 until stepCount) {
                    val scale = 1.0f - (amount * 0.05f * (step.toFloat() / stepCount))
                    val pxX = (centerX + dx * scale).roundToInt().coerceIn(0, width - 1)
                    val pxY = (centerY + dy * scale).roundToInt().coerceIn(0, height - 1)

                    val col = pixels[pxY * width + pxX]
                    sumA += (col shr 24) and 0xFF
                    sumR += (col shr 16) and 0xFF
                    sumG += (col shr 8) and 0xFF
                    sumB += col and 0xFF
                }

                outPixels[y * width + x] = (clamp(sumA / stepCount, 0f, 255f).toInt() shl 24) or
                        (clamp(sumR / stepCount, 0f, 255f).toInt() shl 16) or
                        (clamp(sumG / stepCount, 0f, 255f).toInt() shl 8) or
                        clamp(sumB / stepCount, 0f, 255f).toInt()
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- RENDERING FILTERS (RENDU) ---

    // 100% Offline noise clouds
    fun applyClouds(width: Int, height: Int): Bitmap {
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Simple Perlin-esque value noise for generating fluffy clouds local texture
        fun noise(x: Float, y: Float): Float {
            val cellX = x.toInt()
            val cellY = y.toInt()
            val fracX = x - cellX
            val fracY = y - cellY

            // Pseudo-random values at cell corners
            fun rand(cx: Int, cy: Int): Float {
                val n = (sin(cx * 12.9898 + cy * 78.233) * 43758.5453).toFloat()
                return (n - n.toInt().toFloat()).absoluteValue
            }

            val v00 = rand(cellX, cellY)
            val v10 = rand(cellX + 1, cellY)
            val v01 = rand(cellX, cellY + 1)
            val v11 = rand(cellX + 1, cellY + 1)

            // Bilinear interpolation
            val tx = fracX * fracX * (3.0f - 2.0f * fracX) // cubic smoothstep
            val ty = fracY * fracY * (3.0f - 2.0f * fracY)

            val i1 = v00 + tx * (v10 - v00)
            val i2 = v01 + tx * (v11 - v01)

            return i1 + ty * (i2 - i1)
        }

        fun fbm(x: Float, y: Float): Float {
            var total = 0f
            var freq = 0.05f
            var amp = 1.0f
            var maxAmp = 0f
            for (i in 0..4) {
                total += noise(x * freq, y * freq) * amp
                maxAmp += amp
                freq *= 2.1f
                amp *= 0.5f
            }
            return total / maxAmp
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val nVal = fbm(x.toFloat(), y.toFloat())
                // Interpolate between sky blue (e.g., #3A8EED) and cloud white
                val r = (0x3A + nVal * (0xFF - 0x3A)).toInt().coerceIn(0, 255)
                val g = (0x8E + nVal * (0xFF - 0x8E)).toInt().coerceIn(0, 255)
                val b = (0xED + nVal * (0xFF - 0xED)).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    // Spotlighting effect
    fun applyLighting(src: Bitmap, lightX: Float, lightY: Float, radius: Float, intensity: Float): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - lightX
                val dy = y - lightY
                val dist = sqrt(dx * dx + dy * dy)

                // Light attenuation factor (hotspot in middle, trailing off)
                val atten = if (dist >= radius) {
                    0.2f // Ambient level
                } else {
                    val ratio = 1.0f - (dist / radius)
                    0.2f + 0.8f * ratio * intensity
                }

                val col = pixels[y * width + x]
                val a = (col shr 24) and 0xFF
                val r = (((col shr 16) and 0xFF) * atten).toInt().coerceIn(0, 255)
                val g = (((col shr 8) and 0xFF) * atten).toInt().coerceIn(0, 255)
                val b = ((col and 0xFF) * atten).toInt().coerceIn(0, 255)

                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- SHARPNESS FILTERS (NETTETÉ) ---

    // Highpass-based visual unsharp mask
    fun applyUnsharpMask(src: Bitmap, amount: Float): Bitmap {
        val blurred = applySmoothBlur(src, 2)
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val originalPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)

        src.getPixels(originalPixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

        for (i in originalPixels.indices) {
            val orig = originalPixels[i]
            val blur = blurredPixels[i]

            val oa = (orig shr 24) and 0xFF
            val or = (orig shr 16) and 0xFF
            val og = (orig shr 8) and 0xFF
            val ob = orig and 0xFF

            val br = (blur shr 16) and 0xFF
            val bg = (blur shr 8) and 0xFF
            val bb = blur and 0xFF

            // Unsharp: original + amount * (original - blurred)
            val nr = or + amount * (or - br)
            val ng = og + amount * (og - bg)
            val nb = ob + amount * (ob - bb)

            outPixels[i] = (oa shl 24) or
                    (clamp(nr, 0f, 255f).toInt() shl 16) or
                    (clamp(ng, 0f, 255f).toInt() shl 8) or
                    clamp(nb, 0f, 255f).toInt()
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applySharpen(src: Bitmap, strength: Float): Bitmap {
        // Direct laplacian sharpening kernel
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    outPixels[y * width + x] = pixels[y * width + x]
                    continue
                }

                // Neighborhood evaluation
                val center = pixels[y * width + x]
                val top = pixels[(y - 1) * width + x]
                val bottom = pixels[(y + 1) * width + x]
                val left = pixels[y * width + x - 1]
                val right = pixels[y * width + x + 1]

                val ca = (center shr 24) and 0xFF
                val cr = (center shr 16) and 0xFF
                val cg = (center shr 8) and 0xFF
                val cb = center and 0xFF

                // Sum of cross offsets (Laplacian factor)
                fun comp(shift: Int): Int {
                    val cen = (center shr shift) and 0xFF
                    val t = (top shr shift) and 0xFF
                    val b = (bottom shr shift) and 0xFF
                    val l = (left shr shift) and 0xFF
                    val r = (right shr shift) and 0xFF
                    val laplacian = 5f * cen - (t + b + l + r)
                    return clamp(cen + laplacian * strength, 0f, 255f).toInt()
                }

                outPixels[y * width + x] = (ca shl 24) or (comp(16) shl 16) or (comp(8) shl 8) or comp(0)
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- DEFORMATION FILTERS (DÉFORMATION) ---

    fun applyRipple(src: Bitmap, waveCount: Float, amplitude: Float): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Shift x using a sine wave based on y
                val shiftX = sin(y / height.toFloat() * waveCount * 2 * Math.PI) * amplitude
                val srcX = (x + shiftX).roundToInt().coerceIn(0, width - 1)
                outPixels[y * width + x] = pixels[y * width + srcX]
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applySpherize(src: Bitmap, radiusPercent: Float): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val maxDim = max(width, height).toFloat()
        val radius = maxDim * 0.5f * radiusPercent.coerceIn(0.1f, 1.0f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val dist = sqrt(dx * dx + dy * dy)

                if (dist < radius) {
                    val theta = atan2(dy, dx)
                    // Push coordinates outwards to form a sphere bulge
                    val ratio = dist / radius
                    val sphereRatio = ratio * ratio * ratio // Cubic warp
                    val warpDist = sphereRatio * radius
                    val srcX = (centerX + warpDist * cos(theta)).roundToInt().coerceIn(0, width - 1)
                    val srcY = (centerY + warpDist * sin(theta)).roundToInt().coerceIn(0, height - 1)
                    outPixels[y * width + x] = pixels[srcY * width + srcX]
                } else {
                    outPixels[y * width + x] = pixels[y * width + x]
                }
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- NOISE FILTERS (BRUIT) ---

    fun applyAddNoise(src: Bitmap, amountPercent: Float): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val rand = java.util.Random()
        val noiseRange = (amountPercent * 255).toInt()

        for (i in pixels.indices) {
            val col = pixels[i]
            val a = (col shr 24) and 0xFF
            var r = (col shr 16) and 0xFF
            var g = (col shr 8) and 0xFF
            var b = col and 0xFF

            if (noiseRange > 0) {
                val rNoise = rand.nextInt(noiseRange * 2) - noiseRange
                r = (r + rNoise).coerceIn(0, 255)
                g = (g + rNoise).coerceIn(0, 255) // Monochromatic noise
                b = (b + rNoise).coerceIn(0, 255)
            }

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- OTHER STYLIZED FILTERS ---

    fun applyGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val col = pixels[i]
            val a = (col shr 24) and 0xFF
            val r = (col shr 16) and 0xFF
            val g = (col shr 8) and 0xFF
            val b = col and 114

            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applySepia(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val col = pixels[i]
            val a = (col shr 24) and 0xFF
            val r = (col shr 16) and 0xFF
            val g = (col shr 8) and 0xFF
            val b = col and 0xFF

            val tr = (0.393f * r + 0.769f * g + 0.189f * b).toInt().coerceIn(0, 255)
            val tg = (0.349f * r + 0.686f * g + 0.168f * b).toInt().coerceIn(0, 255)
            val tb = (0.272f * r + 0.534f * g + 0.131f * b).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (tr shl 16) or (tg shl 8) or tb
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applyNegative(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val col = pixels[i]
            val a = (col shr 24) and 0xFF
            val r = 255 - ((col shr 16) and 0xFF)
            val g = 255 - ((col shr 8) and 0xFF)
            val b = 255 - (col and 0xFF)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applyPosterize(src: Bitmap, levels: Int): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        if (levels < 2) return src
        val interval = 255 / (levels - 1)

        for (i in pixels.indices) {
            val col = pixels[i]
            val a = (col shr 24) and 0xFF
            var r = (col shr 16) and 0xFF
            var g = (col shr 8) and 0xFF
            var b = col and 0xFF

            r = ((r / interval) * interval).coerceIn(0, 255)
            g = ((g / interval) * interval).coerceIn(0, 255)
            b = ((b / interval) * interval).coerceIn(0, 255)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    fun applyEdgeDetection(src: Bitmap): Bitmap {
        // High performance Sobel Convolution for Edge Detection
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert the image internally to gray for edge detection processing
        val grays = IntArray(width * height)
        for (i in pixels.indices) {
            val col = pixels[i]
            val r = (col shr 16) and 0xFF
            val g = (col shr 8) and 0xFF
            val b = col and 0xFF
            grays[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }

        // Sobel Kernels
        val gx = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        val gy = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var valX = 0f
                var valY = 0f

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val gVal = grays[(y + ky) * width + (x + kx)].toFloat()
                        valX += gVal * gx[ky + 1][kx + 1]
                        valY += gVal * gy[ky + 1][kx + 1]
                    }
                }

                val mag = sqrt(valX * valX + valY * valY).toInt().coerceIn(0, 255)
                val originalAlpha = (pixels[y * width + x] shr 24) and 0xFF
                // Draw high-contrast white edge or black bg
                outPixels[y * width + x] = (originalAlpha shl 24) or (mag shl 16) or (mag shl 8) or mag
            }
        }
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    // --- BAGUETTE MAGIQUE (MAGIC WAND) SELECTION UTILITY ---
    // Flood Fill to find matching pixels and generate a white overlay mask Bitmap
    fun applyMagicWand(src: Bitmap, startX: Int, startY: Int, tolerance: Int): Bitmap {
        val width = src.width
        val height = src.height
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.eraseColor(Color.TRANSPARENT)

        if (startX !in 0 until width || startY !in 0 until height) return mask

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val targetColor = pixels[startY * width + startX]
        val tr = (targetColor shr 16) and 0xFF
        val tg = (targetColor shr 8) and 0xFF
        val tb = targetColor and 0xFF

        val visited = java.util.BitSet(width * height)
        val queue = java.util.ArrayDeque<Int>()
        queue.add(startY * width + startX)
        visited.set(startY * width + startX)

        val activeMaskPixels = IntArray(width * height)

        while (queue.isNotEmpty()) {
            val idx = queue.poll() ?: break
            val cx = idx % width
            val cy = idx / width

            // Mark mask pixel as solid white with selection highlights (alpha absolute)
            activeMaskPixels[idx] = (0x99 shl 24) or (0x0F shl 16) or (0xB0 shl 8) or 0xFF // Beautiful Cyan overlay for selection

            // Check neighbor coordinates
            val neighbors = arrayOf(
                Pair(cx - 1, cy),
                Pair(cx + 1, cy),
                Pair(cx, cy - 1),
                Pair(cx, cy + 1)
            )

            for (n in neighbors) {
                val nx = n.first
                val ny = n.second
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!visited.get(nIdx)) {
                        val cCol = pixels[nIdx]
                        val cr = (cCol shr 16) and 0xFF
                        val cg = (cCol shr 8) and 0xFF
                        val cb = cCol and 0xFF

                        // Euclidean distance to color similarity
                        val diff = sqrt(((cr - tr) * (cr - tr) + (cg - tg) * (cg - tg) + (cb - tb) * (cb - tb)).toDouble())
                        if (diff <= tolerance) {
                            visited.set(nIdx)
                            queue.add(nIdx)
                        }
                    }
                }
            }
        }
        mask.setPixels(activeMaskPixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }
}
