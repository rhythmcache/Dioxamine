package io.github.rhythmcache.dioxamine.scrcpy

data class ScrcpyApp(
    val name: String,
    val packageName: String,
    val isSystem: Boolean
)

object ScrcpyAppParser {
    private val singleLineRegex = Regex("""^\s*([*-])\s+(.+?)\s{2,}([a-zA-Z0-9_.]+)$""")
    private val multiLineHeaderRegex = Regex("""^\s*([*-])\s+(.+)$""")
    private val multiLinePkgRegex = Regex("""^\s{4,}([a-zA-Z0-9_.]+)$""")
    val validPkgRegex = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)*$""")

    fun parse(stdout: String): List<ScrcpyApp> {
        val list = mutableListOf<ScrcpyApp>()
        var pendingName: String? = null
        var pendingIsSystem = false

        for (rawLine in stdout.lineSequence()) {
            val line = rawLine.trimEnd('\r', ' ')
            if (line.isEmpty()) continue

            val singleMatch = singleLineRegex.matchEntire(line)
            if (singleMatch != null) {
                val pkg = singleMatch.groupValues[3].trim()
                if (validPkgRegex.matches(pkg)) {
                    list.add(
                        ScrcpyApp(
                            name = singleMatch.groupValues[2].trim(),
                            packageName = pkg,
                            isSystem = singleMatch.groupValues[1] == "*"
                        )
                    )
                }
                pendingName = null
                continue
            }

            val headerMatch = multiLineHeaderRegex.matchEntire(line)
            if (headerMatch != null) {
                val prefix = headerMatch.groupValues[1]
                val content = headerMatch.groupValues[2].trim()
                if (!content.startsWith("Device:") && !content.startsWith("List of apps:") && !content.startsWith("Processing")) {
                    pendingName = content
                    pendingIsSystem = (prefix == "*")
                }
                continue
            }

            if (pendingName != null) {
                val pkgMatch = multiLinePkgRegex.matchEntire(line)
                if (pkgMatch != null) {
                    val pkg = pkgMatch.groupValues[1].trim()
                    if (validPkgRegex.matches(pkg)) {
                        list.add(
                            ScrcpyApp(
                                name = pendingName,
                                packageName = pkg,
                                isSystem = pendingIsSystem
                            )
                        )
                    }
                }
                pendingName = null
            }
        }

        return list.sortedWith(
            compareBy<ScrcpyApp> { it.isSystem }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }
}
