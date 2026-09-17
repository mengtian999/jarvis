package com.jarvis.app.resources

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every format string must actually parse.
 *
 * ## What this exists for
 *
 * `BackupCategory` gained `ROLES`, with `backup_roles_count` written as
 * `%1 / %2`. `%1` is an argument index carrying no conversion character, so
 * `java.util.Formatter` reads the SPACE that follows it as the conversion and
 * throws `UnknownFormatConversionException: Conversion = ' '` out of
 * `Resources.getString`. The Backup tab then crashed on open — on every
 * language — because the Roles row always renders its count.
 *
 * Nothing in the build caught it. aapt2 links `%1 / %2` without complaint, the
 * IDE does not flag it, and nothing had ever pushed a string through
 * `String.format`. This is that seam: one pass per string, on the JVM, no
 * Android host required.
 *
 * Deliberately structural, like [com.jarvis.app.backup.RestoreSourceEntriesTest]
 * next door — it cannot tell you the count is wrong, only that the string will
 * not survive being formatted.
 */
class StringsFormatTest {

    private val resDir = File("src/main/res")

    /** Every `res/values*` directory that carries a strings.xml. */
    private val localeDirs: List<File> by lazy {
        val found = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
        assertTrue(
            "expected res/values* directories under ${resDir.path}",
            found.isNotEmpty(),
        )
        found.filter { File(it, "strings.xml").isFile }.sortedBy { it.name }
    }

    /** Locale directory name -> (`string name` -> raw value). */
    private val entries: Map<String, Map<String, String>> by lazy {
        val rx = Regex(
            """<string name="([a-zA-Z0-9_]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        localeDirs.associateBy(
            keySelector = { dir -> dir.name },
            valueTransform = { dir ->
                rx.findAll(File(dir, "strings.xml").readText())
                    .associate { m -> m.groupValues[1] to m.groupValues[2] }
            },
        )
    }

    /** Conversion characters `java.util.Formatter` accepts. */
    private val conversions = "bBcChHdDeEfFaAgGnNuUsStTxXoOpP%"

    @Test
    fun `it actually read the resource files`() {
        // Guards against the two checks below passing vacuously if `res` is not
        // where a Gradle unit test expects it.
        assertTrue("values/strings.xml should declare strings", entries.getValue("values").isNotEmpty())
        assertTrue(
            "expected every locale to be scanned, got ${entries.size}",
            entries.size >= 17,
        )
    }

    @Test
    fun `a positional argument always carries a conversion character`() {
        // The 09-07 crash, expressed as a rule: `%<digits>` must be
        // `%<digits>$<conversion>`. Otherwise the character right after the
        // digits is parsed AS the conversion — a space, a letter from a word,
        // or an end of string — and `Resources.getString` throws.
        val malformed = mutableListOf<String>()
        for ((locale, strings) in entries) {
            for ((key, value) in strings) {
                var i = 0
                while (i < value.length) {
                    if (value[i] != '%') {
                        i++
                        continue
                    }
                    val next = if (i + 1 < value.length) value[i + 1] else ' '
                    if (!next.isDigit()) {
                        // Non-positional (%d, %s) or a literal %%: both legal.
                        i++
                        continue
                    }
                    var j = i + 1
                    while (j < value.length && value[j].isDigit()) j++
                    val complete =
                        j < value.length && value[j] == '$' &&
                            j + 1 < value.length && conversions.contains(value[j + 1])
                    if (!complete) {
                        malformed += "$locale/$key -> ${value.substring(i)}"
                    }
                    i = j
                }
            }
        }
        assertTrue(
            "malformed format strings — each throws " +
                "UnknownFormatConversionException at Resources.getString:\n$malformed",
            malformed.isEmpty(),
        )
    }

    @Test
    fun `no locale declares a key the default language lacks`() {
        // A key present only in values-zh still links and still generates its
        // R field, so the build is silent; then
        // `Resources.NotFoundException` fires the first time a device on another
        // language composes a Text with it. Same shape of failure as above, no
        // diagnostic anywhere until the device says so.
        val default = entries.getValue("values")
        val orphaned = mutableListOf<String>()
        for ((locale, strings) in entries) {
            if (locale == "values") continue
            for (key in strings.keys) {
                if (key !in default) orphaned += "$locale/$key"
            }
        }
        assertTrue("keys a single locale declares alone:\n$orphaned", orphaned.isEmpty())
    }
}
