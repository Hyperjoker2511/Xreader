package app.xreader.data.extension.player

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.4 gate addition (a) — the codegen-shape tripwire.
 *
 * The v1.4.3 field crash (Comix, photographed on the user's
 * phone): a Keiyoushi extension opening died with
 * `AbstractMethodError` naming
 * `GeneratedSerializer.typeParametersSerializers()` on a
 * compiler-generated serializer class. Root cause
 * (V143-ASURA-CRASH-DIAGNOSIS, disease 1): that interface
 * method is ABSTRACT in kotlinx-serialization ≤ 1.7.3 and a
 * DEFAULT from 1.8.0 on. Extensions are compiled against the
 * newer runtime, so their generated serializers OMIT the
 * override — legal against ≥ 1.8, fatal against our old
 * 1.7.3 host runtime, which they meet parent-first through
 * the player's classloader. The JVM gate could not see it:
 * our own plugin, compiling against 1.7.3, dutifully emitted
 * the override in OUR serializers.
 *
 * This test simulates the foreign codegen shape by hand: a
 * serializer written exactly as post-1.8 codegen emits it —
 * a PluginGeneratedSerialDescriptor fed by
 * `childSerializers()`, and NO `typeParametersSerializers()`
 * override. Exercising it through descriptor construction,
 * hashing and a full Json round-trip drives the runtime's
 * own descriptor machinery over that missing method: on any
 * host runtime < 1.8 this throws the field's exact
 * AbstractMethodError; on the aligned 1.11.0 platform it
 * simply works. If the platform ever regresses, this test —
 * not a user's phone — finds out.
 */
@OptIn(InternalSerializationApi::class)
class SerializationCodegenTripwireTest {

    private data class TripwireRow(
        val name: String,
        val url: String,
        val rank: Int
    )

    /**
     * Hand-written in the post-1.8 codegen shape (mirrors the
     * bytecode of a real plugin-generated serializer, minus
     * the override the newer plugin omits). Deliberately does
     * NOT override `typeParametersSerializers()`.
     *
     * The descriptor is the runtime's own
     * PluginGeneratedSerialDescriptor — the class whose
     * hash/equals machinery calls the missing method. It is
     * Kotlin-`internal` in the library (the compiler plugin
     * emits calls to it directly in bytecode, which is how
     * generated serializers use it), so this hand-written
     * mirror builds it reflectively; the resulting object is
     * exactly what codegen produces.
     */
    private class TripwireRowSerializer : GeneratedSerializer<TripwireRow> {

        override val descriptor: SerialDescriptor = buildDescriptor()

        private fun buildDescriptor(): SerialDescriptor {
            val clazz = Class.forName(
                "kotlinx.serialization.internal." +
                    "PluginGeneratedSerialDescriptor"
            )
            val instance = clazz
                .getConstructor(
                    String::class.java,
                    GeneratedSerializer::class.java,
                    Int::class.javaPrimitiveType
                )
                .newInstance(
                    "app.xreader.tripwire.TripwireRow", this, 3
                )
            val addElement = clazz.getMethod(
                "addElement",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            addElement.invoke(instance, "name", false)
            addElement.invoke(instance, "url", false)
            addElement.invoke(instance, "rank", false)
            return instance as SerialDescriptor
        }

        override fun childSerializers(): Array<KSerializer<*>> =
            arrayOf(
                String.serializer(),
                String.serializer(),
                Int.serializer()
            )

        // NOTE: no typeParametersSerializers() override is
        // written here — that absence IS the simulation. (The
        // Kotlin compiler emits only a bare super-forwarder
        // stub for the interface default; on a < 1.8 runtime
        // that stub's invokespecial lands on an ABSTRACT
        // method and dies with the same AbstractMethodError —
        // verified empirically against the 1.7.3 runtime
        // during the v1.4.4 gate.) Do not "fix" it by writing
        // a real override.

        override fun serialize(encoder: Encoder, value: TripwireRow) {
            val out = encoder.beginStructure(descriptor)
            out.encodeStringElement(descriptor, 0, value.name)
            out.encodeStringElement(descriptor, 1, value.url)
            out.encodeIntElement(descriptor, 2, value.rank)
            out.endStructure(descriptor)
        }

        override fun deserialize(decoder: Decoder): TripwireRow {
            val input = decoder.beginStructure(descriptor)
            var name: String? = null
            var url: String? = null
            var rank = 0
            var seen = 0
            loop@ while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    0 -> {
                        name = input.decodeStringElement(descriptor, 0)
                        seen = seen or 0b001
                    }
                    1 -> {
                        url = input.decodeStringElement(descriptor, 1)
                        seen = seen or 0b010
                    }
                    2 -> {
                        rank = input.decodeIntElement(descriptor, 2)
                        seen = seen or 0b100
                    }
                    else -> throw SerializationException(
                        "Unexpected index $index"
                    )
                }
            }
            input.endStructure(descriptor)
            if (seen != 0b111) {
                throw SerializationException("Missing fields in TripwireRow")
            }
            return TripwireRow(name!!, url!!, rank)
        }
    }

    @Test
    fun `post-1-8 codegen shape survives the host runtime`() {
        val serializer = TripwireRowSerializer()
        // Descriptor construction + identity: the runtime's
        // PluginGeneratedSerialDescriptor consults
        // typeParametersSerializers() for its type-parameter
        // descriptors (hash/equals paths) — the exact call
        // that AbstractMethodError'd on the phone.
        val descriptor = serializer.descriptor
        assertEquals("app.xreader.tripwire.TripwireRow", descriptor.serialName)
        assertEquals(3, descriptor.elementsCount)
        assertEquals("url", descriptor.getElementName(1))
        descriptor.hashCode()
        descriptor.toString()

        // A full parse round-trip, the parseAs-shaped drive.
        val row = TripwireRow("Shadow Slave", "https://example.test/ss", 7)
        val json = Json.encodeToString(serializer, row)
        assertTrue(json.contains("Shadow Slave"))
        assertEquals(row, Json.decodeFromString(serializer, json))
    }
}
