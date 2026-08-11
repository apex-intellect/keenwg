package ru.anisimov.keenwg.data.installer

data class InstallProbe(
    val architecture: String,
    val firmware: String,
    val optFreeBytes: Long,
    val entwarePresent: Boolean,
    val companionConfigPresent: Boolean,
    val xkeenVersion: String?,
    val ascPresent: Boolean,
    val xrayPresent: Boolean,
    val companionVersion: String?,
)

object InstallProbeParser {
    private val keys = setOf(
        "architecture", "firmware", "opt_free_kib", "entware", "companion_config",
        "xkeen", "asc", "xray", "companion",
    )

    fun parse(result: CommandResult): InstallProbe {
        if (result.exitCode != 0 || result.outputTruncated) throw IllegalArgumentException("Probe command failed")
        val values = linkedMapOf<String, String>()
        result.stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
            val separator = line.indexOf('=')
            require(separator in 1 until line.lastIndex) { "Malformed probe output" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            require(key in keys && values.put(key, value) == null) { "Unexpected probe field" }
        }
        require(values.keys == keys) { "Incomplete probe output" }
        val freeKib = values.getValue("opt_free_kib").toLong()
        require(freeKib >= 0 && freeKib <= Long.MAX_VALUE / 1024) { "Invalid free space" }
        return InstallProbe(
            architecture = values.getValue("architecture"),
            firmware = values.getValue("firmware"),
            optFreeBytes = freeKib * 1024,
            entwarePresent = values.present("entware"),
            companionConfigPresent = values.present("companion_config"),
            xkeenVersion = values.optional("xkeen"),
            ascPresent = values.present("asc"),
            xrayPresent = values.present("xray"),
            companionVersion = values.optional("companion")
                ?.removePrefix("keenwg-companion ")
                ?.substringBefore(' ')
                ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) },
        )
    }

    private fun Map<String, String>.present(key: String) = getValue(key) == "present"
    private fun Map<String, String>.optional(key: String) = getValue(key).takeUnless { it == "missing" }
}
