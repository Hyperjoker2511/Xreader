/*
 * Transplanted verbatim for Xreader from Komikku (core/common, Apache-2.0) —
 * see the NOTICE file in this module for credits and donor revisions.
 */
package mihon.core.common.extensions

import kotlinx.serialization.json.JsonObject

val JsonObjectEmpty = JsonObject(emptyMap())

val JsonObjectEmptyBytes = byteArrayOf(0x7B, 0x7D)

val JsonObject.Companion.EMPTY: JsonObject
    inline get() = JsonObjectEmpty
