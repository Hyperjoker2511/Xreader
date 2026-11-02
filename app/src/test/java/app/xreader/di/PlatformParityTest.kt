package app.xreader.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.4 gate addition (c) — platform-version parity.
 *
 * Keiyoushi extensions are compiled against the donor's
 * (Komikku's) platform and meet OUR copies of those libraries
 * at runtime, parent-first, through the player's classloader.
 * v1.4.3 shipped serialization 1.7.3 against extensions built
 * for 1.11.0 and died on the user's phone in two distinct
 * ways (V143-ASURA-CRASH-DIAGNOSIS) — while every JVM test
 * stayed green, because a self-consistent classpath cannot
 * see an ABI skew against code that isn't in the room.
 *
 * This test pins the ABI-critical versions to the donor's
 * values and diffs them against our version catalog on every
 * gate run. The pins below were recorded from the donor's own
 * catalogs on 2026-11-02:
 *   research/komikku/gradle/kotlinx.versions.toml —
 *     serialization 1.11.0, coroutines 1.11.0, xmlutil 0.91.3
 *   research/komikku/gradle/libs.versions.toml —
 *     okhttp 5.3.2, okio 3.17.0 (com.squareup.okio:okio)
 * When the donor moves, re-pin HERE in the same change that
 * moves the catalog — a drift between the two fails the gate
 * instead of a user's phone. When the donor checkout is
 * present on this machine, the test also verifies the pins
 * still match the donor's files, so a stale pin is caught
 * rather than enshrined.
 */
class PlatformParityTest {

    /** Donor pins: artifact key in OUR catalog → the donor's
     * version string. Provenance: see the class doc. */
    private val donorPins = linkedMapOf(
        "serializationJson" to "1.11.0",
        "coroutines" to "1.11.0",
        "xmlutil" to "0.91.3",
        "okhttp" to "5.3.2",
        "okio" to "3.17.0"
    )

    private fun catalogText(): String {
        val candidates = listOf(
            File("../gradle/libs.versions.toml"),
            File("gradle/libs.versions.toml"),
            File("app/../gradle/libs.versions.toml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "version catalog not found from any known root"
            )
        return file.readText()
    }

    /** `key = "x.y.z"` entries of the catalog's [versions]. */
    private fun catalogVersions(text: String): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        val entry = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
        var inVersions = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inVersions = trimmed == "[versions]"
                continue
            }
            if (!inVersions) continue
            entry.find(trimmed)?.let { match ->
                versions[match.groupValues[1]] = match.groupValues[2]
            }
        }
        return versions
    }

    @Test
    fun `catalog matches the donor platform pins`() {
        val versions = catalogVersions(catalogText())
        for ((key, pin) in donorPins) {
            assertEquals(
                "platform drift: catalog '$key' must equal the " +
                    "donor's pinned $pin (see PlatformParityTest doc)",
                pin,
                versions[key]
            )
        }
        // The json-okio artifact must EXIST in the catalog —
        // its absence was disease 2 of the field crash
        // (OkioStreamsKt, referenced by shared extension code,
        // provided by nobody).
        assertTrue(
            "kotlinx-serialization-json-okio missing from the catalog",
            catalogText().contains("kotlinx-serialization-json-okio")
        )
    }

    @Test
    fun `pins still match the donor checkout when present`() {
        val home = File(System.getProperty("user.home") ?: return)
        val donorRoot = home.resolve(
            "workspace/xreader/research/komikku/gradle"
        )
        val kotlinxCatalog = donorRoot.resolve("kotlinx.versions.toml")
        val libsCatalog = donorRoot.resolve("libs.versions.toml")
        if (!kotlinxCatalog.exists() || !libsCatalog.exists()) {
            // No donor checkout on this machine: the recorded
            // pins above are the gate (they were verified
            // against the donor when recorded).
            return
        }
        val donorKotlinx = catalogVersions(kotlinxCatalog.readText())
        val donorLibs = catalogVersions(libsCatalog.readText())
        // The donor's own key names (verified 2026-11-02):
        // kotlinx.versions.toml carries serialization_version /
        // coroutines_version / xml_serialization_version;
        // libs.versions.toml carries okhttp_version, and okio
        // as a full module string.
        assertEquals(
            "donor moved: serialization pin is stale",
            donorPins["serializationJson"],
            donorKotlinx["serialization_version"]
        )
        assertEquals(
            "donor moved: coroutines pin is stale",
            donorPins["coroutines"],
            donorKotlinx["coroutines_version"]
        )
        assertEquals(
            "donor moved: xmlutil pin is stale",
            donorPins["xmlutil"],
            donorKotlinx["xml_serialization_version"]
        )
        assertEquals(
            "donor moved: okhttp pin is stale",
            donorPins["okhttp"],
            donorLibs["okhttp_version"]
        )
        val okioLine = libsCatalog.readText().lines()
            .firstOrNull { it.trim().startsWith("okio =") }
            ?: throw AssertionError("donor okio line not found")
        assertTrue(
            "donor moved: okio pin is stale",
            okioLine.contains(":${donorPins["okio"]}\"")
        )
    }
}
