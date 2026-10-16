package com.focusblocker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Mise à jour depuis la release « latest » du dépôt GitHub. */
object Updater {
    private const val RELEASE_URL = "https://github.com/Fimbrethil69/focusBlocker/releases/download/latest/"

    fun installedVersion(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun versionName(version: Long) = "1.$version"

    /** Numéro de la dernière version publiée (écrit par GitHub Actions dans version.txt). */
    suspend fun latestVersion(): Long = withContext(Dispatchers.IO) {
        get("version.txt") { it.bufferedReader().readText() }.trim().toLong()
    }

    suspend fun download(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "FocusBlocker.apk")
        get("FocusBlocker.apk") { input -> file.outputStream().use { input.copyTo(it) } }
        file
    }

    /** Android demande une autorisation pour qu'une application en installe une autre. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun <T> get(name: String, read: (java.io.InputStream) -> T): T {
        val connection = URL(RELEASE_URL + name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }
}
