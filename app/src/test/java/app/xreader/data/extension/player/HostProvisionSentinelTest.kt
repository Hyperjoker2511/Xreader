package app.xreader.data.extension.player

import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.4.4 gate addition (d) — host-provision sentinels.
 *
 * The donor's serialization bundle is FIVE artifacts
 * (Komikku's `serialization = [serialization-json,
 * serialization-json-okio, serialization-protobuf,
 * serialization-xml-core, serialization-xml]`). Xreader's
 * host must provide every one of them on the classpath
 * extensions link against: v1.4.3 provided four and missed
 * kotlinx-serialization-json-okio, so Asura died with
 * NoClassDefFoundError on its first fetch — inside shared
 * extension code the host never calls directly, which is why
 * no host-side test noticed.
 *
 * One loadable sentinel class per bundle artifact. If an
 * artifact ever falls off the dependency graph (a catalog
 * edit, a transitive eviction, a module reshuffle), its
 * sentinel stops loading and this test fails HERE — not on
 * a user's phone, mid-browse.
 */
class HostProvisionSentinelTest {

    /** artifact → one canonical class it must contribute. */
    private val sentinels = linkedMapOf(
        "kotlinx-serialization-json" to
            "kotlinx.serialization.json.Json",
        // The canonical case: the class whose absence was
        // Asura's field crash (V143-ASURA-CRASH-DIAGNOSIS,
        // disease 2).
        "kotlinx-serialization-json-okio" to
            "kotlinx.serialization.json.okio.OkioStreamsKt",
        "kotlinx-serialization-protobuf" to
            "kotlinx.serialization.protobuf.ProtoBuf",
        // xmlutil's core-android artifact (the bundle's
        // serialization-xml-core leg, as our catalog names it).
        "xmlutil core-android" to
            "nl.adaptivity.xmlutil.core.AndroidXmlReader",
        // xmlutil's serialization artifact (serialization-xml).
        "xmlutil serialization" to
            "nl.adaptivity.xmlutil.serialization.XML"
    )

    @Test
    fun `every donor bundle artifact contributes its sentinel`() {
        val missing = mutableListOf<String>()
        for ((artifact, sentinel) in sentinels) {
            try {
                Class.forName(sentinel, false, javaClass.classLoader)
            } catch (t: Throwable) {
                missing += "$artifact → $sentinel " +
                    "(${t.javaClass.simpleName}: ${t.message})"
            }
        }
        if (missing.isNotEmpty()) {
            fail(
                "the host no longer provides part of the donor's " +
                    "serialization bundle:\n" + missing.joinToString("\n")
            )
        }
    }
}
