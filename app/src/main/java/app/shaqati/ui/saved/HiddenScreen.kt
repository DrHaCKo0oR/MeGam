package app.shaqati.ui.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.ListingCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScreen(vm: AppViewModel, onBack: () -> Unit, openDetail: (String) -> Unit) {
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.hidden_count, hidden.size)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(hidden, key = { it.id }) { v ->
                Column {
                    ListingCard(
                        v = v,
                        morningTime = settings?.schedule?.morningDeparture ?: "08:00",
                        onClick = { openDetail(v.id) },
                        onToggleSave = { vm.setSaved(v.id, !v.saved) },
                        compact = true,
                    )
                    TextButton(onClick = { v.alsoOn.forEach { vm.unhide(it.id) } }) { Text(stringResource(R.string.action_unhide)) }
                }
            }
        }
    }
}
