package app.xreader.data.parse

/**
 * LZX decompression as used by CHM (OE-5 part B), implemented
 * from build-logs/research/RESEARCH-CHM.md §4 — the chmlib /
 * cabextract behaviour, including the places where it differs
 * from Microsoft's document (position-slot counts, the aligned
 * tree read BEFORE the main/length trees, the aligned extra-bits
 * rule for extra == 3). Pure Kotlin.
 *
 * How CHM drives it (§4.1, with one correction): the compressed
 * section is a sequence of 0x8000-byte output blocks grouped
 * into reset intervals. The decoder state — window contents,
 * R0/R1/R2, the main and length code lengths, the E8 counter —
 * persists across blocks WITHIN an interval and resets fully
 * at interval boundaries. The bitstream realigns to a 16-bit
 * unit at each output-block boundary.
 *
 * CORRECTION to §4.1/§4.4's "an LZX block never straddles a
 * 0x8000 boundary": real streams DO straddle — testChm.chm's
 * first LZX block declares 65536 output bytes. Both reference
 * implementations carry the current block (its remaining
 * length and its Huffman trees) across output blocks and only
 * read a new block header when the previous block is exhausted
 * (libmspack lzxd.c: `this_run = min(block_remaining,
 * bytes_todo)`; Tika ChmLzxBlock's blockRemaining carry). This
 * decoder follows the implementations, not the note.
 *
 * Any structural violation throws [LzxException]; the caller
 * turns it into an honest parse failure — never partial output.
 */
internal class LzxException(message: String) : Exception(message)

internal object LzxDecoder {

    private const val BLOCK_SIZE = 0x8000
    private const val MIN_MATCH = 2
    private const val NUM_LENGTH_SYMBOLS = 249 // length tree: 0..248

