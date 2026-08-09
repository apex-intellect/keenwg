package ru.anisimov.keenwg.data

sealed class RouterMutationError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RolledBack(message: String, cause: Throwable? = null) : RouterMutationError(message, cause)
    class Uncertain(message: String, cause: Throwable? = null) : RouterMutationError(message, cause)
    class LocalFinalization(
        val newPublicKey: String?,
        message: String,
        cause: Throwable? = null,
    ) : RouterMutationError(message, cause)
}
