package ru.anisimov.keenwg.domain

object PeerInputValidator {
    private val safeName = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    fun validate(name: String, ip: String, subnetBase: String, occupiedIps: Set<String>): List<ValidationIssue> = buildList {
        addAll(validateName(name))
        if (!ServerSettingsValidator.isIpv4(ip) || !ip.startsWith(subnetBase)) add(ValidationIssue("ip", "IP вне подсети WireGuard"))
        val host = ip.substringAfterLast('.').toIntOrNull()
        if (host == null || host in setOf(0, 1, 255)) add(ValidationIssue("ip", "Этот IP зарезервирован"))
        if (ip in occupiedIps) add(ValidationIssue("ip", "Этот IP уже используется"))
    }

    fun validateName(name: String): List<ValidationIssue> =
        if (safeName.matches(name)) emptyList() else listOf(ValidationIssue("name", "Имя содержит недопустимые символы"))
}

class PeerInputException(val issues: List<ValidationIssue>) :
    IllegalArgumentException(issues.firstOrNull()?.message ?: "Параметры доступа не прошли проверку")
