package com.jarvis.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.app.R
import com.jarvis.app.agent.RoleFile
import com.jarvis.app.agent.RoleStore

/**
 * [T-role-picker] Bottom sheet listing ALL roles for the in-chat jump-to-role /
 * new-chat-with-role flows. Replaces the current-role-only entry dialog. Picking
 * a role opens a NEW session bound to it (immutable in-session) or filters the
 * list to that role history. Mirrors Android RolePickerSheet.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePickerSheet(
    currentRoleId: String,
    onNewChatWithRole: (roleId: String) -> Unit,
    onViewRoleHistory: (roleId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val roles by RoleStore.rolesSnapshot.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // [T-role-picker-scroll] 这里必须先给 Column 一个有界高度，才能让
        // 下面的 LazyColumn 建立滚动视口。ModalBottomSheet 不给内容纵向
        // 约束：LazyColumn 只有 fillMaxWidth 时会把行一直排到弹窗可见区
        // 填满就停，结果是「只能看到约 6 个角色且滑不动」。写法对齐
        // BrowserHistorySheet.kt 的 .fillMaxHeight(0.8f)。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.role_entry_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(roles) { index, role ->
                    RolePickerRow(
                        role = role,
                        isCurrent = role.roleId == currentRoleId,
                        onNewChat = { onNewChatWithRole(role.roleId) },
                        onHistory = { onViewRoleHistory(role.roleId) },
                    )
                    if (index < roles.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/**
 * [T-role-picker-layout] Avatar on the left; to its right a Column with the
 * role name (top, left-aligned, ellipsized) followed by TWO action rows
 * (new chat / view history) stacked beneath. Vertical spacing inside the
 * column is tuned to the avatar size so long names do not stretch the row.
 */
@Composable
private fun RolePickerRow(
    role: RoleFile,
    isCurrent: Boolean,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
) {
    val bmp = remember(role.roleId) { RoleStore.cachedAvatarBitmap(role.roleId) }
    val name = role.metadata.name.ifEmpty { if (role.isDefault) "Jarvis" else "Role" }
    val avatarSize = 40.dp
    val gap = avatarSize / 4
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(avatarSize).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(avatarSize).clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.firstOrNull()?.toString() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Text(
                        stringResource(R.string.role_picker_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNewChat)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.role_entry_new_session),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onHistory)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.role_entry_view_history),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
