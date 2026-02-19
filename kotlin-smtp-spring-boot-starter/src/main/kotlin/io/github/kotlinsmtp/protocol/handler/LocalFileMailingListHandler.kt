package io.github.kotlinsmtp.protocol.handler

import java.nio.file.Files
import java.nio.file.Path

/**
 * Local file-based EXPN implementation.
 *
 * Directory structure:
 * - listsDir/<list>.txt
 *
 * File format:
 * - Ignore empty/blank lines.
 * - Ignore lines starting with '#'.
 * - Return each line as-is as a member entry (email address/description string).
 *
 * TODO(DB/S3/MSA):
 * - Migrate mailing list/membership storage to DB or directory service.
 * - Introduce an ACL policy engine for EXPN authorization.
 */
class LocalFileMailingListHandler(
    private val listsDir: Path,
    private val maxMembers: Int = 200,
) : SmtpMailingListHandler {

    override fun expand(listName: String): List<String> {
        val key = normalizeListKey(listName)
        if (key.isEmpty()) return emptyList()

        val file = listsDir.resolve("$key.txt")
        if (!Files.exists(file)) return emptyList()

        return runCatching {
            Files.newBufferedReader(file).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(maxMembers)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeListKey(input: String): String {
        // If input is "list@domain", use only local-part (feature-first; per-domain split is TODO).
        val raw = input.trim().substringBeforeLast('@')
        // Make filename-safe token.
        return raw.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(128)
    }
}

