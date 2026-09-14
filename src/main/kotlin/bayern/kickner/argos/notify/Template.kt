package bayern.kickner.argos.notify

private val placeholderPattern = Regex("""\{\{(\w+)}}""")

/**
 * Replaces placeholders in the format `{{key}}` within [template] with matching values from [placeholders].
 * Unmatched placeholders are left unchanged. Each placeholder is resolved once in a single pass, so a value
 * that itself contains `{{...}}` (error messages are partly network-controlled) is never expanded again.
 *
 * @param template Source string containing `{{placeholder}}` tokens.
 * @param placeholders Map of placeholder keys to replacement values.
 * @param escapeJson Whether values are escaped as JSON string content (for templates that are JSON documents).
 * @return Formatted string with substituted placeholders.
 */
fun renderTemplate(template: String, placeholders: Map<String, String>, escapeJson: Boolean = false): String =
    placeholderPattern.replace(template) { match ->
        val value = placeholders[match.groupValues[1]] ?: return@replace match.value
        if (escapeJson) jsonEscape(value) else value
    }

/**
 * Escapes [value] so it can be embedded between the quotes of a JSON string literal.
 */
fun jsonEscape(value: String): String = buildString(value.length + 8) {
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
}
