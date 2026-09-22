package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.catalog.AssetDeepSkyCatalog
import com.indigo.mobileobservatory.catalog.CatalogBrowseFilter
import com.indigo.mobileobservatory.catalog.CatalogObject
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Target library. Same [AssetDeepSkyCatalog] index the star map search uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetLibraryScreen(
    onBack: () -> Unit,
    onGuideTo: (CatalogObject) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.target_library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        TargetLibraryContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            onGuideTo = onGuideTo
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TargetLibraryContent(
    onGuideTo: (CatalogObject) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val catalog = remember { AssetDeepSkyCatalog(context.applicationContext) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CatalogBrowseFilter.ALL) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var results by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (!PhoneSiteProvider.hasPermission(context)) return@LaunchedEffect
        site = runCatching {
            withContext(Dispatchers.Default) { PhoneSiteProvider.currentSite(context) }
        }.getOrNull()
    }

    LaunchedEffect(query, filter, site) {
        results = withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                catalog.suggest(site, Instant.now(), filter = filter)
            } else {
                catalog.search(query).filter(filter::matches)
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(
                if (query.isBlank() && site != null) R.string.target_library_tonight_banner
                else R.string.target_library_shell_banner
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.target_library_search)) }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogBrowseFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(stringResource(option.labelRes)) }
                )
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results, key = { it.id }) { obj ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGuideTo(obj) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${obj.id} · ${obj.name}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(
                                    R.string.target_library_meta,
                                    obj.type,
                                    obj.magnitude?.let { "%.1f".format(it) } ?: "—",
                                    obj.raHours,
                                    obj.decDeg
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { onGuideTo(obj) }) {
                            Text(stringResource(R.string.target_library_guide))
                        }
                    }
                }
            }
        }
    }
}

private val CatalogBrowseFilter.labelRes: Int
    get() = when (this) {
        CatalogBrowseFilter.ALL -> R.string.target_library_filter_all
        CatalogBrowseFilter.STAR -> R.string.target_library_filter_star
        CatalogBrowseFilter.CLUSTER -> R.string.target_library_filter_cluster
        CatalogBrowseFilter.NEBULA -> R.string.target_library_filter_nebula
        CatalogBrowseFilter.GALAXY -> R.string.target_library_filter_galaxy
    }
