package app.xreader.data.reader

import android.content.Context
import android.net.Uri
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.File

/**
 * User-imported reader fonts (PLAN §2.3/§6): .ttf/.otf files picked via
 * SAF are stream-copied into `filesDir/fonts/` — the only place the
 * reader loads font files from. Import never loads the whole file into
 * RAM and refuses anything that isn't a font by extension + size cap.
 */
class UserFonts(private val fontsDir: File) {

    fun dir(): File = fontsDir.apply { mkdirs() }

    /** Imported font file names, sorted — the settings picker's list. */
    fun list(): List<String> =
        dir().listFiles { f -> f.isFile && isFontName(f.name) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun file(name: String): File? {
        val safe = File(name).name // never let a stored name escape the dir
        val file = File(dir(), safe)
        return if (file.isFile && isFontName(safe)) file else null
    }

    /** Stream-copies the picked font in; returns its stored file name. */
    fun import(context: Context, uri: Uri, displayName: String): AppResult<String> {
        val safeName = File(displayName).name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!isFontName(safeName)) {
            return AppResult.Err(
                AppError.BadFile("that file isn't a .ttf or .otf font")
            )
        }
        val target = File(dir(), safeName)
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return AppResult.Err(AppError.BadFile("the font file couldn't be opened"))
            var total = 0L
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FONT_BYTES) {
                            target.delete()
                            return AppResult.Err(
                                AppError.BadFile("that font file is too large (over 20 MB)")
                            )
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (total == 0L) {
                target.delete()
                AppResult.Err(AppError.BadFile("the font file was empty"))
            } else {
                AppResult.Ok(safeName)
            }
        } catch (e: Exception) {
            target.delete()
            AppResult.Err(AppError.Unknown(e))
        }
    }

    fun delete(name: String): Boolean = file(name)?.delete() ?: false

    companion object {
        const val MAX_FONT_BYTES = 20L * 1024 * 1024

        fun isFontName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".ttf") || lower.endsWith(".otf")
        }
    }
}
