// +--------------------------------------------------------------+
// | RoleStore.kt - 角色集合管理器 (Multi-Role System)           |
// +--------------------------------------------------------------+

package com.jarvis.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.jarvis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

// MARK: - Role Collection 管理

/**
 * 角色集合管理器
 * 管理所有角色：默认角色(禁止删除) + 用户自定义角色(可编辑/可删除)
 */
object RoleStore {
    
    private const val TAG = "RoleStore"
    private const val DEFAULT_ROLE_ID = "default"
    
    // 默认人格内核 - Jarvis。必须声明在 _currentRole（loadDefault）之前，
    // 否则对象初始化顺序会让 loadDefault() 读到未初始化的 null。
    val defaultPersonalityBody = """
            Don't perform — help. Skip the "Sure!" and "Happy to assist!" — just do the work.

            Have a stance. It's fine to disagree, prefer one thing over another, find some things interesting and others dull.

            Act first, ask second. If you can look it up, look it up. Come back with answers, not questions.
        """.trimIndent()
    const val CHINESE_CHAR_LIMIT = 1600
    const val ENGLISH_WORD_LIMIT = 1000
    const val CJK_RATIO_THRESHOLD = 0.3

    // [T-role-resolve] Cached default-role instance. loadDefault() builds a
    // fresh avatar bitmap per call — fine once at startup, NOT fine now that
    // render-time resolvers (message headers, list rows) call it per
    // recomposition. Every caller treats the result as read-only (edits go
    // through saveRole with a new RoleFile), so sharing one instance is safe.
    // Declared BEFORE _currentRole: that initializer calls loadDefault(),
    // which writes this cache — a declaration further down would have its
    // own `= null` initializer run afterwards and wipe it (the same
    // init-order trap as defaultPersonalityBody below).
    @Volatile
    private var cachedDefaultRole: RoleFile? = null
    
    /**
     * App 级 Context，由 [prime] 注入（MinisApp.onCreate）。项目中没有全局
     * Context，模块内统一走这里，与 FastModePrefs 的模式一致。
     */
    @Volatile
    private var appContext: Context? = null
    
    private fun requireAppContext(): Context =
        appContext ?: throw IllegalStateException("RoleStore.prime() 尚未调用")
    
