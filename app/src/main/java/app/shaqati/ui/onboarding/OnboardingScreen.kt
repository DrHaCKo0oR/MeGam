package app.shaqati.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.sources.Districts
import app.shaqati.data.KeyVault
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.isArabic
import app.shaqati.ui.components.money
import app.shaqati.ui.settings.KeyEditor
import app.shaqati.ui.theme.AppTheme

@Composable
fun OnboardingScreen(vm: AppViewModel, onPickWork: () -> Unit, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val c = s.criteria
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.TravelExplore, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onb_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Point(Icons.Outlined.DirectionsCar, stringResource(R.string.onb_requirements, money(c.maxAnnualRent), c.minRooms, c.minBathrooms, c.maxMorningMinutes))
        Point(Icons.Outlined.AutoAwesome, stringResource(R.string.onb_ai_point))

        Card {
            Text(stringResource(R.string.set_work), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val district = Districts.near(s.work, 3.0).firstOrNull()
            Text(
                if (s.workConfirmed) listOfNotNull(s.workLabel.ifBlank { null }, district?.let { if (isArabic()) it.ar else it.en }).joinToString(" · ")
                else stringResource(R.string.onb_work),
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.workConfirmed) AppTheme.status.good else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onPickWork) {
                Icon(Icons.Outlined.Work, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.onb_set_work))
            }
        }

        Card {
            Text(stringResource(R.string.onb_keys), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.onb_keys_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
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

        Button(
            onClick = {
                vm.finishOnboarding(start = true)
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.onb_start)) }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}
