package org.tasks.compose.settings

import org.tasks.themes.TasksIcons
import org.tasks.compose.components.SymbolIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.tasks.compose.rememberDateFormatter
import org.tasks.extensions.pickDirectory
import org.tasks.viewmodel.BackupOperationFailed
import org.tasks.viewmodel.BackupSucceeded
import org.tasks.viewmodel.BackupsViewModel
import org.tasks.viewmodel.ImportSucceeded
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.back
import tasks.kmp.generated.resources.backup_BPr_header
import tasks.kmp.generated.resources.backup_directory
import tasks.kmp.generated.resources.backup_failed
import tasks.kmp.generated.resources.backup_last
import tasks.kmp.generated.resources.backup_local
import tasks.kmp.generated.resources.backup_now
import tasks.kmp.generated.resources.backup_restore
import tasks.kmp.generated.resources.backup_succeeded
import tasks.kmp.generated.resources.import_backup
import tasks.kmp.generated.resources.import_succeeded
import tasks.kmp.generated.resources.ignore_backup_warnings
import tasks.kmp.generated.resources.last_backup_never
import tasks.kmp.generated.resources.no_backups

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsDetail(
    onNavigateBack: () -> Unit,
) {
    val viewModel = koinViewModel<BackupsViewModel>()
    val scope = rememberCoroutineScope()
    val formatter = rememberDateFormatter(is24Hour = false)

    LaunchedEffect(Unit) {
        viewModel.loadAvailableBackups()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.backup_BPr_header)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        SymbolIcon(
                            name = TasksIcons.ARROW_BACK,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeader(
                title = stringResource(Res.string.backup_local),
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
            )

            PreferenceRow(
                title = stringResource(Res.string.backup_directory),
                summary = viewModel.backupDir,
                summaryMaxLines = 3,
                icon = TasksIcons.PLACE,
                showChevron = true,
                onClick = {
                    scope.launch {
                        val picked = pickDirectory(viewModel.backupDir)
                        if (picked != null) {
                            viewModel.updateBackupDir(picked)
                            viewModel.loadAvailableBackups()
                        }
                    }
                },
            )
            PreferenceRow(
                title = stringResource(Res.string.backup_now),
                icon = TasksIcons.SD_STORAGE,
                enabled = !viewModel.inFlight,
                trailing = {
                    if (viewModel.inFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                onClick = {
                    viewModel.backupNow()
                    viewModel.loadAvailableBackups()
                },
            )
            PreferenceRow(
                title = stringResource(Res.string.backup_last),
                summary = viewModel.lastBackup?.let {
                    formatter?.fullDateTime(it)
                } ?: stringResource(Res.string.last_backup_never),
                icon = TasksIcons.HISTORY,
            )
            PreferenceRow(
                title = stringResource(Res.string.ignore_backup_warnings),
                icon = TasksIcons.WARNING,
                trailing = {
                    Switch(
                        checked = viewModel.ignoreWarnings,
                        onCheckedChange = viewModel::updateIgnoreWarnings,
                    )
                },
            )

            SectionHeader(
                title = stringResource(Res.string.backup_restore),
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
            )

            if (viewModel.availableBackups.isEmpty()) {
                Text(
                    text = stringResource(Res.string.no_backups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            } else {
                viewModel.availableBackups.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = entry.filename,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = formatter?.fullDateTime(entry.timestamp)
                                    .orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = !viewModel.inFlight,
                            onClick = {
                                viewModel.importBackup(entry.filename)
                                viewModel.loadAvailableBackups()
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(Res.string.import_backup))
                        }
                    }
                }
            }

            viewModel.lastResult?.let { result ->
                val (text, isError) = resultLabel(result)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun resultLabel(result: org.tasks.viewmodel.BackupOperationResult): Pair<String, Boolean> {
    return when (result) {
        is BackupSucceeded ->
            stringResource(Res.string.backup_succeeded, result.taskCount) to false
        is ImportSucceeded ->
            stringResource(
                Res.string.import_succeeded,
                result.imported,
                result.taskCount,
                result.skipped,
            ) to false
        is BackupOperationFailed ->
            (result.message?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.backup_failed)) to true
    }
}
