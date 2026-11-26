package com.jarvis.app.ui.onboarding

import android.content.Context
import android.util.Log

/**
 * [T-android-first-run-intro] First-run experience.
 *
 * The gateway registers the device and seeds a default model group on every
 * launch (GatewaySync), which made the old 3-step "add a provider → select
 * models → start" landing obsolete: steps 1 and 2 were already "Done" the
 * moment the user first saw them. A fresh install now lands directly in a
 * draft chat with the default role, and the role opens the conversation
 * itself — a short self-introduction produced by a normal agent loop (see
 * ChatViewModel.startIntro).
 *
 * Dispatch-once: [isIntroDone] is persisted the moment the greeting loop is
 * launched, so upgrades, "user deleted every session" and process restarts
 * never re-trigger it. Only the launch resolver in AppNavigation hands out a
 * target (plus the process-death restore path), and ChatScreen drains it on
 * first composition — the same one-shot discipline as
 * ChatViewModelStore.PendingForwardRespond: addressed to one draft id,
 * TTL-bound, consumed exactly once.
 */
object FirstRunIntro {
    private const val TAG = "FirstRunIntro"
    private const val PREFS_NAME = "first_run_intro"
    private const val KEY_INTRO_SENT = "intro_sent"
    private const val STASH_TTL_MS = 300_000L

    /**
     * How long the first chat waits for a usable model before it falls back
     * to the offline welcome + Retry strip. Long enough for a normal gateway
     * registration + /v1/models round trip (typically 1–3s), short enough
     * that an offline device is not left staring at a spinner.
     */
    const val READY_TIMEOUT_MS = 8_000L

    /**
     * After the wait above times out, keep watching a little longer: a slow
     * gateway that lands seconds late still gets its greeting instead of a
     * stale "couldn't connect" card. Nothing happens if the user has already
     * started typing.
     */
    const val LATE_GRACE_MS = 45_000L

    @Volatile
    private var pendingTargetId: String? = null

    @Volatile
    private var stashedAtMs: Long = 0L

    /** True once this install has already dispatched (or declined) the greeting. */
    fun isIntroDone(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INTRO_SENT, false)
    } catch (_: Exception) {
        // Unreadable prefs → treat as done rather than greeting on every launch.
        true
    }

    fun markIntroDone(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_INTRO_SENT, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist intro flag: ${e.message}")
        }
    }

    /** Address the first-run greeting to the draft the resolver is about to open. */
    fun stash(targetId: String) {
        pendingTargetId = targetId
        stashedAtMs = System.currentTimeMillis()
        Log.d(TAG, "stash target=$targetId")
    }

    /** Drain the request exactly once, and only for the draft it was addressed to.
     *  Both sides are normalized (a leading "chat/" route prefix stripped) so a
     *  caller may pass either the bare session id or the route string. */
    fun consume(sessionId: String): Boolean {
        val target = pendingTargetId ?: return false
        if (System.currentTimeMillis() - stashedAtMs > STASH_TTL_MS) {
            pendingTargetId = null
            Log.d(TAG, "consume: dropping stale marker (target=$target)")
            return false
        }
        if (target.removePrefix("chat/") != sessionId.removePrefix("chat/")) return false
        pendingTargetId = null
        Log.d(TAG, "consume target=$sessionId")
        return true
    }
}
