package io.github.rhythmcache.dioxamine.fastboot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.fastboot.FastbootClient

private data class RebootOption(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val onSelect: (FastbootViewModel) -> Unit,
)

private val rebootOptions = listOf(
    RebootOption(
        icon = Icons.Filled.RestartAlt,
        titleRes = R.string.fastboot_reboot_system_title,
        descriptionRes = R.string.fastboot_reboot_system_desc,
        onSelect = { it.reboot(FastbootClient.RebootTarget.SYSTEM) },
    ),
    RebootOption(
        icon = Icons.Filled.SettingsApplications,
        titleRes = R.string.fastboot_reboot_bootloader_title,
        descriptionRes = R.string.fastboot_reboot_bootloader_desc,
        onSelect = { it.reboot(FastbootClient.RebootTarget.BOOTLOADER) },
    ),
    RebootOption(
        icon = Icons.Filled.Build,
        titleRes = R.string.fastboot_reboot_recovery_title,
        descriptionRes = R.string.fastboot_reboot_recovery_desc,
        onSelect = { it.reboot(FastbootClient.RebootTarget.RECOVERY) },
    ),
    RebootOption(
        icon = Icons.Filled.Bolt,
        titleRes = R.string.fastboot_reboot_fastboot_title,
        descriptionRes = R.string.fastboot_reboot_fastboot_desc,
        onSelect = { it.reboot(FastbootClient.RebootTarget.FASTBOOT) },
    ),
    RebootOption(
        icon = Icons.Filled.PlayArrow,
        titleRes = R.string.fastboot_continue_boot_title,
        descriptionRes = R.string.fastboot_continue_boot_desc,
        onSelect = { it.continueBoot() },
    ),
    RebootOption(
        icon = Icons.Filled.PowerSettingsNew,
        titleRes = R.string.fastboot_shutdown_title,
        descriptionRes = R.string.fastboot_shutdown_desc,
        onSelect = { it.shutdown() },
    ),
)

private data class LockOption(
    val icon: ImageVector,
    val titleRes: Int,
    val description: String,
    val onSelect: (FastbootViewModel) -> Unit,
)

private val lockOptions = listOf(
    LockOption(Icons.Filled.LockOpen, R.string.fastboot_unlock_bootloader_title, "flashing unlock") {
        it.setLockMode(FastbootClient.LockMode.UNLOCK)
    },
    LockOption(Icons.Filled.Lock, R.string.fastboot_lock_bootloader_title, "flashing lock") {
        it.setLockMode(FastbootClient.LockMode.LOCK)
    },
    LockOption(Icons.Filled.LockOpen, R.string.fastboot_unlock_critical_title, "flashing unlock_critical") {
        it.setLockMode(FastbootClient.LockMode.UNLOCK_CRITICAL)
    },
    LockOption(Icons.Filled.Lock, R.string.fastboot_lock_critical_title, "flashing lock_critical") {
        it.setLockMode(FastbootClient.LockMode.LOCK_CRITICAL)
    },
    LockOption(Icons.AutoMirrored.Filled.HelpOutline, R.string.fastboot_check_unlock_title, "flashing get_unlock_ability") {
        it.getUnlockAbility()
    },
)

@Composable
fun RebootScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FastbootDetailHeader(title = stringResource(R.string.fastboot_reboot_title), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        OperationStatusCard(
            status = vm.lastOperationStatus,
            onDismiss = { vm.clearOperationStatus() },
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rebootOptions) { option ->
                FastbootActionTile(
                    icon = option.icon,
                    title = stringResource(option.titleRes),
                    description = stringResource(option.descriptionRes),
                    enabled = !vm.isBusy,
                    onClick = { option.onSelect(vm) },
                )
            }
        }
    }
}

@Composable
fun LockStateScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FastbootDetailHeader(title = stringResource(R.string.fastboot_lock_title), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.fastboot_lock_warning),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))

        OperationStatusCard(
            status = vm.lastOperationStatus,
            onDismiss = { vm.clearOperationStatus() },
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lockOptions) { option ->
                FastbootActionTile(
                    icon = option.icon,
                    title = stringResource(option.titleRes),
                    description = option.description,
                    enabled = !vm.isBusy,
                    onClick = { option.onSelect(vm) },
                )
            }
        }
    }
}
