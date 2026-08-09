package ru.anisimov.keenwg.ui.util

import ru.anisimov.keenwg.domain.model.HandshakeKind
import ru.anisimov.keenwg.domain.model.HandshakeStatus

fun handshakeLabel(status: HandshakeStatus): String = when (status.kind) {
    HandshakeKind.JUST_NOW -> "Сейчас"
    HandshakeKind.NEVER -> "Подключений пока не было"
    HandshakeKind.UNKNOWN, HandshakeKind.INVALID -> "Время подключения неизвестно"
    HandshakeKind.AGE -> {
        val seconds = status.ageSeconds ?: return "Время подключения неизвестно"
        when {
            seconds < 60 -> "$seconds с назад"
            seconds < 3600 -> "${seconds / 60} мин назад"
            else -> "${seconds / 3600} ч назад"
        }
    }
}

fun bytesLabel(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
}
