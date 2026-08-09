package ru.anisimov.keenwg.data.store

import ru.anisimov.keenwg.domain.model.AccessPolicy

interface AccessPolicyStore {
    suspend fun put(publicKey: String, policy: AccessPolicy)
    suspend fun get(publicKey: String): AccessPolicy?
    suspend fun rotate(oldPublicKey: String, newPublicKey: String, policy: AccessPolicy)
    suspend fun remove(publicKey: String)
}

object EmptyAccessPolicyStore : AccessPolicyStore {
    override suspend fun put(publicKey: String, policy: AccessPolicy) = Unit
    override suspend fun get(publicKey: String): AccessPolicy? = null
    override suspend fun rotate(oldPublicKey: String, newPublicKey: String, policy: AccessPolicy) = Unit
    override suspend fun remove(publicKey: String) = Unit
}
