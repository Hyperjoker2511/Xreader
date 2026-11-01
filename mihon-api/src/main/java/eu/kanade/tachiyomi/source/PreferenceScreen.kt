/*
 * Transplanted for Xreader from Komikku (source-api, Apache-2.0) — see NOTICE.
 * Komikku is a multiplatform module: commonMain declares
 * `expect class PreferenceScreen` and androidMain provides the actual typealias
 * below. Xreader's mihon-api is a plain Android library module, so the plain
 * typealias is the same thing: it erases to androidx.preference.PreferenceScreen
 * in every signature, exactly like the extension-lib contract.
 */
package eu.kanade.tachiyomi.source

typealias PreferenceScreen = androidx.preference.PreferenceScreen
