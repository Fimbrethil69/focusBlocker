package com.focusblocker.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.focusblocker.core.BlockConfig
import com.focusblocker.core.BlockCount
import com.focusblocker.core.BlockStats
import com.focusblocker.core.ConfigChange
import com.focusblocker.core.ConfigState
import com.focusblocker.core.TimeRange
import com.focusblocker.core.formatMinute
import com.focusblocker.data.ConfigRepository
import com.focusblocker.data.SocialApps
import com.focusblocker.data.StatsRepository
import com.focusblocker.service.BlockerAccessibilityService
import com.focusblocker.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = ConfigRepository.get(this)
        val stats = StatsRepository.get(this)
        setContent {
            FocusTheme { MainScreen(repository, stats) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(repository: ConfigRepository, statsRepository: StatsRepository) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var state by remember { mutableStateOf(repository.load()) }
    var stats by remember { mutableStateOf(statsRepository.load()) }
    val installDate = remember { installDate(context) }
    var serviceEnabled by remember { mutableStateOf(BlockerAccessibilityService.isEnabled(context)) }

    // Brouillon des réglages : part de la configuration en attente si elle existe.
    var draft by remember { mutableStateOf(state.pending ?: state.active) }

    fun refresh() {
        now = LocalDateTime.now()
        state = repository.load()
        stats = statsRepository.load()
        serviceEnabled = BlockerAccessibilityService.isEnabled(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            refresh()
        }
    }

    val active = state.active
    val blocked = state.isBlockedAt(now)
    val canEdit = state.canEdit(now)
    val nextChange = active.nextChange(now)
    val savedDraft = state.pending ?: active
    val hasChanges = draft != savedDraft

    Scaffold(topBar = { TopAppBar(title = { Text("FocusBlocker") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!serviceEnabled) {
                InfoCard(
                    title = "⚠️ Protection désactivée",
                    text = "Activez « FocusBlocker » dans les paramètres d'accessibilité pour que le blocage fonctionne.",
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Ouvrir les paramètres") }
                }
            }

            InfoCard(
                title = if (blocked) "🔒 Réseaux sociaux bloqués" else "🔓 Accès libre",
                text = buildString {
                    if (nextChange != null) {
                        append(if (blocked) "Jusqu'à " else "Prochain blocage : ")
                        append(formatTime(nextChange, now))
                        append("\n")
                    }
                    append("Shorts YouTube : toujours bloqués")
                },
                color = if (blocked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )

            StatsSection(
                stats = stats,
                today = now.toLocalDate(),
                state = state.resolved(now.toLocalDate()),
                daysWithoutLoosening = state.daysWithoutLoosening(now.toLocalDate(), installDate),
            )

            if (!canEdit) {
                InfoCard(
                    title = "Réglages verrouillés",
                    text = "Les réglages ne sont modifiables qu'en dehors des plages de blocage" +
                        (nextChange?.let { ", à partir de ${formatTime(it, now)}." } ?: "."),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }

            if (state.pending != null) {
                InfoCard(
                    title = "Modifications en attente",
                    text = "Elles prendront effet demain. Aujourd'hui : ${describe(active)}.",
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    if (canEdit) {
                        TextButton(onClick = {
                            repository.save(state.withoutPending(LocalDateTime.now()))
                            refresh()
                            draft = state.active
                        }) { Text("Annuler ces modifications") }
                    }
                }
            }

            ScheduleSection(draft, enabled = canEdit) { draft = it }
            AppsSection(draft, enabled = canEdit) { draft = it }

            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = hasChanges,
                        onClick = {
                            refresh()
                            if (state.canEdit(now)) {
                                repository.save(state.withEdit(draft, now))
                                refresh()
                            }
                        },
                    ) { Text("Enregistrer (effet demain)") }
                    OutlinedButton(enabled = hasChanges, onClick = { draft = savedDraft }) { Text("Annuler") }
                }
                Text(
                    "Pour éviter les contournements, toute modification ne s'applique qu'à partir du lendemain.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            UpdateSection()
        }
    }
}

@Composable
private fun StatsSection(stats: BlockStats, today: LocalDate, state: ConfigState, daysWithoutLoosening: Long) {
    Section("Statistiques") {
        Text("Ouvertures bloquées", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatTile("Aujourd'hui", stats.day(today), Modifier.weight(1f))
            StatTile("Semaine", stats.week(today), Modifier.weight(1f))
            StatTile("Mois", stats.month(today), Modifier.weight(1f))
            StatTile("Total", stats.total(), Modifier.weight(1f))
        }
        Text("Semaine : depuis lundi · Mois : depuis le 1er", style = MaterialTheme.typography.bodySmall)

        // Le compteur ne repart de zéro que si les règles sont assouplies ; un renforcement le laisse courir.
        Text(
            if (state.loosenedOn == today) {
                "Paramètres assouplis aujourd'hui : compteur remis à zéro"
            } else {
                "Paramètres inchangés depuis ${plural(daysWithoutLoosening, "jour")}"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        state.tightenedOn?.let { date ->
            Text(
                "💪 Règles renforcées ${formatDay(date, today)} : le compteur continue !",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state.pendingChange) {
            ConfigChange.LOOSER -> Text(
                "La modification en attente assouplit vos règles : ce compteur repartira de zéro demain.",
                style = MaterialTheme.typography.bodySmall,
            )
            ConfigChange.STRICTER -> Text(
                "La modification en attente renforce vos règles : le compteur continuera demain.",
                style = MaterialTheme.typography.bodySmall,
            )
            ConfigChange.NONE -> Unit
        }
    }
}

@Composable
private fun StatTile(label: String, count: BlockCount, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.total.toString(), style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("dont ${count.shorts} Shorts", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedVersion(context) }
    var available by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Section("Mises à jour") {
        Text("Version installée : ${Updater.versionName(installed)}")
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        val latest = available
        if (latest == null) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    message = "Recherche…"
                    message = try {
                        val version = Updater.latestVersion()
                        if (version > installed) {
                            available = version
                            "Nouvelle version disponible : ${Updater.versionName(version)}"
                        } else {
                            "Vous avez la dernière version."
                        }
                    } catch (e: Exception) {
                        "Impossible de vérifier. Êtes-vous connecté à Internet ?"
                    }
                    busy = false
                }
            }) { Text("Vérifier les mises à jour") }
        } else {
            Button(enabled = !busy, onClick = {
                if (!Updater.canInstall(context)) {
                    message = "Autorisez FocusBlocker à installer des applications, revenez ici, puis touchez à nouveau « Installer »."
                    Updater.openInstallPermissionSettings(context)
                } else {
                    scope.launch {
                        busy = true
                        message = "Téléchargement…"
                        try {
                            Updater.install(context, Updater.download(context))
                            message = "Confirmez l'installation dans la fenêtre d'Android."
                        } catch (e: Exception) {
                            message = "Échec du téléchargement. Réessayez."
                        }
                        busy = false
                    }
                }
            }) { Text("Installer la version ${Updater.versionName(latest)}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(config: BlockConfig, enabled: Boolean, onChange: (BlockConfig) -> Unit) {
    val context = LocalContext.current
    Section("Horaires de blocage") {
        if (config.ranges.isEmpty()) Text("Aucune plage : les réseaux sociaux ne seront jamais bloqués.")
        config.ranges.forEachIndexed { index, range ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(range.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = enabled, onClick = {
                    pickRange(context, range) { new ->
                        onChange(config.copy(ranges = config.ranges.toMutableList().also { it[index] = new }))
                    }
                }) { Text("Modifier") }
                TextButton(enabled = enabled, onClick = {
                    onChange(config.copy(ranges = config.ranges.filterIndexed { i, _ -> i != index }))
                }) { Text("Supprimer") }
            }
        }
        TextButton(enabled = enabled, onClick = {
            pickRange(context, null) { new -> onChange(config.copy(ranges = config.ranges + new)) }
        }) { Text("+ Ajouter une plage") }

        Text("Jours", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = day in config.days,
                    enabled = enabled,
                    onClick = {
                        val days = if (day in config.days) config.days - day else config.days + day
                        onChange(config.copy(days = days))
                    },
                    label = { Text(dayShort(day)) },
                )
            }
        }
    }
}

@Composable
private fun AppsSection(config: BlockConfig, enabled: Boolean, onChange: (BlockConfig) -> Unit) {
    val context = LocalContext.current
    Section("Applications bloquées pendant ces horaires") {
        SocialApps.all.forEach { app ->
            val checked = app.packageName in config.blockedPackages
            val installed = remember(app.packageName) { isInstalled(context, app.packageName) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        val packages = if (checked) config.blockedPackages - app.packageName else config.blockedPackages + app.packageName
                        onChange(config.copy(blockedPackages = packages))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
                Text(app.name + if (installed) "" else " (non installée)")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String, color: Color, actions: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

private fun describe(config: BlockConfig): String =
    if (config.ranges.isEmpty()) "aucune plage" else config.ranges.joinToString(", ")

/** Demande l'heure de début puis l'heure de fin. */
private fun pickRange(context: Context, initial: TimeRange?, onResult: (TimeRange) -> Unit) {
    val start = initial?.startMinute ?: (9 * 60)
    val end = initial?.endMinute ?: (12 * 60)
    TimePickerDialog(context, { _, sh, sm ->
        val startMinute = sh * 60 + sm
        TimePickerDialog(context, { _, eh, em ->
            val endMinute = eh * 60 + em
            if (endMinute != startMinute) onResult(TimeRange(startMinute, endMinute))
        }, end / 60, end % 60, true).apply { setTitle("Fin (début : ${formatMinute(startMinute)})") }.show()
    }, start / 60, start % 60, true).apply { setTitle("Début de la plage") }.show()
}

private fun formatDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "aujourd'hui"
    today.minusDays(1) -> "hier"
    else -> "le " + date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH))
}

private fun plural(n: Long, word: String) = if (n <= 1) "$n $word" else "$n ${word}s"

private fun installDate(context: Context): LocalDate =
    Instant.ofEpochMilli(context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

private fun isInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}
