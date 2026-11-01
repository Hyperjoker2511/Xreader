/*
 * Transplanted verbatim for Xreader from Komikku (core/common, Apache-2.0) —
 * see the NOTICE file in this module for credits and donor revisions.
 */
package eu.kanade.tachiyomi.network

import okhttp3.Response

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @see Response.isSuccessful
 * @since tachiyomix 1.6
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
