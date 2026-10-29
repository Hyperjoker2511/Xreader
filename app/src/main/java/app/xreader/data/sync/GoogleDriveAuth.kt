package app.xreader.data.sync

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.runCatchingAppSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Play-services Google Sign-In for Drive app-folder sync (PLAN §7).
 * The sign-in itself is the USER's action in the Sync screen (his
 * account, his tap — nothing is pre-connected); this class only reads
 * the resulting account and mints short-lived access tokens.
 *
 * DEVICE-BOUND (honest list): live sign-in needs a Google Cloud OAuth
 * client registered for `app.xreader` + this build's signing SHA-1 —
 * a one-time console setup the sandbox cannot perform or verify. The
 * Drive REST client above this seam is fully fixture-tested.
 */
class GoogleDriveAuth(private val context: Context) : DriveAuthProvider {

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(Scopes.DRIVE_APPFOLDER))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    override suspend fun accountEmail(): String? =
        runCatching { GoogleSignIn.getLastSignedInAccount(context)?.email }.getOrNull()

    override suspend fun accessToken(): AppResult<String> =
        withContext(Dispatchers.IO) {
            val account = runCatching {
                GoogleSignIn.getLastSignedInAccount(context)?.account
            }.getOrNull() ?: return@withContext AppResult.Err(
                AppError.AuthFailed("Google Drive", "not signed in")
            )
            try {
                val token = GoogleAuthUtil.getToken(
                    context, account, "oauth2:${Scopes.DRIVE_APPFOLDER}"
                )
                AppResult.Ok(token)
            } catch (e: UserRecoverableAuthException) {
                AppResult.Err(
                    AppError.AuthFailed(
                        "Google Drive",
                        "Google needs you to confirm access again — open Sync & backup"
                    )
                )
            } catch (e: Exception) {
                AppResult.Err(
                    AppError.ProviderDown(
                        "Google Drive",
                        detail = "token: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                    )
                )
            }
        }

    override suspend fun signOut() {
        runCatchingAppSuspend { signInClient().signOut() }
    }
}
