package io.github.rhythmcache.dioxamine.adb.builtin.processmanager

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessManagerScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()

    val processClient = remember(client) {
        client?.let { ProcessManagerClient(context, it) }
    }

    var memoryStats by remember { mutableStateOf<SystemMemoryStats?>(null) }
    var processList by remember { mutableStateOf<List<ProcessItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ProcessFilter.ALL) }
    var selectedSort by remember { mutableStateOf(ProcessSort.RAM_DESC) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    val autoRefreshEnabled = true
    var statsExpanded by remember { mutableStateOf(true) }
    val iconCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    var targetAppForForceStop by remember { mutableStateOf<ProcessItem?>(null) }
    var targetProcessForKill by remember { mutableStateOf<ProcessItem?>(null) }

    fun refreshProcesses(silent: Boolean = false) {
        val pClient = processClient ?: return
        coroutineScope.launch {
            if (!silent) {
                if (processList.isEmpty()) isLoading = true else isRefreshing = true
            }
            errorMessage = null

            runCatching {
                pClient.fetchProcesses()
            }.onSuccess { (mem, procs) ->
                memoryStats = mem
                processList = procs
                isLoading = false
                isRefreshing = false

                // Lazy load icons for apps
                launch(Dispatchers.IO) {
                    val appsNeedingIcons = procs.filter {
                        it.packageName.isNotBlank() && !iconCache.containsKey(it.packageName)
                    }.distinctBy { it.packageName }

                    for (app in appsNeedingIcons) {
                        if (!isActive) break
                        val iconBytes = pClient.fetchIcon(app.packageName)
                        if (iconBytes != null && iconBytes.isNotEmpty()) {
                            val bitmap = runCatching {
                                BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)?.asImageBitmap()
                            }.getOrNull()
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    iconCache[app.packageName] = bitmap
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                iconCache[app.packageName] = null
                            }
                        }
                    }
                }
            }.onFailure { err ->
                isLoading = false
                isRefreshing = false
                errorMessage = err.message
                AppLogger.e("ProcessManagerScreen", "Failed to fetch processes: ${err.message}", err)
            }
        }
    }

    // Initial load
    LaunchedEffect(processClient) {
        if (processClient != null && processList.isEmpty()) {
            refreshProcesses()
        }
    }

    // Auto-refresh timer loop
    LaunchedEffect(autoRefreshEnabled, processClient) {
        if (autoRefreshEnabled && processClient != null) {
            while (isActive && autoRefreshEnabled) {
                delay(2000)
                refreshProcesses(silent = true)
            }
        }
    }

    BackHandler(onBack = onBack)

    val allCount = remember(processList.size) { processList.size }
    val userCount = remember(processList.size) { processList.count { it.isUserApp } }
    val systemCount = remember(processList.size) { processList.count { !it.isUserApp } }

    val filteredAndSortedProcesses = remember(processList, searchQuery, selectedFilter, selectedSort) {
        processList.asSequence().filter { item ->
            val matchesFilter = when (selectedFilter) {
                ProcessFilter.ALL -> true
                ProcessFilter.USER_APPS -> item.isUserApp
                ProcessFilter.SYSTEM -> !item.isUserApp
            }
            val matchesSearch = searchQuery.isBlank() ||
                item.processName.contains(searchQuery, ignoreCase = true) ||
                item.appLabel.contains(searchQuery, ignoreCase = true) ||
                item.packageName.contains(searchQuery, ignoreCase = true) ||
                item.pid.toString().contains(searchQuery)

            matchesFilter && matchesSearch
        }.sortedWith { a, b ->
            when (selectedSort) {
                ProcessSort.RAM_DESC -> b.rssKb.compareTo(a.rssKb)
                ProcessSort.CPU_DESC -> b.cpuPercent.compareTo(a.cpuPercent)
                ProcessSort.PID_ASC -> a.pid.compareTo(b.pid)
                ProcessSort.NAME_ASC -> a.displayName.compareTo(b.displayName, ignoreCase = true)
            }
        }.toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.proc_manager_title), fontWeight = FontWeight.Bold)
                        if (memoryStats != null) {
                            Text(
                                text = stringResource(
                                    R.string.proc_manager_processes_count,
                                    allCount,
                                    userCount,
                                    systemCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { statsExpanded = !statsExpanded }
                    ) {
                        Icon(
                            imageVector = if (statsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.cd_expand_collapse)
                        )
                    }
                    IconButton(
                        onClick = { refreshProcesses() },
                        enabled = !isLoading && !isRefreshing
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.btn_refresh)
                        )
                    }
                },
                windowInsets = WindowInsets(0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Loading progress bar during silent refresh
            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }

            // System Memory & CPU Usage Cards (Collapsible)
            AnimatedVisibility(visible = statsExpanded && memoryStats != null) {
                memoryStats?.let { mem ->
                    Column {
                        Spacer(Modifier.height(6.dp))

                        // System Memory Hero Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.proc_manager_ram_usage),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.proc_manager_ram_used_format,
                                            mem.formattedUsed,
                                            mem.formattedTotal,
                                            (mem.usedRatio * 100).toInt()
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { mem.usedRatio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        // CPU Usage Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.proc_manager_cpu_usage),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.proc_manager_cpu_format,
                                            mem.formattedCpu,
                                            mem.cpuCoreCount
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { mem.cpuUsageRatio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (!statsExpanded || memoryStats == null) {
                Spacer(Modifier.height(6.dp))
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.proc_manager_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Filter Chips and Sort Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    FilterChip(
                        selected = selectedFilter == ProcessFilter.ALL,
                        onClick = { selectedFilter = ProcessFilter.ALL },
                        label = { Text(stringResource(R.string.proc_filter_all, allCount), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = selectedFilter == ProcessFilter.USER_APPS,
                        onClick = { selectedFilter = ProcessFilter.USER_APPS },
                        label = { Text(stringResource(R.string.proc_filter_apps, userCount), style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = selectedFilter == ProcessFilter.SYSTEM,
                        onClick = { selectedFilter = ProcessFilter.SYSTEM },
                        label = { Text(stringResource(R.string.proc_filter_system, systemCount), style = MaterialTheme.typography.labelSmall) }
                    )
                }

                Box {
                    IconButton(onClick = { sortDropdownExpanded = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sortDropdownExpanded,
                        onDismissRequest = { sortDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proc_sort_ram)) },
                            onClick = {
                                selectedSort = ProcessSort.RAM_DESC
                                sortDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedSort == ProcessSort.RAM_DESC) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proc_sort_cpu)) },
                            onClick = {
                                selectedSort = ProcessSort.CPU_DESC
                                sortDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedSort == ProcessSort.CPU_DESC) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proc_sort_pid)) },
                            onClick = {
                                selectedSort = ProcessSort.PID_ASC
                                sortDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedSort == ProcessSort.PID_ASC) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proc_sort_name)) },
                            onClick = {
                                selectedSort = ProcessSort.NAME_ASC
                                sortDropdownExpanded = false
                            },
                            leadingIcon = {
                                if (selectedSort == ProcessSort.NAME_ASC) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Main List View
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.proc_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { refreshProcesses() }) {
                            Text(stringResource(R.string.btn_retry))
                        }
                    }
                }
            } else if (filteredAndSortedProcesses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.proc_empty_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = filteredAndSortedProcesses,
                        key = { it.pid }
                    ) { item ->
                        ProcessItemCard(
                            item = item,
                            icon = if (item.packageName.isNotBlank()) iconCache[item.packageName] else null,
                            onForceStop = { targetAppForForceStop = item },
                            onKill = { targetProcessForKill = item }
                        )
                    }
                }
            }
        }
    }

    // Force Stop Dialog
    targetAppForForceStop?.let { app ->
        AlertDialog(
            onDismissRequest = { targetAppForForceStop = null },
            icon = { Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(stringResource(R.string.proc_dialog_force_stop_title, app.displayName))
            },
            text = {
                Text(stringResource(R.string.proc_dialog_force_stop_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pClient = processClient
                        val pkg = app.packageName
                        targetAppForForceStop = null
                        if (pClient != null && pkg.isNotBlank()) {
                            coroutineScope.launch {
                                val result = pClient.forceStop(pkg)
                                if (result.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.proc_toast_force_stop_success, app.displayName),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshProcesses(silent = true)
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.proc_toast_force_stop_failed, app.displayName, result.exceptionOrNull()?.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.proc_btn_force_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { targetAppForForceStop = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Kill PID Dialog
    targetProcessForKill?.let { proc ->
        AlertDialog(
            onDismissRequest = { targetProcessForKill = null },
            icon = { Icon(Icons.Filled.Dangerous, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(stringResource(R.string.proc_dialog_kill_pid_title, proc.displayName, proc.pid))
            },
            text = {
                Text(stringResource(R.string.proc_dialog_kill_pid_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pClient = processClient
                        val pid = proc.pid
                        targetProcessForKill = null
                        if (pClient != null) {
                            coroutineScope.launch {
                                val result = pClient.killPid(pid, 9)
                                if (result.isSuccess) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.proc_toast_kill_success, pid),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshProcesses(silent = true)
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.proc_toast_kill_failed, pid, result.exceptionOrNull()?.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.proc_btn_kill))
                }
            },
            dismissButton = {
                TextButton(onClick = { targetProcessForKill = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProcessItemCard(
    item: ProcessItem,
    icon: ImageBitmap?,
    onForceStop: () -> Unit,
    onKill: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (item.isUserApp) {
                    Icon(
                        Icons.Filled.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.processName != item.displayName) {
                    Text(
                        text = item.processName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Badges & Metrics Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // RAM Badge
                    Text(
                        text = item.formattedRam,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // CPU Badge
                    Text(
                        text = "\u2022  " + stringResource(R.string.proc_badge_cpu, item.formattedCpu),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )

                    // PID Badge
                    Text(
                        text = "\u2022  " + stringResource(R.string.proc_badge_pid, item.pid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isUserApp && item.packageName.isNotBlank()) {
                    IconButton(
                        onClick = onForceStop,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.proc_action_force_stop),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onKill,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.proc_action_kill_pid),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
