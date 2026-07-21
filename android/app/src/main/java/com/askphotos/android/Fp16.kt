package com.askphotos.android

internal object Fp16 {
    fun fromFloat(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val magnitude = bits and 0x7fffffff
        val rounded = magnitude + 0x1000
        val half = when {
            rounded >= 0x47800000 -> {
                if (magnitude >= 0x47800000) {
                    if (rounded < 0x7f800000) 0x7c00 else 0x7c00 or ((magnitude and 0x007fffff) ushr 13)
                } else {
                    0x7bff
                }
            }
            rounded >= 0x38800000 -> (rounded - 0x38000000) ushr 13
            rounded < 0x33000000 -> 0
            else -> {
                val exponent = magnitude ushr 23
                (((magnitude and 0x7fffff) or 0x800000) + (0x800000 ushr (exponent - 102))) ushr (126 - exponent)
            }
        }
        return (sign or half).toShort()
    }

    fun toFloat(value: Short): Float {
        val half = value.toInt() and 0xffff
        val sign = (half and 0x8000) shl 16
        var exponent = (half ushr 10) and 0x1f
        var fraction = half and 0x03ff
        val bits = when {
            exponent == 0 && fraction == 0 -> sign
            exponent == 0 -> {
                while (fraction and 0x0400 == 0) {
                    fraction = fraction shl 1
                    exponent -= 1
                }
                fraction = fraction and 0x03ff
                sign or ((exponent + 113) shl 23) or (fraction shl 13)
            }
            exponent == 0x1f -> sign or 0x7f800000.toInt() or (fraction shl 13)
            else -> sign or ((exponent + 112) shl 23) or (fraction shl 13)
        }
        return Float.fromBits(bits)
    }
}
