package ru.anisimov.keenwg.domain

/** Converts a human label into KeenOS' conservative ASCII peer-name format. */
fun normalizePeerName(value: String): String {
    val transliterated = buildString {
        value.trim().lowercase().forEach { char ->
            append(CYRILLIC_TO_LATIN[char] ?: char)
        }
    }
    return transliterated
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .replace(Regex("[-_]{2,}"), "-")
        .trim('-', '_')
        .take(64)
        .trimEnd('-', '_')
        .ifBlank { "device" }
}

private val CYRILLIC_TO_LATIN = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
    'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
)
