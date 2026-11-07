package com.jarvis.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.app.R
import com.jarvis.app.agent.AgentGroup
import com.jarvis.app.agent.AgentGroupMember
import com.jarvis.app.agent.AgentGroupModelRef
import com.jarvis.app.agent.GroupStore
import com.jarvis.app.agent.RoleStore
import com.jarvis.app.data.repository.ProviderRepository
import kotlinx.coroutines.launch

/**
 * [T-group-chat] 发起群聊 sheet — one surface for the whole group lifecycle:
 *
 *  - LIST mode: existing groups (tap → new chat with that group) + "新建群".
 *  - FORM mode (create or edit): name / instruction / member roster (tap
 *    order = round-robin speaking order) / moderator / unified-model override
 *    (D4: default OFF) / delete (edit only, confirm dialog, D6).
 *
 * Mirrors iOS CreateGroupSheet.swift. Confirm in create mode navigates straight
 * into the new group chat via [onOpenGroupChat].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentGroupSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onOpenGroupChat: (groupId: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups by GroupStore.groupsSnapshot.collectAsState()
    val scope = rememberCoroutineScope()

    // null = list mode; else the group being edited (fresh AgentGroup()
    // with blank id = create mode).
    var editing by remember { mutableStateOf<AgentGroup?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Lambda-typed val (not an inline if-expression): `if (…) null else { … }`
    // otherwise infers Unit? and the lambda branch is parsed as a statement.
    // Set when entering edit mode of an existing group; null in create mode.
    var deleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            val current = editing
            if (current == null) {
                // ── LIST mode ──────────────────────────────────────────────
                Text(
                    stringResource(R.string.agent_group_pick_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .clickable {
                            deleteAction = null
                            editing = AgentGroup(
                                id = "", name = "", instruction = null,
                                members = emptyList(), hostRoleId = null, modelOverride = null,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.agent_group_new),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(groups, key = { it.id }) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Row tap = start a chat with this group.
                                .clickable { onOpenGroupChat(group.id) }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            GroupAvatarStack(group.members.map { it.roleId })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.agent_group_member_count, group.members.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                deleteAction = { showDeleteConfirm = true }
                                editing = group
                            }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.agent_group_settings),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                // ── FORM mode (create when id is blank, edit otherwise) ────
                GroupEditForm(
                    group = current,
                    providerRepository = providerRepository,
                    onCancel = { editing = null },
                    onSave = { name, instruction, members, hostRoleId, modelOverride ->
                        scope.launch {
                            if (current.id.isEmpty()) {
                                val created = GroupStore.createGroup(
                                    name = name,
                                    members = members,
                                    hostRoleId = hostRoleId,
                                    instruction = instruction,
                                    modelOverride = modelOverride,
                                )
                                editing = null
                                onDismiss()
                                onOpenGroupChat(created.id)
                            } else {
                                GroupStore.updateGroup(
                                    current.copy(
                                        name = name,
                                        instruction = instruction,
                                        members = members,
                                        hostRoleId = hostRoleId,
                                        modelOverride = modelOverride,
                                    ),
                                )
                                editing = null
                            }
                        }
                    },
                    onDelete = deleteAction,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.agent_group_delete)) },
            text = { Text(stringResource(R.string.agent_group_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val id = editing?.id ?: return@TextButton
                    scope.launch {
                        GroupStore.deleteGroup(id)
                        editing = null
                    }
                }) { Text(stringResource(R.string.agent_group_delete_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.agent_group_delete_no))
                }
            },
        )
    }
}

/**
 * Create/edit form. Member roster order is tap-to-add order; reorder with the
 * up/down buttons. Participation toggle (D2) lives per member row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditForm(
    group: AgentGroup,
    providerRepository: ProviderRepository,
    onCancel: () -> Unit,
    onSave: (name: String, instruction: String?, members: List<AgentGroupMember>, hostRoleId: String?, modelOverride: AgentGroupModelRef?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val isCreate = group.id.isEmpty()
    var name by remember(group.id) { mutableStateOf(group.name) }
    var instruction by remember(group.id) { mutableStateOf(group.instruction.orEmpty()) }
    var members by remember(group.id) { mutableStateOf(group.members) }
    var hostRoleId by remember(group.id) { mutableStateOf(group.hostRoleId) }
    var modelOverride by remember(group.id) { mutableStateOf(group.modelOverride) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val roles by RoleStore.rolesSnapshot.collectAsState()
    val selectedIds = members.map { it.roleId }.toHashSet()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isCreate) stringResource(R.string.agent_group_create_title)
                else stringResource(R.string.agent_group_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.agent_group_name_label)) },
                    placeholder = { Text(stringResource(R.string.agent_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text(stringResource(R.string.agent_group_instruction_label)) },
                    placeholder = { Text(stringResource(R.string.agent_group_instruction_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 2,
                )
            }
            item {
                Text(
                    stringResource(R.string.agent_group_members_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            if (members.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.agent_group_members_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            // [T-group-chat][P3-ui] Column header over the roster. EXACT
            // alignment by construction: header and member rows share the same
            // structure — weight(1f) left region + four fixed 36dp slots with
            // identical spacing — so "主持" sits dead-center over the ★ column.
            if (members.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.agent_group_member_col),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.agent_group_host_col),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        repeat(3) { Spacer(modifier = Modifier.size(36.dp)) }
                    }
                }
            }
            // Roster rows — array order is the round-robin speaking order.
            items(members.size) { idx ->
                val member = members[idx]
                val role = RoleStore.roleById(member.roleId)
                val roleMissing = role == null
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${idx + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // [T-group-chat] ifEmpty fallback: chips carry one for the
                        // default/blank-name role; the roster must not render a
                        // blank line either.
                        role?.metadata?.name?.ifEmpty { "Jarvis" }
                            ?: stringResource(R.string.agent_group_member_removed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = if (roleMissing) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (roleMissing) {
                        RowControl(
                            icon = Icons.Default.Close,
                            contentDescription = stringResource(R.string.agent_group_cleanup),
                            onClick = { members = members.filterNot { it.roleId == member.roleId } },
                        )
                    } else {
                        // [T-group-chat][P3-ui] 参与 @全体 的开关从行内移除（用户
                        // 反馈多余）——AgentGroupMember.participatesInRound 字段
                        // 保留，建群默认 true，未来需要时再回 UI。
                        // 主持：★ 点亮 = 主持（最后发言），★ 熄灭 = 普通。
                        RowControl(
                            icon = if (hostRoleId == member.roleId) Icons.Default.Star else Icons.Outlined.Star,
                            tint = if (hostRoleId == member.roleId) Color(0xFFFFB300)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            iconSize = 22.dp,
                            contentDescription = stringResource(R.string.agent_group_host_label),
                            onClick = {
                                hostRoleId = if (hostRoleId == member.roleId) null else member.roleId
                            },
                        )
                        RowControl(
                            icon = Icons.Default.ArrowUpward,
                            enabled = idx > 0,
                            onClick = {
                                if (idx > 0) {
                                    val l = members.toMutableList()
                                    l[idx - 1] = l[idx].also { l[idx] = l[idx - 1] }
                                    members = l
                                }
                            },
                        )
                        RowControl(
                            icon = Icons.Default.ArrowDownward,
                            enabled = idx < members.lastIndex,
                            onClick = {
                                if (idx < members.lastIndex) {
                                    val l = members.toMutableList()
                                    l[idx + 1] = l[idx].also { l[idx] = l[idx + 1] }
                                    members = l
                                }
                            },
                        )
                        RowControl(
                            icon = Icons.Default.Close,
                            onClick = {
                                members = members.filterNot { it.roleId == member.roleId }
                                if (hostRoleId == member.roleId) hostRoleId = null
                            },
                        )
                    }
                }
            }
            if (members.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.agent_group_order_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            item {
                // Add-member chips: tap a role chip to append it to the roster.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    roles
                        .filter { it.roleId !in selectedIds }
                        .forEach { role ->
                            val label = role.metadata.name.ifEmpty { if (role.isDefault) "Jarvis" else "Role" }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                    .clickable {
                                        members = members + AgentGroupMember(role.roleId)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                }
            }
            item {
                // [T-group-chat-model-override] Unified model — default OFF.
                // Resolution chain at speak time: this > role.model > default.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.agent_group_model_override),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            if (modelOverride == null) stringResource(R.string.agent_group_model_override_off)
                            else modelOverride!!.modelId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = modelOverride != null,
                        onCheckedChange = { on ->
                            modelOverride = if (on) {
                                providerRepository.allVisibleEntries().firstOrNull()?.let {
                                    AgentGroupModelRef(it.baseModel.id, it.providerInstanceId)
                                }
                            } else null
                        },
                    )
                }
                if (modelOverride != null) {
                    ExposedDropdownMenuBox(
                        expanded = modelMenuOpen,
                        onExpandedChange = { modelMenuOpen = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        val entries = remember { providerRepository.allVisibleEntries() }
                        val currentEntry = entries.firstOrNull {
                            it.providerInstanceId == modelOverride?.providerInstanceId &&
                                it.baseModel.id == modelOverride?.modelId
                        }
                        OutlinedTextField(
                            value = currentEntry?.baseModel?.displayName ?: modelOverride!!.modelId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.agent_group_model_pick)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = modelMenuOpen,
                            onDismissRequest = { modelMenuOpen = false },
                        ) {
                            entries.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.baseModel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        modelOverride = AgentGroupModelRef(entry.baseModel.id, entry.providerInstanceId)
                                        modelMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!isCreate && onDelete != null) {
                item {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_group_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && members.isNotEmpty()) {
                        onSave(
                            name,
                            instruction.trim().ifEmpty { null },
                            members,
                            hostRoleId,
                            modelOverride,
                        )
                    }
                },
                enabled = name.isNotBlank() && members.isNotEmpty(),
            ) {
                Text(stringResource(if (isCreate) R.string.agent_group_create else R.string.agent_group_save))
            }
        }
    }
}

/**
 * [T-group-chat][P3-ui] Fixed-size row control (36dp box, 18dp glyph). The
 * roster header places "主持" over the first of these — alignment holds
 * because header and rows use the SAME slot width and spacing, with no
 * theme-dependent IconButton minimums in the way.
 */
@Composable
private fun RowControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Overlapping avatar stack for a roster of roleIds (session list / sheet rows). */
@Composable
fun GroupAvatarStack(roleIds: List<String>, avatarSize: Int = 20) {
    val shown = roleIds.take(3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, roleId ->
            val bmp = remember(roleId) { RoleStore.cachedAvatarBitmap(roleId) }
            val name = RoleStore.roleById(roleId)?.metadata?.name.orEmpty()
            Box(
                modifier = Modifier
                    .offset(x = (-6 * i).dp)
                    .size(avatarSize.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(avatarSize.dp).clip(CircleShape),
                    )
                } else {
                    Text(
                        name.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (roleIds.size > 3) {
            Text(
                "+${roleIds.size - 3}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = (-6 * shown.size).dp).padding(start = 2.dp),
            )
        }
    }
}
