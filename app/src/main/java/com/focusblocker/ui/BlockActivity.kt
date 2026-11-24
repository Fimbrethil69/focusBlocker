package com.focusblocker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.focusblocker.core.INSTAGRAM_PACKAGE
import com.focusblocker.data.ConfigRepository
import com.focusblocker.data.SocialApps
import com.focusblocker.service.BlockerAccessibilityService
import java.time.LocalDateTime

class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val appName = SocialApps.nameOf(pkg)
        val now = LocalDateTime.now()
        val config = ConfigRepository.get(this).current(now)
        val until = config.nextChange(now)
        val directAllowed = pkg == INSTAGRAM_PACKAGE && config.allowInstagramDirect

        setContent {
            FocusTheme {
                BackHandler { finish() }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🔒", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "$appName est bloqué",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            until?.let { "Accès possible à partir de ${formatTime(it, now)}" } ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { finish() }) { Text("OK") }
                        if (directAllowed) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { openInstagramDirect() }) { Text("Ouvrir mes messages Instagram") }
                        }
                    }
                }
            }
        }
    }

    private fun openInstagramDirect() {
        BlockerAccessibilityService.allowInstagramDirectLaunch()
        val inbox = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
            .setPackage(INSTAGRAM_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(inbox)
        } catch (e: ActivityNotFoundException) {
            // Lien non reconnu : ouvre Instagram, le temps de toucher l'icône des messages.
            packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)?.let(::startActivity)
        }
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, packageName: String) =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
