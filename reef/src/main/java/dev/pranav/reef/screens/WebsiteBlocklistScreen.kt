package dev.pranav.reef.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.reef.R
import dev.pranav.reef.util.WebsiteBlocklist
import dev.pranav.reef.util.WebsiteLimits

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebsiteBlocklistScreen(onBackPressed: () -> Unit) {
    val blockedDomains =
        remember { mutableStateListOf(*WebsiteBlocklist.getBlockedDomains().toTypedArray()) }
    val limitDomains =
        remember { mutableStateMapOf<String, Long>().apply { putAll(WebsiteLimits.getDomainsWithLimits()) } }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingDomain by remember { mutableStateOf<String?>(null) }
    var editingIsLimit by remember { mutableStateOf(false) }
    var editingHours by remember { mutableStateOf(0) }
    var editingMinutes by remember { mutableStateOf(30) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.website_blocklist)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingDomain = null
                    editingIsLimit = false
                    editingHours = 0
                    editingMinutes = 30
                    showAddDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_website)) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BlocklistInfoCard()
            }

            if (blockedDomains.isEmpty() && limitDomains.isEmpty()) {
                item {
                    BlocklistEmptyState()
                }
            } else {
                if (blockedDomains.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.permanently_blocked),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(blockedDomains, key = { "blocked_$it" }) { domain ->
                        DomainItem(
                            domain = domain,
                            subtitle = stringResource(R.string.always_blocked),
                            onClick = {
                                editingDomain = domain
                                editingIsLimit = false
                                editingHours = 0
                                editingMinutes = 0
                                showAddDialog = true
                            },
                            onRemove = {
                                WebsiteBlocklist.removeDomain(domain)
                                blockedDomains.remove(domain)
                            }
                        )
                    }
                }

                if (limitDomains.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.daily_limits),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    items(limitDomains.keys.toList(), key = { "limit_$it" }) { domain ->
                        val limitMs = limitDomains[domain] ?: 0L
                        val hours = (limitMs / 3600_000).toInt()
                        val mins = ((limitMs % 3600_000) / 60_000).toInt()
                        val subtitleFormat = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        DomainItem(
                            domain = domain,
                            subtitle = stringResource(R.string.limit_format, subtitleFormat),
                            onClick = {
                                editingDomain = domain
                                editingIsLimit = true
                                editingHours = hours
                                editingMinutes = mins
                                showAddDialog = true
                            },
                            onRemove = {
                                WebsiteLimits.removeLimit(domain)
                                limitDomains.remove(domain)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        val sheetState = rememberBottomSheetState(SheetValue.Hidden)
        var newDomain by remember { mutableStateOf(editingDomain ?: "") }
        var isLimit by remember { mutableStateOf(editingIsLimit) }
        var limitMinutes by remember { mutableIntStateOf(if (editingIsLimit) editingHours * 60 + editingMinutes else 30) }

        ModalBottomSheet(
            onDismissRequest = { showAddDialog = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (editingDomain != null) stringResource(R.string.edit_website) else stringResource(
                        R.string.add_website
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.domain_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    enabled = editingDomain == null
                )

                Text(
                    text = stringResource(R.string.block_type),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = !isLimit,
                        onCheckedChange = { isLimit = false },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.always_block))
                    }
                    ToggleButton(
                        checked = isLimit,
                        onCheckedChange = { isLimit = true },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.daily_limit))
                    }
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (!isLimit) stringResource(R.string.website_always_block_desc) else stringResource(
                            R.string.website_daily_limit_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                AnimatedVisibility(visible = isLimit) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.daily_time_limit),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val hoursStr = if (limitMinutes >= 60) "${limitMinutes / 60}h " else ""
                        val minsStr =
                            if (limitMinutes % 60 > 0 || limitMinutes == 0) "${limitMinutes % 60}m" else ""
                        Text(
                            text = hoursStr + minsStr,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                8.dp,
                                Alignment.CenterHorizontally
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (limitMinutes >= 15) limitMinutes -= 15 else limitMinutes = 0
                                },
                                enabled = limitMinutes > 0,
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("−15m") }

                            FilledTonalButton(
                                onClick = {
                                    if (limitMinutes >= 1) limitMinutes -= 1 else limitMinutes = 0
                                },
                                enabled = limitMinutes > 0,
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("−1m") }

                            FilledTonalButton(
                                onClick = { limitMinutes += 1 },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("+1m") }

                            FilledTonalButton(
                                onClick = { limitMinutes += 15 },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("+15m") }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { preset ->
                                FilterChip(
                                    selected = limitMinutes == preset,
                                    onClick = { limitMinutes = preset },
                                    label = {
                                        val h = if (preset >= 60) "${preset / 60}h " else ""
                                        val m =
                                            if (preset % 60 > 0 || preset == 0) "${preset % 60}m" else ""
                                        Text(h + m)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { showAddDialog = false },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newDomain.isNotBlank()) {
                                val domain = newDomain.trim().lowercase()

                                // Remove existing entries before saving
                                if (editingDomain != null && editingDomain != domain) {
                                    WebsiteBlocklist.removeDomain(editingDomain!!)
                                    blockedDomains.remove(editingDomain!!)
                                    WebsiteLimits.removeLimit(editingDomain!!)
                                    limitDomains.remove(editingDomain!!)
                                } else if (editingDomain != null) {
                                    WebsiteBlocklist.removeDomain(domain)
                                    blockedDomains.remove(domain)
                                    WebsiteLimits.removeLimit(domain)
                                    limitDomains.remove(domain)
                                }

                                if (isLimit) {
                                    if (limitMinutes > 0) {
                                        WebsiteLimits.setLimit(domain, limitMinutes)
                                        limitDomains[domain] = limitMinutes * 60_000L
                                    }
                                } else {
                                    WebsiteBlocklist.addDomain(domain)
                                    blockedDomains.add(domain)
                                }
                                showAddDialog = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun BlocklistInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.blocked_websites),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.blocked_websites_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BlocklistEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = stringResource(R.string.no_routines),
                modifier = Modifier.padding(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_websites_added),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.add_websites_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DomainItem(
    domain: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
