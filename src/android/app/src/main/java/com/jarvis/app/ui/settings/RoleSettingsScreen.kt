// +--------------------------------------------------------------+
// | RoleSettingsScreen.kt - 角色设置页面 (Android)              |
// +--------------------------------------------------------------+

package com.jarvis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jarvis.app.R
import com.jarvis.app.agent.RoleStore
import com.jarvis.app.agent.RoleFile
import com.jarvis.app.agent.RoleMetadata
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentRole by RoleStore.currentRoleState.collectAsState()
    var rolesVersion by remember { mutableStateOf(0) }
    val allRoles = remember(rolesVersion) { RoleStore.allRoles(context) }

    var editorVisible by remember { mutableStateOf(false) }
    var editingRole by remember { mutableStateOf<RoleFile?>(null) }
    var deleteTarget by remember { mutableStateOf<RoleFile?>(null) }

    fun refresh() { rolesVersion++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_role)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 当前角色卡片
            item {
                CurrentRoleCard(
                    role = currentRole,
                    onEdit = {
                        editingRole = currentRole
                        editorVisible = true
                    }
                )
            }

            // 角色列表
            items(allRoles, key = { it.roleId }) { role ->
                RoleRow(
                    role = role,
                    currentRoleId = RoleStore.currentRoleId,
                    onSelect = {
                        RoleStore.setCurrentRole(context, role.roleId)
                        refresh()
                    },
                    onEdit = {
                        editingRole = role
                        editorVisible = true
                    },
                    onDelete = { deleteTarget = role }
                )
            }

            // 添加按钮
            item {
                Button(
                    onClick = {
                        editingRole = null
                        editorVisible = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.role_add))
                }
            }
        }
    }

    // 新建 / 编辑角色对话框
    if (editorVisible) {
        RoleEditorDialog(
            existing = editingRole,
            onDismiss = {
                editorVisible = false
                editingRole = null
            },
            onSave = { name, style, lang, body, avatarBytes ->
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    if (editingRole == null) {
                        RoleStore.createRole(context, trimmed, style, lang, body, avatarBytes)
                    } else {
                        val updated = editingRole!!.copy(
                            metadata = RoleMetadata(trimmed, style, lang),
                            body = body,
                            avatarData = avatarBytes
                        )
                        RoleStore.saveRole(context, updated)
                    }
                    refresh()
                }
                editorVisible = false
                editingRole = null
            }
        )
    }

    // 删除确认对话框
    deleteTarget?.let { role ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.role_delete_confirm_title)) },
            text = { Text(stringResource(R.string.role_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        RoleStore.deleteRole(context, role.roleId)
                        deleteTarget = null
                        refresh()
                    }
                ) { Text(stringResource(R.string.role_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CurrentRoleCard(role: RoleFile, onEdit: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            AsyncImage(
                model = role.avatarData,
                contentDescription = null,
                modifier = Modifier.size(60.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
            
            Spacer(Modifier.width(16.dp))
            
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.role_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = role.metadata.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (role.metadata.style.isNotBlank()) {
                    Text(
                        text = role.metadata.style,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (role.isDefault) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.role_default_label)) },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.role_edit))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleRow(
    role: RoleFile,
    currentRoleId: String,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onSelect() },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            AsyncImage(
                model = role.avatarData,
                contentDescription = null,
                modifier = Modifier.size(40.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
            
            Spacer(Modifier.width(12.dp))
            
            Column(Modifier.weight(1f)) {
                Text(role.metadata.name)
                if (role.metadata.style.isNotBlank()) {
                    Text(
                        text = role.metadata.style,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (role.isDefault) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.role_default_label)) }
                )
            } else {
                IconButton(onClick = { onEdit() }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.role_edit))
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.role_delete))
                }
            }
        }
}
}
// ─── 新建 / 编辑角色对话框 ───────────────────────────────────────────────

@Composable
private fun RoleEditorDialog(
    existing: RoleFile?,
    onDismiss: () -> Unit,
    onSave: (name: String, style: String, lang: String, body: String, avatarBytes: ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.metadata?.name ?: "") }
    var style by remember { mutableStateOf(existing?.metadata?.style ?: "") }
    var lang by remember { mutableStateOf((existing?.metadata?.lang ?: "").ifEmpty { "auto" }) }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var avatarBytes by remember { mutableStateOf(existing?.avatarData) }

    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.use { it.readBytes() }
            if (bytes != null) avatarBytes = bytes
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.role_create_title else R.string.role_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 头像预览 + 更换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = avatarBytes,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { avatarPicker.launch("image/*") }) {
                        Text(stringResource(R.string.role_avatar_change))
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.role_name)) },
                    placeholder = { Text(stringResource(R.string.role_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(stringResource(R.string.role_style)) },
                    placeholder = { Text(stringResource(R.string.role_style_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.role_lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "auto" to R.string.role_lang_auto,
                            "zh" to R.string.role_lang_zh,
                            "en" to R.string.role_lang_en,
                        ).forEach { (key, labelRes) ->
                            FilterChip(
                                selected = lang == key,
                                onClick = { lang = key },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.role_body)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, style, lang, body, avatarBytes) }
            ) { Text(stringResource(R.string.role_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}