    // Position-slot tables (RESEARCH-CHM §4.6 — the
    // implementation values, not Microsoft's document values).
    private val EXTRA_BITS = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14,
        15, 15, 16, 16, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17,
        17, 17, 17
    )
    private val POSITION_BASE = intArrayOf(
        0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192,
        256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288,
        16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608, 262144, 393216,
        524288, 655360, 786432, 917504, 1048576, 1179648, 1310720, 1441792,
        1572864, 1703936, 1835008, 1966080, 2097152
    )

    /**
     * Decompresses one whole MSCompressed section.
     *
     * @param content the Content storage entry's bytes (the
     * compressed stream itself).
     * @param addresses the ResetTable's per-block compressed
     * addresses, relative to the start of [content].
     * @param compressedLength the ResetTable's total compressed
     * length (the last block's segment ends here).
     * @param uncompressedLength the SpanInfo total (the section's
     * true size; the final block is clamped to it).
     * @param windowSize the LZX window in bytes (from ControlData;
     * a power of two in 2^15..2^21).
     * @param resetBlockCount blocks per reset interval (from
     * ControlData; the field value itself).
     */
    fun decompress(
        content: ByteArray,
        addresses: LongArray,
        compressedLength: Long,
        uncompressedLength: Long,
        windowSize: Int,
        resetBlockCount: Int
    ): ByteArray {
        if (resetBlockCount < 1) throw LzxException("reset interval is zero")
        if (uncompressedLength < 0 || uncompressedLength > Int.MAX_VALUE - 8) {
            throw LzxException("uncompressed length out of range")
        }
        if (compressedLength < 0 || compressedLength > content.size) {
            throw LzxException("compressed length out of range")
        }
        var previous = -1L
        for (address in addresses) {
            if (address < 0 || address > compressedLength || address < previous) {
                throw LzxException("reset table addresses are not ordered")
            }
            previous = address
        }
        val out = ByteArray(uncompressedLength.toInt())
        val state = State(windowSize)
        var block = 0
        var pos = 0
        while (block < addresses.size) {
            val blocksHere = minOf(resetBlockCount, addresses.size - block)
            // One bit reader spans the whole interval: LZX
            // blocks straddle output blocks, and the frame-end
            // realignment (State.decodeFrame) is what keeps the
            // reader on the encoder's unit boundaries — the
            // reset table's per-block addresses only locate the
            // interval itself.
            val intervalStart = addresses[block]
            val intervalEnd = if (block + blocksHere < addresses.size) {
                addresses[block + blocksHere]
            } else {
                compressedLength
            }
            if (intervalEnd < intervalStart || intervalEnd > content.size) {
                throw LzxException("block bounds out of range")
            }
            val intervalBytes = content.copyOfRange(intervalStart.toInt(), intervalEnd.toInt())
            val reader = BitReader(intervalBytes)
            state.resetInterval(pos)
            state.readStreamHeader(reader)
            for (j in 0 until blocksHere) {
                val frameStart = pos - (pos % BLOCK_SIZE)
                val frameLength = minOf(BLOCK_SIZE, out.size - frameStart)
                if (frameLength <= 0) throw LzxException("block past the end of the section")
                // pos can already sit inside this frame when the
                // previous frame's last match overran its end.
                pos = state.decodeFrame(reader, intervalBytes, out, frameStart, frameLength, pos)
            }
            block += blocksHere
        }
        if (pos != out.size) throw LzxException("section decoded to the wrong length")
        return out
    }

    /** Bits are consumed from 16-bit little-endian units,
     * most-significant bit first (RESEARCH-CHM §4.3). */
    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0 // next byte to load
        private var buffer = 0L // valid bits live in the low [count] bits
        private var count = 0

        fun readBits(n: Int): Int {
            while (count < n) {
                val lo = if (bytePos < data.size) data[bytePos].toInt() and 0xFF else 0
                val hi = if (bytePos + 1 < data.size) data[bytePos + 1].toInt() and 0xFF else 0
                bytePos += 2
                // One phantom unit past the end is the final
                // unit's padding; needing more means the segment
                // is truncated — fail, never zero-fill a block.
                if (bytePos > data.size + 2) throw LzxException("bitstream overrun")
                buffer = (buffer shl 16) or ((hi shl 8) or lo).toLong()
                count += 16
            }
            val shift = count - n
            val value = ((buffer ushr shift) and ((1L shl n) - 1)).toInt()
            buffer = buffer and ((1L shl shift) - 1)
            count -= n
            return value
        }

        /** Drops the unconsumed tail of the current 16-bit unit
         * (the uncompressed-block realignment, §4.4). */
        fun alignToUnit() {
            val drop = count % 16
            buffer = buffer and ((1L shl (count - drop)) - 1)
            count -= drop
        }

        /** Byte offset of the next unread byte in the segment. */
        fun rawPosition(): Int = bytePos - count / 8

        /** Repositions the reader at a raw byte offset (after an
         * uncompressed block's literal bytes). */
        fun jumpToByte(newPos: Int) {
            bytePos = newPos
            buffer = 0
            count = 0
        }
    }

    /** Canonical Huffman decoding (symbols in numeric order,
     * codes by increasing length then symbol — §4.3). Code
     * lengths run 0..16, not 0..15 as §4.3 claims: the
     * READ_LENGTHS delta arithmetic is mod 17 precisely because
     * 17 values exist, lzxd.c's HUFF_MAXBITS is 16, and
     * testChm.chm's third interval carries a main tree whose
     * Kraft sum is exactly 1 only with its 16-bit codes counted
     * (verified in the OE-5 harness). */
    private class Huffman(lengths: IntArray) {
        private val counts = IntArray(17)
        private val symbols: IntArray

        init {
            var kraft = 0
            for (length in lengths) {
                if (length !in 0..16) throw LzxException("code length out of range")
                if (length > 0) {
                    counts[length]++
                    kraft += 1 shl (16 - length)
                }
            }
            if (kraft > (1 shl 16)) throw LzxException("over-subscribed Huffman code")
            symbols = IntArray(lengths.size)
            val next = IntArray(17)
            var offset = 0
            for (len in 1..16) {
                next[len] = offset
                offset += counts[len]
            }
            for (symbol in lengths.indices) {
                val length = lengths[symbol]
                if (length > 0) symbols[next[length]++] = symbol
            }
        }

        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..16) {
                code = (code shl 1) or reader.readBits(1)
                val count = counts[len]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
            }
            throw LzxException("bad Huffman code")
        }
    }

    private class State(private val windowSize: Int) {
        private val numSlots: Int
        private val mainElements: Int
        private val mainLens: IntArray
        private val lengthLens = IntArray(NUM_LENGTH_SYMBOLS)
        private var r0 = 1
        private var r1 = 1
        private var r2 = 1
        private var intelFilesize = 0
        private var intelStarted = false
        private var intervalStart = 0 // absolute pos of this interval in the output

        // The current LZX block, carried across output blocks
        // when it straddles one (see the class doc correction).
        private var blockType = 0
        private var blockLength = 0
        private var blockRemaining = 0
        private var mainTree: Huffman? = null
        private var lengthTree: Huffman? = null
        private var alignedTree: Huffman? = null
        private var oddRawPadPending = false

        init {
            val w = when (windowSize) {
                1 shl 15 -> 15
                1 shl 16 -> 16
                1 shl 17 -> 17
                1 shl 18 -> 18
                1 shl 19 -> 19
                1 shl 20 -> 20
                1 shl 21 -> 21
                else -> throw LzxException("window size out of range")
            }
            numSlots = when (w) {
                20 -> 42
                21 -> 50
                else -> 2 * w
            }
            mainElements = 256 + 8 * numSlots
            mainLens = IntArray(mainElements)
        }

        fun resetInterval(outputPos: Int) {
            r0 = 1
            r1 = 1
            r2 = 1
            mainLens.fill(0)
            lengthLens.fill(0)
            intelFilesize = 0
            intelStarted = false
            intervalStart = outputPos
            blockType = 0
            blockLength = 0
            blockRemaining = 0
            mainTree = null
            lengthTree = null
            alignedTree = null
            oddRawPadPending = false
        }

        /** The per-interval stream header (§4.2): one E8 flag
         * bit, then the 32-bit translation file size as two
         * 16-bit halves when the flag is set. The size travels
         * in the stream, never in ControlData. */
        fun readStreamHeader(reader: BitReader) {
            intelFilesize = if (reader.readBits(1) == 1) {
                val hi = reader.readBits(16)
                val lo = reader.readBits(16)
                (hi shl 16) or lo
            } else {
                0
            }
        }

        /** Decodes one 0x8000 output block (a frame), writing
         * at [outPos]. The current LZX block may end mid-frame
         * (a new header follows in the same frame) or straddle
         * into the next frame (its state carries over). At the
         * frame's end the bitstream realigns to a 16-bit unit
         * (lzxd.c's frame rule) and the E8 fixup applies to the
         * frame's bytes (§4.8). */
        fun decodeFrame(
            reader: BitReader,
            segment: ByteArray,
            out: ByteArray,
            frameStart: Int,
            frameLength: Int,
            entryPos: Int
        ): Int {
            var pos = entryPos
            val frameEnd = frameStart + frameLength
            while (pos < frameEnd) {
                if (blockRemaining == 0) startBlock(reader, segment)
                pos = if (blockType == 3) {
                    // Uncompressed block: raw bytes straight
                    // from the stream (they may straddle the
                    // frame — the reader spans the interval).
                    val n = minOf(blockRemaining, frameEnd - pos)
                    val rawPos = reader.rawPosition()
                    if (rawPos + n > segment.size) {
                        throw LzxException("uncompressed block overruns its segment")
                    }
                    System.arraycopy(segment, rawPos, out, pos, n)
                    reader.jumpToByte(rawPos + n)
                    blockRemaining -= n
                    if (blockRemaining == 0 && blockLength % 2 == 1) {
                        oddRawPadPending = true
                    }
                    pos + n
                } else {
                    decodeSymbol(reader, out, pos)
                }
            }
            reader.alignToUnit()
            if (intelFilesize != 0 && intelStarted) {
                applyE8(out, frameStart, frameEnd, frameStart - intervalStart)
            }
            return pos
        }

        /** Reads one LZX block header (§4.4) and, for compressed
         * blocks, its Huffman trees. */
        private fun startBlock(reader: BitReader, segment: ByteArray) {
            // An odd-sized uncompressed block is followed by one
            // pad byte before the next header (lzxd.c skips it
            // when the next block starts).
            if (oddRawPadPending) {
                reader.jumpToByte(reader.rawPosition() + 1)
                oddRawPadPending = false
            }
            val type = reader.readBits(3)
            val length = (reader.readBits(16) shl 8) or reader.readBits(8)
            if (length <= 0) throw LzxException("LZX block length out of range")
            blockType = type
            blockLength = length
            blockRemaining = length
            when (type) {
                1 -> {
                    alignedTree = null
                    readTrees(reader)
                }
                2 -> {
                    // Aligned-offset block: the aligned tree's
                    // lengths come FIRST (8 × 3 bits), then the
                    // header continues as a verbatim block (§4.4).
                    alignedTree = Huffman(IntArray(8) { reader.readBits(3) })
                    readTrees(reader)
                }
                3 -> {
                    // Uncompressed block (§4.4): realign, then
                    // R0/R1/R2 as little-endian DWORDs replace
                    // the repeat offsets, then the raw bytes.
                    reader.alignToUnit()
                    val rawStart = reader.rawPosition()
                    if (rawStart + 12 > segment.size) {
                        throw LzxException("uncompressed block overruns its segment")
                    }
                    r0 = int32Le(segment, rawStart)
                    r1 = int32Le(segment, rawStart + 4)
                    r2 = int32Le(segment, rawStart + 8)
                    if (r0 < 0 || r0 >= windowSize || r1 < 0 || r1 >= windowSize ||
                        r2 < 0 || r2 >= windowSize
                    ) {
                        throw LzxException("uncompressed block has bad repeat offsets")
                    }
                    reader.jumpToByte(rawStart + 12)
                    intelStarted = true // an uncompressed block forces E8 started (§4.8)
                }
                else -> throw LzxException("unknown LZX block type $type")
            }
        }

        private fun readTrees(reader: BitReader) {
            readLengths(reader, mainLens, 0, 256)
            readLengths(reader, mainLens, 256, mainElements)
            readLengths(reader, lengthLens, 0, NUM_LENGTH_SYMBOLS)
            mainTree = Huffman(mainLens)
            lengthTree = Huffman(lengthLens)
            // E8 translation starts when the byte 0xE8 can occur
            // in this stream (§4.8).
            if (mainLens[0xE8] != 0) intelStarted = true
        }

        /** Decodes ONE symbol (a literal, or a whole match —
         * a match may overrun the frame but never its block),
         * returning the new output position. */
        private fun decodeSymbol(reader: BitReader, out: ByteArray, startPos: Int): Int {
            val mainTree = mainTree ?: throw LzxException("symbol without a block")
            val lengthTree = lengthTree ?: throw LzxException("symbol without a block")
            val symbol = mainTree.decode(reader)
            if (symbol < 256) {
                if (blockRemaining < 1) throw LzxException("block overrun")
                out[startPos] = symbol.toByte()
                blockRemaining--
                return startPos + 1
            }
            val m = symbol - 256
            val slot = m shr 3
            val lengthHeader = m and 7
            val matchLength = if (lengthHeader == 7) {
                lengthTree.decode(reader) + 7 + MIN_MATCH
            } else {
                lengthHeader + MIN_MATCH
            }
            if (matchLength > blockRemaining) {
                throw LzxException("match overruns its block")
            }
            if (startPos + matchLength > out.size) {
                throw LzxException("match overruns the section")
            }
            val offset = decodeOffset(reader, slot, alignedTree)
            if (offset < 1 || offset > windowSize || offset > startPos - intervalStart) {
                throw LzxException("match offset out of range")
            }
            var from = startPos - offset
            var pos = startPos
            repeat(matchLength) {
                out[pos++] = out[from++]
            }
            blockRemaining -= matchLength
            return pos
        }

        private fun decodeOffset(reader: BitReader, slot: Int, aligned: Huffman?): Int {
            fun push(value: Int) {
                r2 = r1
                r1 = r0
                r0 = value
            }
            return when (slot) {
                0 -> r0
                1 -> {
                    val offset = r1
                    r1 = r0
                    r0 = offset
                    offset
                }
                2 -> {
                    val offset = r2
                    r2 = r0
                    r0 = offset
                    offset
                }
                3 -> {
                    push(1)
                    1
                }
                else -> {
                    val base = POSITION_BASE[slot] - 2
                    val extra = EXTRA_BITS[slot]
                    val offset = if (aligned != null) {
                        when {
                            extra > 3 ->
                                base + ((reader.readBits(extra - 3) shl 3) + aligned.decode(reader))
                            extra == 3 -> base + aligned.decode(reader)
                            extra >= 1 -> base + reader.readBits(extra)
                            else -> 1 // unreachable: slots ≥ 4 have extra ≥ 1
                        }
                    } else {
                        base + reader.readBits(extra)
                    }
                    push(offset)
                    offset
                }
            }
        }

        /** READ_LENGTHS (§4.5): code lengths arrive as deltas
         * against the previous block's lengths, coded with a
         * freshly-transmitted 20-symbol pretree. */
        private fun readLengths(reader: BitReader, lengths: IntArray, first: Int, last: Int) {
            val preLens = IntArray(20) { reader.readBits(4) }
            val preTree = Huffman(preLens)
            var i = first
            while (i < last) {
                when (val z = preTree.decode(reader)) {
                    in 0..16 -> {
                        var newLength = lengths[i] - z
                        if (newLength < 0) newLength += 17
                        lengths[i] = newLength
                        i++
                    }
                    17 -> {
                        val run = reader.readBits(4) + 4
                        repeat(run) {
                            if (i < last) lengths[i] = 0
                            i++
                        }
                    }
                    18 -> {
                        val run = reader.readBits(5) + 20
                        repeat(run) {
                            if (i < last) lengths[i] = 0
                            i++
                        }
                    }
                    19 -> {
                        val run = reader.readBits(1) + 4
                        val s = preTree.decode(reader)
                        if (s !in 0..16) throw LzxException("bad pretree symbol")
                        var newLength = lengths[i] - s
                        if (newLength < 0) newLength += 17
                        repeat(run) {
                            if (i < last) lengths[i] = newLength
                            i++
                        }
                    }
                    else -> throw LzxException("bad pretree symbol")
                }
            }
        }

        /** The Intel E8 fixup (§4.8), applied to one decoded
         * 0x8000 block: [curposStart] is the block's absolute
         * position within the interval's uncompressed stream. */
        private fun applyE8(out: ByteArray, blockStart: Int, blockEnd: Int, curposStart: Int) {
            var p = blockStart
            var curpos = curposStart
            while (p < blockEnd - 10) {
                if (out[p] == 0xE8.toByte()) {
                    var v = int32Le(out, p + 1)
                    if (v >= -curpos && v < intelFilesize) {
                        v = if (v >= 0) v - curpos else v + intelFilesize
                        putInt32Le(out, p + 1, v)
                    }
                }
                p++
                curpos++
            }
        }

        private fun int32Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun putInt32Le(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }
    }
}
