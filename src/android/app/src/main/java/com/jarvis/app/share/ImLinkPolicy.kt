package com.jarvis.app.share

/**
 * Classifies share text / intent data as an IM deep link (matrix user ID,
 * room alias/ID, bitjarvis.chat URL, matrix.to URL, or custom scheme) so the
 * host can route to the IM module instead of the normal attachment flow.
 *
 * Kept as a pure, stateless utility object so it is trivially unit-testable
 * without instrumenting an Activity.
 */
object ImLinkPolicy {

    fun isImLink(text: String?): Boolean {
        val s = text?.trim() ?: return false
        return s.startsWith("https://bitjarvis.chat/") ||
            s.startsWith("http://bitjarvis.chat/") ||
            s.startsWith("https://www.bitjarvis.chat/") ||
            s.startsWith("http://www.bitjarvis.chat/") ||
            s.startsWith("https://matrix.to/") ||
            s.startsWith("matrix:") ||
            s.startsWith("im.bitjarvis://") ||
            (s.length > 1 && "@#!+\$".indexOf(s[0]) >= 0 && s.contains(":"))
    }
}
