package io.github.rhythmcache.dioxamine.fastboot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R

@Composable
fun VariablesScreen(vm: FastbootViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        FastbootDetailHeader(title = stringResource(R.string.fastboot_vars_title), onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.fastboot_vars_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(stringResource(R.string.fastboot_var_name_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.End),
        ) {
            OutlinedButton(
                enabled = !vm.isBusy,
                onClick = { vm.getAllVars() },
            ) { Text(stringResource(R.string.fastboot_btn_get_all)) }
            Button(
                enabled = key.isNotBlank() && !vm.isBusy,
                onClick = { vm.getVar(key.trim()) },
            ) { Text(stringResource(R.string.fastboot_btn_get_var)) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OperationStatusCard(
            status = vm.lastOperationStatus,
            onDismiss = { vm.clearOperationStatus() },
        )
    }
}