    /**
     * 应用启动时初始化角色系统：
     * 1. 注入 applicationContext
     * 2. 确保默认角色(Jarvis)存在
     * 3. 从 current.json 恢复当前角色
     * 4. 预热 [currentRoleState]（Compose 界面依赖它显示当前角色名）
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        ensureDefaultRole(context)
        seedDefaultAvatarIfNeeded(context)
        // Invalidate the clinit-built default cache so a later loadDefault()
        // reads the disk-backed (asset-seeded) avatar instead of the stale
        // programmatic one built before appContext was injected.
        cachedDefaultRole = null
        currentRoleId = readPersistedCurrentId(context)
        _currentRole.value = currentRole()
        // [T-role-snapshot] Warm the collection snapshot so the first
        // session-list render (session avatars) resolves roles without
        // per-row file IO. Synchronous on purpose: prime runs once at
        // startup BEFORE any UI composes, and the first session load
        // (ChatViewModel.loadSession → resolveSessionRole) must not race
        // an async refresh into reading an empty snapshot.
        _rolesSnapshot.value = allRoles(context)
    }
    
    /** 确保默认角色(Jarvis)的 role.json 存在；幂等，永不覆盖已有内容。 */
    fun ensureDefaultRole(context: Context) {
        val roleDir = rolesDir(context).appendingPathComponent(DEFAULT_ROLE_ID)
        val roleFile = File(roleDir, "role.json")
        if (roleFile.exists()) return
        try {
            roleDir.mkdirs()
            roleFile.writeText(buildJson(loadDefault()))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "创建默认角色失败: ${e.message}")
        }
    }
    
    /**
     * Seed the bundled Jarvis avatar (assets/roles/default-avatar.png) into
     * the default role directory as avatar.png, once and idempotently. Skips
     * when a custom avatar.png already exists on disk (saveRole writes one
     * when the user picks an image), so it never clobbers customizations.
     * Covers both fresh installs and existing users who pre-date the asset.
     */
    private fun seedDefaultAvatarIfNeeded(context: Context) {
        val roleDir = rolesDir(context).appendingPathComponent(DEFAULT_ROLE_ID)
        val avatarFile = File(roleDir, "avatar.png")
        if (avatarFile.exists() && avatarFile.length() > 0) return
        try {
            roleDir.mkdirs()
            context.assets.open("roles/default-avatar.png").use { input ->
                java.io.FileOutputStream(avatarFile).use { out -> input.copyTo(out) }
            }
            AppLogger.info(TAG, "Seeded bundled default avatar to roles/$DEFAULT_ROLE_ID/avatar.png")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Failed to seed default avatar: ${e.message}")
        }
    }

    private fun readPersistedCurrentId(context: Context): String =
        try {
            val currentFile = File(rolesDir(context), "current.json")
            if (currentFile.exists()) {
                val json = currentFile.readText()
                val match = """"roleId"\s*:\s*"([^"]+)"""".toRegex().find(json)
                match?.groupValues?.get(1) ?: DEFAULT_ROLE_ID
            } else {
                DEFAULT_ROLE_ID
            }
        } catch (e: Exception) {
            DEFAULT_ROLE_ID
        }
    
    /**
     * 当前激活角色的响应式状态。角色切换 / 保存 / 删除后调用
     * [refreshCurrentRole] 更新，Compsoe 界面经 collectAsState 实时刷新。
     * 初始值为默认角色(Jarvis)，任何时刻都不会为空。
     */
    private val _currentRole = MutableStateFlow<RoleFile>(loadDefault())
    val currentRoleState: StateFlow<RoleFile> = _currentRole.asStateFlow()

    // [T-role-snapshot] In-memory snapshot of the full role collection. Backs
    // list rendering (session-list avatars, role pickers, forward-to-role
    // target lists) so a LazyColumn row never does per-row file IO. Refreshed
    // on save/delete (collection mutations); a role switch alone does not
    // change the set, so it skips the refresh.
    private val _rolesSnapshot = MutableStateFlow<List<RoleFile>>(emptyList())
    val rolesSnapshot: StateFlow<List<RoleFile>> = _rolesSnapshot.asStateFlow()

    /**
     * 按 roleId 查角色（基于内存快照，无文件 IO）。查不到返回 null，
     * 调用方回退 [loadDefault] —— 渲染期孤儿引用（角色已删）回退默认角色，
     * 与 folderId 策略一致。
     */
    fun roleById(roleId: String): RoleFile? =
        _rolesSnapshot.value.find { it.roleId == roleId }

    /**
     * [T-role-resolve] 渲染期统一解析器：快照查找 → 回退默认角色。
     * 供聊天顶栏 / 消息头部 / 列表头像等 UI 渲染点使用（无磁盘 IO，
     * 可在 Compose 渲染路径安全调用）。会话加载期的一次性解析走
     * ChatViewModel.resolveSessionRole（额外带磁盘兜底，防快照异步刷新竞态）。
     */
    fun resolveRole(roleId: String?): RoleFile =
        roleId?.let { roleById(it) } ?: loadDefault()

    // [T-role-snapshot-io] Snapshot refreshes off the main thread: saveRole/
    // deleteRole can be called from the UI layer, and allRoles() walks the
    // roles/ directory doing file IO per role. Mutex serializes refreshes so
    // an older read can never overwrite a newer one (launch order == lock
    // acquisition order).
    private val snapshotMutex = Mutex()
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 刷新角色集合快照。save/delete 后调用，让列表渲染拿到最新角色（含头像）。 */
    fun refreshRolesSnapshot() {
        val ctx = appContext ?: return
        snapshotScope.launch {
            snapshotMutex.withLock {
                _rolesSnapshot.value = allRoles(ctx)
            }
        }
    }

    /**
     * [T-backup-roles-category] 备份恢复把 role.json / avatar.png /
     * current.json 原样写回 roles/ 之后调用。[refreshRolesSnapshot] 只刷新
     * 集合快照——恢复的包里还带 current.json（当前激活角色指针），它和
     * [currentRole] 的 StateFlow 同样是启动时读入内存的，不在这里重读的话
     * 恢复后 UI 会一直停在重启前的角色状态。
     */
    fun refreshAfterRestore() {
        val ctx = appContext ?: return
        currentRoleId = readPersistedCurrentId(ctx)
        _currentRole.value = currentRole()
        refreshRolesSnapshot()
    }
    
    /**
     * [T-role-avatar-cache] Decoded avatar bitmaps for list-row rendering.
     * Session-list rows decode roleId → avatar ByteArray → Bitmap on every
     * recomposition; without a cache a 50-row list re-decodes 50 PNGs per
     * scroll frame. Avatars are stored pre-cropped to 64x64 (~16 KB ARGB),
     * so entry-count is the right size metric — 24 entries ≈ 400 KB.
     * Keyed by roleId; invalidated on save/delete (same id may carry new
     * avatar bytes after an edit). The default role (no avatarData → null)
     * never enters the cache.
     */
    private val avatarBitmapCache = object : android.util.LruCache<String, Bitmap>(24) {}

    /**
     * [T-role-avatar-cache] Row-render avatar: roleId → Bitmap, or null when
     * the role has no avatar image (caller draws the ✨ fallback). Uses
     * [rolesSnapshot] so role deletions resolve to the default avatar on the
     * next snapshot emission, and the LruCache so list scrolling stays cheap.
     */
    fun cachedAvatarBitmap(roleId: String?): Bitmap? {
        val role = resolveRole(roleId)
        val data = role.avatarData ?: return null
        avatarBitmapCache.get(role.roleId)?.let { return it }
        return try {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            avatarBitmapCache.put(role.roleId, bmp)
            bmp
        } catch (e: Exception) {
            AppLogger.warning(TAG, "解码角色头像失败: ${e.message}")
            null
        }
    }

    /** [T-role-avatar-cache] Drop one role's decoded avatar (edit/delete) or all. */
    fun invalidateAvatarCache(roleId: String? = null) {
        if (roleId == null) avatarBitmapCache.evictAll() else avatarBitmapCache.remove(roleId)
    }

    /** 角色集合变化后调用，让 [currentRoleState] 反映最新当前角色。 */
    fun refreshCurrentRole() {
        _currentRole.value = currentRole()
    }
    
    /** 获取角色目录路径 */
    private fun rolesDir(context: Context): File =
        File(File(context.filesDir, "jarvis-global/memory"), "roles")
    
    /** 获取当前激活的角色ID */
    var currentRoleId: String = DEFAULT_ROLE_ID
        private set
    
    /** 获取当前激活的角色名称 */
    val currentRoleName: String
        get() = currentRole().metadata.name
    
    /** 获取所有角色列表（含默认角色 Jarvis） */
    fun allRoles(context: Context): List<RoleFile> {
        val roles = mutableListOf<RoleFile>()
        val dir = rolesDir(context)
        
        if (!dir.exists()) return roles
        
        // 遍历 roles/<roleId>/role.json，包含默认角色
        dir.listFiles()?.forEach { roleDir ->
            if (roleDir.isDirectory) {
                loadRole(context, roleDir.name)?.let { roles.add(it) }
            }
        }
        
        return roles.sortedByDescending { it.metadata.name.lowercase() }
    }
    
    /** 获取当前激活的角色 */
    fun currentRole(): RoleFile {
        val roleId = try {
            val currentFile = File(rolesDir(requireAppContext()), "current.json")
            if (currentFile.exists()) {
                val json = currentFile.readText()
                val match = """"roleId"\s*:\s*"([^"]+)"""".toRegex().find(json)
                match?.groupValues?.get(1) ?: DEFAULT_ROLE_ID
            } else {
                DEFAULT_ROLE_ID
            }
        } catch (e: Exception) {
            DEFAULT_ROLE_ID
        }
        
        return loadRole(requireAppContext(), roleId) ?: loadDefault()
    }
    
    /** 加载指定角色 */
    fun loadRole(context: Context, roleId: String): RoleFile? {
        val roleDir = rolesDir(context).appendingPathComponent(roleId)
        val roleFile = File(roleDir, "role.json")
        val avatarFile = File(roleDir, "avatar.png")
        
        if (!roleFile.exists()) return null
        
        return try {
            val json = roleFile.readText()
            val avatarData = if (avatarFile.exists()) avatarFile.readBytes() else null
            
            RoleFile(
                metadata = RoleMetadata.fromJson(json),
                body = parseBodyFromJson(json),
                avatarData = avatarData,
                roleId = roleId,
                isDefault = roleId == DEFAULT_ROLE_ID
            )
        } catch (e: Exception) {
            AppLogger.warning(TAG, "加载角色失败: ${e.message}")
            null
        }
    }
    
    /** 保存角色 */
    fun saveRole(context: Context, role: RoleFile) {
        val roleDir = rolesDir(context).appendingPathComponent(role.roleId)
        roleDir.mkdirs()
        
        // 保存 role.json
        val json = buildJson(role)
        val roleFile = File(roleDir, "role.json")
        roleFile.writeText(json)
        
        // 保存头像
        role.avatarData?.let { avatarData ->
            val avatarFile = File(roleDir, "avatar.png")
            avatarFile.writeBytes(compressAvatar(avatarData))
        }
        
        // 更新当前角色ID (如果是默认角色或第一个保存的)
        if (role.roleId == DEFAULT_ROLE_ID) {
            currentRoleId = DEFAULT_ROLE_ID
            saveCurrentId(context, DEFAULT_ROLE_ID)
        }
        refreshCurrentRole()
        // [T-role-snapshot] save may add or rewrite a role file → refresh the
        // collection so list avatars/pickers reflect it immediately.
        // [T-role-avatar-cache] A rewrite can carry new avatar bytes under the
        // same roleId → drop the stale decoded bitmap.
        invalidateAvatarCache(role.roleId)
        refreshRolesSnapshot()
    }

    /** 创建新角色 */
    fun createRole(context: Context, name: String, style: String = "", 
                   lang: String = "auto", body: String = "",
                   avatarData: ByteArray? = null): RoleFile {
        var roleId = slugify(name) ?: UUID.randomUUID().toString()
        var counter = 1
        
        // 确保ID唯一
        while (loadRole(context, roleId) != null) {
            roleId = "${roleId}_$counter"
            counter++
        }
        
        val role = RoleFile(
            metadata = RoleMetadata(
                name = name,
                style = style,
                lang = lang
            ),
            body = body,
            avatarData = (avatarData ?: generateDefaultAvatar()).let { compressAvatar(it) },
            roleId = roleId,
            isDefault = false
        )
        
        saveRole(context, role)
        return role
    }
    
    /** 删除角色(不可删除默认角色) */
    fun deleteRole(context: Context, roleId: String): Boolean {
        if (roleId == DEFAULT_ROLE_ID) return false
        
        val roleDir = rolesDir(context).appendingPathComponent(roleId)
        if (!roleDir.exists()) return false
        
        val success = try {
            roleDir.deleteRecursively()
            true
        } catch (e: Exception) {
            false
        }
        if (success && currentRoleId == roleId) {
            // 删除的是当前角色 → 回退到默认角色
            currentRoleId = DEFAULT_ROLE_ID
            saveCurrentId(context, DEFAULT_ROLE_ID)
        }
        refreshCurrentRole()
        // [T-role-snapshot] delete shrinks the collection → refresh so list
        // rows referencing the deleted role fall back to default via roleById.
        // [T-role-avatar-cache] Free the deleted role's decoded avatar.
        invalidateAvatarCache(roleId)
        refreshRolesSnapshot()
        return success
    }

    /** 设置当前激活的角色 */
    fun setCurrentRole(context: Context, roleId: String): Boolean {
        if (loadRole(context, roleId) == null && roleId != DEFAULT_ROLE_ID) {
            return false
        }
        currentRoleId = roleId
        saveCurrentId(context, roleId)
        refreshCurrentRole()
        return true
    }
    
    /** 切换到下一个角色(循环) */
    fun switchToNextRole(context: Context) {
        val allRoles = allRoles(context).toMutableList()
        if (allRoles.isEmpty()) return
        
        val currentIndex = allRoles.indexOfFirst { it.roleId == currentRoleId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % allRoles.size
        currentRoleId = allRoles[nextIndex].roleId
        saveCurrentId(context, currentRoleId)
        refreshCurrentRole()
    }
    
    /** 重置为默认角色 */
    fun resetToDefault(context: Context) {
        currentRoleId = DEFAULT_ROLE_ID
        saveCurrentId(context, DEFAULT_ROLE_ID)
        refreshCurrentRole()
    }
    
    /** 加载默认角色(Jarvis) */
    fun loadDefault(): RoleFile {
        cachedDefaultRole?.let { return it }
        // After prime(), appContext is set ? prefer the disk-backed default
        // role so it carries the bundled avatar seeded by
        // seedDefaultAvatarIfNeeded(). At clinit (before prime) appContext is
        // null, so fall through to the programmatic avatar below (needs no
        // Context and is crash-safe per the P0 guard).
        appContext?.let { ctx ->
            loadRole(ctx, DEFAULT_ROLE_ID)?.let { return it }
        }
        val defaultRole = RoleFile(
            metadata = RoleMetadata(
                name = "Jarvis",
                style = "",
                lang = "auto"
            ),
            body = defaultPersonalityBody,
            // [P0-startup-crash-guard] generateDefaultAvatar() runs during
            // object <clinit> (via _currentRole init at line 102, on the main
            // thread before MinisApp.onCreate). An OOM (OutOfMemoryError, an
            // Error, NOT caught by `catch (e: Exception)`) or any other bitmap
            // failure here would raise ExceptionInInitializerError and crash
            // the app at startup — the same failure class as the historical
            // body-null NPE. Catch Throwable so a missing default avatar
            // degrades to null (avatarData is already nullable) instead of
            // killing the launch.
            avatarData = try { generateDefaultAvatar() } catch (e: Throwable) { null },
            roleId = DEFAULT_ROLE_ID,
            isDefault = true
        )
        cachedDefaultRole = defaultRole
        return defaultRole
    }
    
    // MARK: - Private Helpers
    
    private fun slugify(text: String): String? {
        val slug = text.lowercase()
            .replace(" ".toRegex(), "-")
            .replace(Regex("[^a-z0-9-]+"), "")
            .trim('-')
        return if (slug.isEmpty()) null else slug
    }
    
    private fun compressAvatar(data: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            // 先居中裁剪为正方形，再缩放到 64x64，避免变形
            val cropSize = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - cropSize) / 2
            val y = (bitmap.height - cropSize) / 2
            val cropped = Bitmap.createBitmap(bitmap, x, y, cropSize, cropSize)
            val resized = Bitmap.createScaledBitmap(cropped, 64, 64, true)
            val output = java.io.ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        } catch (e: Exception) {
            data
        }
    }
    
    private fun generateDefaultAvatar(): ByteArray {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // 绘制圆形背景
        paint.color = Color.parseColor("#4F46E5") // 蓝色
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        
        // 绘制✨
        paint.color = Color.WHITE
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("✨", size / 2f, size / 2f + 8f, paint)
        
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }
    
    private fun saveCurrentId(context: Context, roleId: String) {
        try {
            val currentFile = File(rolesDir(context), "current.json")
            currentFile.writeText("{\"roleId\":\"$roleId\"}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "保存当前角色ID失败: ${e.message}")
        }
    }
    
    /**
     * 从 role.json 提取 "body" 字段（角色"系统提示" / SOUL.md 正文）。
     * 优先走 org.json.JSONObject 正规解码；写入端 buildJson 会把换行/引号/反斜杠
     * 做 JSON 转义，读取端必须对称反转义（\n → 真实换行、\" → "、\\ → \），
     * 否则正文字面 \n 会在编辑界面显示出来。解析失败时回退旧正则提取。
     */
    private fun parseBodyFromJson(json: String): String {
        return try {
            JSONObject(json).optString("body", "")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "parseBodyFromJson via JSONObject 失败: ${e.message}")
            val match = """"body"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(json)
            match?.groupValues?.get(1) ?: ""
        }
    }
    
    /**
     * 将角色序列化为 role.json 的 JSON 文本。使用 org.json.JSONObject 做正规编解码，
     * 与 parseBodyFromJson 严格对称：真实换行会被正确转义并可在读回时还原。
     * toString(2) 带缩进便于人工阅读与跨端调试。异常时回退旧的手写转义逻辑。
     */
    private fun buildJson(role: RoleFile): String {
        return try {
            JSONObject()
                .put("name", role.metadata.name)
                .put("style", role.metadata.style)
                .put("lang", role.metadata.lang)
                .put("body", role.body)
                .toString(2)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "buildJson via JSONObject 失败: ${e.message}")
            val bodyEscaped = role.body
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            return """
            {
                "name": "${role.metadata.name}",
                "style": "${role.metadata.style}",
                "lang": "${role.metadata.lang}",
                "body": "$bodyEscaped"
            }
            """.trimIndent()
        }
    }
}

// MARK: - 数据模型

data class RoleMetadata(
    val name: String,
    val style: String = "",
    val lang: String = "auto"
) {
    companion object {
        /**
         * 从 role.json 的 JSON 文本解析元数据。走 org.json.JSONObject 正规解码，
         * 正确处理转义（字段值里含 " / \ 时也可精确还原）；解析失败时回退到
         * 旧正则逻辑，兼容非标准 JSON。
         */
        fun fromJson(json: String): RoleMetadata {
            return try {
                val obj = JSONObject(json)
                RoleMetadata(
                    name = obj.optString("name", "Jarvis").ifEmpty { "Jarvis" },
                    style = obj.optString("style", ""),
                    lang = obj.optString("lang", "auto").ifEmpty { "auto" }
                )
            } catch (e: Exception) {
                val nameMatch = """"name"\s*:\s*"([^"]+)"""".toRegex().find(json)
                val styleMatch = """"style"\s*:\s*"([^"]*)"""".toRegex().find(json)
                val langMatch = """"lang"\s*:\s*"([^"]+)"""".toRegex().find(json)
                RoleMetadata(
                    name = nameMatch?.groupValues?.get(1) ?: "Jarvis",
                    style = styleMatch?.groupValues?.get(1) ?: "",
                    lang = langMatch?.groupValues?.get(1) ?: "auto"
                )
            }
        }
    }
}

data class RoleFile(
    val metadata: RoleMetadata,
    var body: String,
    var avatarData: ByteArray?,
    val roleId: String,
    val isDefault: Boolean = false
)

// MARK: - 工具函数

fun File.appendingPathComponent(component: String): File {
    return if (this.path.endsWith(File.separator)) {
        File(this, component)
    } else {
        File(this.path + File.separator + component)
    }
}