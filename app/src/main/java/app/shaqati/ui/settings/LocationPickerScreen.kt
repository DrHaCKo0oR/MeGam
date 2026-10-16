package app.shaqati.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shaqati.R
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.sources.Districts
import app.shaqati.ui.AppViewModel
import app.shaqati.ui.components.isArabic
import app.shaqati.ui.map.MapWeb
import app.shaqati.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun LocationPickerScreen(vm: AppViewModel, target: String, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val start = if (target == "home") s.calibration.home else s.work
    var center by remember { mutableStateOf(start) }
    var moveTo by remember { mutableStateOf<GeoPoint?>(null) }
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ar = isArabic()
    val key = remember { vm.googleKey() }

    Box(Modifier.fillMaxSize()) {
        MapWeb(
            modifier = Modifier.fillMaxSize(),
            googleKey = key,
            language = if (ar) "ar" else "en",
            center = start,
            zoom = 16,
            payload = null,
            pick = true,
            moveTo = moveTo,
            onCenter = { center = it },
        )
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 3.dp) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                        Text(
                            stringResource(if (target == "home") R.string.pick_home_title else R.string.pick_work_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        placeholder = { Text(stringResource(R.string.pick_search_district)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val matches = remember(query) {
                        if (query.isBlank()) emptyList()
                        else {
                            val k = Districts.key(query)
                            Districts.all.filter { Districts.key(it.ar).contains(k) || it.en.lowercase(Locale.ROOT).contains(query.trim().lowercase(Locale.ROOT)) }.take(6)
                        }
                    }
                    if (matches.isNotEmpty()) {
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(matches) { d ->
                                Text(
                                    if (ar) d.ar else d.en,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        moveTo = d.center
                                        center = d.center
                                        query = ""
                                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; linkError = false },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                        placeholder = { Text(stringResource(R.string.pick_paste_link)) },
                        singleLine = true,
                        isError = linkError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            resolving = true
                            scope.launch {
                                val p = vm.resolveMapLink(link)
                                resolving = false
                                if (p == null) linkError = true else { moveTo = p; center = p; link = "" }
                            }
                        }),
                        trailingIcon = { if (resolving) CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    if (linkError) {
                        Text(stringResource(R.string.pick_link_bad), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.bad, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                    }
                }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(10.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                val district = Districts.near(center, 3.0).firstOrNull()
                Text(stringResource(R.string.pick_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(district?.let { if (ar) it.ar else it.en }, String.format(Locale.ROOT, "%.5f, %.5f", center.lat, center.lng)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (target == "home") vm.setCalibrationHome(center) else vm.setWork(center, district?.let { if (ar) it.ar else it.en } ?: "")
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pick_use)) }
            }
        }
    }
}
