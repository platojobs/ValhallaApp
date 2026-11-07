package com.valhalla.core.security

/**
 * 纯 Kotlin SHA-256（FIPS 180-4），不依赖任何平台加密 API。
 *
 * 采用增量输入：调用方可以分块 [update]，最后调用一次 [digest] 取结果。
 * 这样固件验签时不必把整包 payload 载入内存。
 *
 * 注意：实例在 [digest] 之后不可继续复用；需要新摘要时请新建实例。
 */
class Sha256Digest {
    private val state =
        intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
            0x510e527f,
            0x9b05688c.toInt(),
            0x1f83d9ab,
            0x5be0cd19,
        )

    private val buffer = ByteArray(BLOCK_SIZE)
    private var bufferLength = 0
    private var totalLength = 0L
    private val schedule = IntArray(64)

    fun update(data: ByteArray): Sha256Digest = update(data, 0, data.size)

    fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Sha256Digest {
        require(offset in 0..data.size) { "offset 越界: $offset" }
        require(length in 0..(data.size - offset)) { "length 越界: $length" }
        if (length == 0) return this

        totalLength += length.toLong()
        var position = offset
        var remaining = length

        if (bufferLength > 0) {
            val take = minOf(BLOCK_SIZE - bufferLength, remaining)
            data.copyInto(buffer, bufferLength, position, position + take)
            bufferLength += take
            position += take
            remaining -= take
            if (bufferLength == BLOCK_SIZE) {
                processBlock(buffer, 0)
                bufferLength = 0
            }
        }

        while (remaining >= BLOCK_SIZE) {
            processBlock(data, position)
            position += BLOCK_SIZE
            remaining -= BLOCK_SIZE
        }

        if (remaining > 0) {
            data.copyInto(buffer, 0, position, position + remaining)
            bufferLength = remaining
        }
        return this
    }

    fun digest(): ByteArray {
        val bitLength = totalLength * 8
        val remainder = totalLength % BLOCK_SIZE.toLong()
        val paddingLength = if (remainder < 56L) 56L - remainder else 120L - remainder
        val padding = ByteArray((paddingLength + 8).toInt())
        padding[0] = 0x80.toByte()
        for (index in 0 until 8) {
            padding[padding.size - 1 - index] = ((bitLength ushr (8 * index)) and 0xFF).toByte()
        }
        update(padding)

        val out = ByteArray(32)
        for (index in 0 until 8) {
            val value = state[index]
            out[index * 4] = (value ushr 24).toByte()
            out[index * 4 + 1] = (value ushr 16).toByte()
            out[index * 4 + 2] = (value ushr 8).toByte()
            out[index * 4 + 3] = value.toByte()
        }
        return out
    }

    private fun processBlock(
        data: ByteArray,
        offset: Int,
    ) {
        for (index in 0 until 16) {
            val position = offset + index * 4
            schedule[index] =
                ((data[position].toInt() and 0xFF) shl 24) or
                ((data[position + 1].toInt() and 0xFF) shl 16) or
                ((data[position + 2].toInt() and 0xFF) shl 8) or
                (data[position + 3].toInt() and 0xFF)
        }
        for (index in 16 until 64) {
            val s0 =
                schedule[index - 15].rotateRight(7) xor
                    schedule[index - 15].rotateRight(18) xor
                    (schedule[index - 15] ushr 3)
            val s1 =
                schedule[index - 2].rotateRight(17) xor
                    schedule[index - 2].rotateRight(19) xor
                    (schedule[index - 2] ushr 10)
            schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[index] + schedule[index]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private companion object {
        const val BLOCK_SIZE = 64

        val K =
            intArrayOf(
                0x428a2f98,
                0x71374491,
                0xb5c0fbcf.toInt(),
                0xe9b5dba5.toInt(),
                0x3956c25b,
                0x59f111f1,
                0x923f82a4.toInt(),
                0xab1c5ed5.toInt(),
                0xd807aa98.toInt(),
                0x12835b01,
                0x243185be,
                0x550c7dc3,
                0x72be5d74,
                0x80deb1fe.toInt(),
                0x9bdc06a7.toInt(),
                0xc19bf174.toInt(),
                0xe49b69c1.toInt(),
                0xefbe4786.toInt(),
                0x0fc19dc6,
                0x240ca1cc,
                0x2de92c6f,
                0x4a7484aa,
                0x5cb0a9dc,
                0x76f988da,
                0x983e5152.toInt(),
                0xa831c66d.toInt(),
                0xb00327c8.toInt(),
                0xbf597fc7.toInt(),
                0xc6e00bf3.toInt(),
                0xd5a79147.toInt(),
                0x06ca6351,
                0x14292967,
                0x27b70a85,
                0x2e1b2138,
                0x4d2c6dfc,
                0x53380d13,
                0x650a7354,
                0x766a0abb,
                0x81c2c92e.toInt(),
                0x92722c85.toInt(),
                0xa2bfe8a1.toInt(),
                0xa81a664b.toInt(),
                0xc24b8b70.toInt(),
                0xc76c51a3.toInt(),
                0xd192e819.toInt(),
                0xd6990624.toInt(),
                0xf40e3585.toInt(),
                0x106aa070,
                0x19a4c116,
                0x1e376c08,
                0x2748774c,
                0x34b0bcb5,
                0x391c0cb3,
                0x4ed8aa4a,
                0x5b9cca4f,
                0x682e6ff3,
                0x748f82ee,
                0x78a5636f,
                0x84c87814.toInt(),
                0x8cc70208.toInt(),
                0x90befffa.toInt(),
                0xa4506ceb.toInt(),
                0xbef9a3f7.toInt(),
                0xc67178f2.toInt(),
            )
    }
}

/** SHA-256 便捷入口。 */
object Sha256 {
    fun compute(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray = Sha256Digest().update(data, offset, length).digest()
}
