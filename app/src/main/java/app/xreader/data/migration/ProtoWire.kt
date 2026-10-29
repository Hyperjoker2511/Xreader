package app.xreader.data.migration

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * A minimal protobuf wire-format reader (Phase 6) — just enough to
 * read Mihon's `.tachibk` backups without vendoring a protobuf
 * runtime for one file format. The wire format is small and stable:
 * every field is a tag varint (field number << 3 | wire type) plus
 * a varint / fixed / length-delimited value. Unknown fields are
 * skipped by construction, so newer Mihon backups degrade to
 * "what we understand" instead of failing (PLAN §8 risk note).
 */
class ProtoMessage private constructor(private val fields: List<Field>) {

    data class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray)

    fun fields(number: Int): List<Field> = fields.filter { it.number == number }

    fun strings(number: Int): List<String> =
        fields(number).map { String(it.bytes, Charsets.UTF_8) }

    fun firstString(number: Int): String? = strings(number).firstOrNull()

    fun varints(number: Int): List<Long> = fields(number).map { it.varint }

    fun firstVarint(number: Int): Long? = varints(number).firstOrNull()

    fun bool(number: Int): Boolean = firstVarint(number) == 1L

    /** Floats/doubles ride in fixed32/fixed64 fields (little-endian). */
    fun firstFloat(number: Int): Float? {
        val field = fields(number).firstOrNull() ?: return null
        if (field.bytes.size < 4) return null
        val bits = (field.bytes[0].toInt() and 0xFF) or
            ((field.bytes[1].toInt() and 0xFF) shl 8) or
            ((field.bytes[2].toInt() and 0xFF) shl 16) or
            ((field.bytes[3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    fun messages(number: Int): List<ProtoMessage> =
        fields(number).mapNotNull { parse(it.bytes) }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        /** Parses one message; null on truncated/garbled input. */
        fun parse(bytes: ByteArray): ProtoMessage? {
            val reader = Reader(bytes)
            val out = mutableListOf<Field>()
            return try {
                while (!reader.exhausted()) {
                    val tag = reader.varint()
                    val number = (tag ushr 3).toInt()
                    if (number <= 0) return null
                    when (val wire = (tag and 0x7).toInt()) {
                        WIRE_VARINT -> out += Field(number, wire, reader.varint(), ByteArray(0))
                        WIRE_FIXED64 -> out += Field(number, wire, 0, reader.bytes(8))
                        WIRE_LENGTH -> {
                            val length = reader.varint().toInt()
                            if (length < 0) return null
                            out += Field(number, wire, 0, reader.bytes(length))
                        }
                        WIRE_FIXED32 -> out += Field(number, wire, 0, reader.bytes(4))
                        else -> return null // groups (3/4) never appear in Mihon backups
                    }
                }
                ProtoMessage(out)
            } catch (e: Exception) {
                null
            }
        }

        /** `.tachibk` files are gzip-wrapped protobuf; a raw
         * protobuf payload is accepted too. Null when neither. */
        fun gunzipIfNeeded(bytes: ByteArray): ByteArray? {
            if (bytes.size >= 2 &&
                bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            ) {
                return try {
                    GZIPInputStream(bytes.inputStream()).use { gzip ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = gzip.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
            }
            return bytes
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun exhausted(): Boolean = pos >= bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= bytes.size) throw IndexOutOfBoundsException("varint overruns")
                val byte = bytes[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
        }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || pos + count > bytes.size) {
                throw IndexOutOfBoundsException("field overruns the message")
            }
            val slice = bytes.copyOfRange(pos, pos + count)
            pos += count
            return slice
        }
    }
}
