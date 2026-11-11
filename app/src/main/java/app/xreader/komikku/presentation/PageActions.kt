/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * v1.5.7 (Komikku full, R-12): the donor ReaderPageActionsDialog's
 * four actions over a page's image file — copy to clipboard,
 * share, save to device (set-as-cover lives on the ViewModel,
 * which owns the book row). Files reach other apps through the
 * app's existing FileProvider: the page file is staged into
 * cache/quote-cards (a path the provider already publishes)
 * rather than widening the provider's roots.
 */
package app.xreader.komikku.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * v1.5.8 (I3): the donor's long-press page-action SET — exactly
 * four actions for a single page, each bound to its live
 * handler through the platform seams (cover override store /
 * image clipboard / share sheet / MediaStore-Downloads). The
 * invisible-engine fence stands: no WebView/Browser action is
 * in this set, and spread combined-page actions stay out.
 */
enum class PageAction(val label: String) {
    SET_AS_COVER("Set as cover"),
    COPY_TO_CLIPBOARD("Copy to clipboard"),
    SHARE("Share"),
    SAVE_TO_DEVICE("Save to device")
}

/** One action + the handler the shipped dialog invokes. */
data class PageActionItem(
    val action: PageAction,
    val onInvoke: () -> Unit
) {
    val label: String get() = action.label
}

/** Builds the dialog's model: exactly the four donor actions,
 * in donor order, each wired to its caller's handler. */
fun pageActionItems(
    onSetAsCover: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
): List<PageActionItem> = listOf(
    PageActionItem(PageAction.SET_AS_COVER, onSetAsCover),
    PageActionItem(PageAction.COPY_TO_CLIPBOARD, onCopy),
    PageActionItem(PageAction.SHARE, onShare),
    PageActionItem(PageAction.SAVE_TO_DEVICE, onSave)
)

/** Stages [source] into the FileProvider-published cache area
 * and returns its content URI. */
private fun stagedUri(context: Context, source: File): Uri {
    val dir = File(context.cacheDir, "quote-cards").apply { mkdirs() }
    val staged = File(dir, "page-${source.name}")
    if (!staged.isFile || staged.length() != source.length()) {
        source.copyTo(staged, overwrite = true)
    }
    return FileProvider.getUriForFile(context, "app.xreader.fileprovider", staged)
}

fun copyPageToClipboard(context: Context, source: File) {
    val uri = stagedUri(context, source)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Page", uri))
}

fun sharePage(context: Context, source: File) {
    val uri = stagedUri(context, source)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share page"))
}

fun shareChapterLink(context: Context, bookTitle: String, chapterTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$bookTitle — $chapterTitle")
    }
    context.startActivity(Intent.createChooser(intent, "Share chapter"))
}

/** Saves [source] into Pictures/Xreader via MediaStore (API
 * 29+) or the app's external pictures directory below that.
 * Returns true on success. */
fun savePageToDevice(context: Context, source: File): Boolean =
    runCatching {
        val name = "xreader-${System.currentTimeMillis()}-${source.name}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Xreader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(
                android.os.Environment.DIRECTORY_PICTURES
            ) ?: return false
            source.copyTo(File(dir, name), overwrite = true)
            true
        }
    }.getOrDefault(false)
