package app.shaqati.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.BuildConfig
import app.shaqati.R
import app.shaqati.core.sources.Districts
import app.shaqati.data.KeyVault
import app.shaqati.data.Settings
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.clock
import app.shaqati.ui.components.isArabic
import app.shaqati.ui.components.money
import app.shaqati.ui.components.sourceName
import app.shaqati.ui.components.timeAgo
import app.shaqati.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, openPicker: (String) -> Unit, openHidden: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val s = settings ?: return
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.testTag("settings_list"),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Group(stringResource(R.string.set_work)) {
                    val district = Districts.near(s.work, 3.0).firstOrNull()
                    Text(
                        if (s.workConfirmed) listOfNotNull(s.workLabel.ifBlank { null }, district?.let { if (isArabic()) it.ar else it.en }).joinToString(" · ")
                        else stringResource(R.string.set_work_default),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(String.format(Locale.ROOT, "%.5f, %.5f", s.work.lat, s.work.lng), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { openPicker("work") }) { Text(stringResource(R.string.set_work_change)) }
                    Spacer(Modifier.height(8.dp))
                    SliderRow(
                        stringResource(R.string.set_radius, String.format(Locale.ROOT, "%.0f", s.searchRadiusKm)),
                        s.searchRadiusKm.toFloat(), 4f..15f, 10,
                    ) { v -> vm.updateSettings { it.copy(searchRadiusKm = v.roundToInt().toDouble()) } }
                }
            }
            item { RequirementsGroup(vm, s) }
            item { CommuteGroup(vm, s, openPicker) }
            item { KeysGroup(vm) }
            item { AiGroup(vm, s) }
            item {
                Group(stringResource(R.string.set_sources)) {
                    Settings.SUPPORTED_SOURCES.forEach { src ->
                        val st = catalog.sources[src.key]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sourceName(src), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when {
                                        st == null -> stringResource(R.string.source_status_never)
                                        st.blocked -> stringResource(R.string.source_status_blocked)
                                        !st.ok -> stringResource(R.string.source_status_failed, st.message ?: "")
                                        else -> stringResource(R.string.source_status_ok, st.found, timeAgo(st.lastRunAt))
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (st != null && !st.ok) AppTheme.status.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            Switch(
                                checked = src in s.enabledSources,
                                onCheckedChange = { on -> vm.updateSettings { it.copy(enabledSources = if (on) it.enabledSources + src else it.enabledSources - src) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sources_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Group(stringResource(R.string.set_auto_refresh)) {
                    ChipRow(listOf(0, 3, 6, 12, 24), s.autoRefreshHours, { h ->
                        if (h == 0) stringResource(R.string.auto_off) else stringResource(R.string.auto_hours, h)
                    }) { h -> vm.updateSettings { it.copy(autoRefreshHours = h) } }
                }
            }
            item {
                Group(stringResource(R.string.set_language)) {
                    ChipRow(listOf("", "ar", "en"), s.language, { t ->
                        when (t) {
                            "ar" -> stringResource(R.string.lang_ar)
                            "en" -> stringResource(R.string.lang_en)
                            else -> stringResource(R.string.lang_system)
                        }
                    }) { vm.setLanguage(it) }
                }
            }
            item {
                Group(stringResource(R.string.set_data)) {
                    if (hidden.isNotEmpty()) {
                        TextButton(onClick = openHidden) { Text(stringResource(R.string.hidden_count, hidden.size)) }
                    }
                    OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.set_clear)) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.set_about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.set_clear)) },
            text = { Text(stringResource(R.string.set_clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearCatalog() }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RequirementsGroup(vm: AppViewModel, s: Settings) {
    val c = s.criteria
    Group(stringResource(R.string.set_requirements)) {
        SliderRow(stringResource(R.string.set_budget, money(c.maxAnnualRent)), (c.maxAnnualRent / 1000).toFloat(), 20f..120f, 99) { v ->
            vm.updateSettings { it.copy(criteria = it.criteria.copy(maxAnnualRent = v.roundToInt() * 1000.0)) }
        }
        Stepper(stringResource(R.string.set_rooms, c.minRooms), c.minRooms, 1..6) { n -> vm.updateSettings { it.copy(criteria = it.criteria.copy(minRooms = n)) } }
        Stepper(stringResource(R.string.set_bathrooms, c.minBathrooms), c.minBathrooms, 1..5) { n -> vm.updateSettings { it.copy(criteria = it.criteria.copy(minBathrooms = n)) } }
        Stepper(stringResource(R.string.set_living, c.minLivingRooms), c.minLivingRooms, 0..3) { n -> vm.updateSettings { it.copy(criteria = it.criteria.copy(minLivingRooms = n)) } }
        SliderRow(stringResource(R.string.set_commute_max, c.maxMorningMinutes), c.maxMorningMinutes.toFloat(), 15f..75f, 11) { v ->
            vm.updateSettings { it.copy(criteria = it.criteria.copy(maxMorningMinutes = (v / 5).roundToInt() * 5)) }
        }
        SliderRow(stringResource(R.string.set_max_age, c.maxListingAgeDays), c.maxListingAgeDays.toFloat(), 15f..180f, 10) { v ->
            vm.updateSettings { it.copy(criteria = it.criteria.copy(maxListingAgeDays = (v / 15).roundToInt() * 15)) }
        }
        SwitchRow(stringResource(R.string.set_allow_furnished), c.allowFurnished) { on -> vm.updateSettings { it.copy(criteria = it.criteria.copy(allowFurnished = on)) } }
        SwitchRow(stringResource(R.string.set_exclude_singles), c.excludeSinglesOnly) { on -> vm.updateSettings { it.copy(criteria = it.criteria.copy(excludeSinglesOnly = on)) } }
    }
}

@Composable
private fun CommuteGroup(vm: AppViewModel, s: Settings, openPicker: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Group(stringResource(R.string.set_commute)) {
        Text(
            stringResource(R.string.set_departures, clock(s.schedule.morningDeparture), clock(s.schedule.eveningDeparture)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        ChipRow(listOf("07:00", "07:30", "08:00", "08:30"), s.schedule.morningDeparture, { clock(it) }) { t ->
            vm.updateSettings { it.copy(schedule = it.schedule.copy(morningDeparture = t)) }
        }
        ChipRow(listOf("16:00", "16:30", "17:00", "17:30"), s.schedule.eveningDeparture, { clock(it) }) { t ->
            vm.updateSettings { it.copy(schedule = it.schedule.copy(eveningDeparture = t)) }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        SwitchRow(stringResource(R.string.set_calibration), s.calibration.enabled) { on ->
            vm.updateSettings { it.copy(calibration = it.calibration.copy(enabled = on)) }
        }
        if (s.calibration.enabled) {
            Text(
                stringResource(R.string.set_calibration_body, s.calibration.actualMorningMinutes, s.calibration.actualEveningMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Stepper(stringResource(R.string.set_morning_actual, s.calibration.actualMorningMinutes), s.calibration.actualMorningMinutes, 10..150, 5) { n ->
                vm.updateSettings { it.copy(calibration = it.calibration.copy(actualMorningMinutes = n)) }
            }
            Stepper(stringResource(R.string.set_evening_actual, s.calibration.actualEveningMinutes), s.calibration.actualEveningMinutes, 10..150, 5) { n ->
                vm.updateSettings { it.copy(calibration = it.calibration.copy(actualEveningMinutes = n)) }
            }
            TextButton(onClick = { openPicker("home") }) { Text(stringResource(R.string.set_calibration_home)) }
            val learned = s.corrections
            Text(
                if (learned != null && learned.forecastMorning != null) {
                    stringResource(
                        R.string.set_calibration_learned,
                        learned.forecastMorning, s.calibration.actualMorningMinutes,
                        String.format(Locale.ROOT, "%.2f", learned.morning.factor), learned.morning.fixedMinutes,
                    )
                } else stringResource(R.string.set_calibration_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vm.googleKeySet()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { vm.recalibrate(); busy = false }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.set_recalibrate))
                }
            }
        }
    }
}

@Composable
private fun KeysGroup(vm: AppViewModel) {
    Group(stringResource(R.string.set_keys)) {
        KeyEditor(
            title = stringResource(R.string.set_google_key),
            body = stringResource(R.string.set_google_key_body),
            isSet = vm.googleKeySet(),
            onSave = { vm.setKey(KeyVault.GOOGLE, it) },
            verify = { key -> vm.verifyGoogle(key) != null },
        )
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        KeyEditor(
            title = stringResource(R.string.set_anthropic_key),
            body = stringResource(R.string.set_anthropic_key_body),
            isSet = vm.anthropicKeySet(),
            onSave = { vm.setKey(KeyVault.ANTHROPIC, it) },
            verify = { key -> vm.verifyAnthropic(key) },
        )
    }
}

@Composable
fun KeyEditor(title: String, body: String, isSet: Boolean, onSave: (String?) -> Unit, verify: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(isSet) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            result == true -> stringResource(R.string.key_ok)
            result == false -> stringResource(R.string.key_bad)
            saved -> stringResource(R.string.key_set)
            else -> stringResource(R.string.key_missing)
        },
        style = MaterialTheme.typography.labelMedium,
        color = when {
            result == false -> AppTheme.status.bad
            saved || result == true -> AppTheme.status.good
            else -> AppTheme.status.warn
        },
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.trim(); result = null },
        placeholder = { Text(stringResource(R.string.key_paste_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = text.isNotBlank(), onClick = { onSave(text); saved = true; text = "" }) { Text(stringResource(R.string.action_save_key)) }
        TextButton(enabled = text.isNotBlank() && !checking, onClick = {
            checking = true
            val k = text
            scope.launch {
                result = verify(k)
                checking = false
                if (result == true) { onSave(k); saved = true; text = "" }
            }
        }) { Text(stringResource(R.string.key_check)) }
        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (saved) TextButton(onClick = { onSave(null); saved = false; result = null }) { Text(stringResource(R.string.action_remove)) }
    }
}

@Composable
private fun AiGroup(vm: AppViewModel, s: Settings) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val ai = s.ai
    Group(stringResource(R.string.set_ai)) {
        SwitchRow(stringResource(R.string.set_ai_enabled), ai.enabled) { on -> vm.updateSettings { it.copy(ai = it.ai.copy(enabled = on)) } }
        SwitchRow(stringResource(R.string.set_ai_batch), ai.useBatch) { on -> vm.updateSettings { it.copy(ai = it.ai.copy(useBatch = on)) } }
        SliderRow(stringResource(R.string.set_ai_cap, String.format(Locale.ROOT, "%.0f", ai.monthlyCapUsd)), ai.monthlyCapUsd.toFloat(), 1f..30f, 28) { v ->
            vm.updateSettings { it.copy(ai = it.ai.copy(monthlyCapUsd = v.roundToInt().toDouble())) }
        }
        Stepper(stringResource(R.string.set_ai_photos, ai.photosPerListing), ai.photosPerListing, 3..8) { n -> vm.updateSettings { it.copy(ai = it.ai.copy(photosPerListing = n)) } }
        Stepper(stringResource(R.string.set_ai_per_refresh, ai.maxPerRefresh), ai.maxPerRefresh, 5..60, 5) { n -> vm.updateSettings { it.copy(ai = it.ai.copy(maxPerRefresh = n)) } }
        val month = java.time.LocalDate.now(java.time.ZoneOffset.UTC).let { "%04d-%02d".format(Locale.ROOT, it.year, it.monthValue) }
        val spend = catalog.spend.firstOrNull { it.month == month }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.set_ai_spend,
                String.format(Locale.ROOT, "%.2f", spend?.usd ?: 0.0),
                String.format(Locale.ROOT, "%.0f", ai.monthlyCapUsd),
                spend?.assessed ?: 0,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.set_ai_cost, String.format(Locale.ROOT, "%.3f", vm.aiCostPerListing(s))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) { content() }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onChange(local) },
        valueRange = range,
        steps = steps,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(enabled = value - step >= range.first, onClick = { onChange(value - step) }) { Icon(Icons.Outlined.Remove, "-") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        IconButton(enabled = value + step <= range.last, onClick = { onChange(value + step) }) { Icon(Icons.Outlined.Add, "+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
        }
    }
}
