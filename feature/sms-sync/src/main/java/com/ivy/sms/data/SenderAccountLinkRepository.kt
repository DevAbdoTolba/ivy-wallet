package com.ivy.sms.data

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.ReadSenderAccountLinkDao
import com.ivy.data.db.dao.write.WriteSenderAccountLinkDao
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.domain.model.SenderAccountLink
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface SenderAccountLinkRepository {
    suspend fun findAll(): Either<String, List<SenderAccountLink>>
    suspend fun findBySenderId(senderId: String): Either<String, SenderAccountLink?>
    suspend fun findByAccountId(accountId: AccountId): Either<String, List<SenderAccountLink>>
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SenderAccountLink>>
    suspend fun upsert(link: SenderAccountLink): Either<String, Unit>
    suspend fun delete(senderId: String): Either<String, Unit>
}

@Singleton
class SenderAccountLinkRepositoryImpl @Inject constructor(
    private val readDao: ReadSenderAccountLinkDao,
    private val writeDao: WriteSenderAccountLinkDao,
    private val mapper: SenderAccountLinkMapper,
    private val accountRepository: AccountRepository,
    private val dispatchers: DispatchersProvider,
) : SenderAccountLinkRepository {

    override suspend fun findAll(): Either<String, List<SenderAccountLink>> = withContext(dispatchers.io) {
        runCatching { readDao.findAll() }.fold(
            onSuccess = { entities ->
                val mapped = mutableListOf<SenderAccountLink>()
                for (e in entities) {
                    with(mapper) { e.toDomain() }.onRight { mapped.add(it) }
                }
                mapped.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun findBySenderId(senderId: String): Either<String, SenderAccountLink?> = withContext(dispatchers.io) {
        runCatching { readDao.findBySenderId(senderId) }.fold(
            onSuccess = { entity ->
                if (entity == null) {
                    null.right()
                } else {
                    with(mapper) { entity.toDomain() }
                        .fold({ "STORAGE_ERROR:$it".left() }, { it.right() })
                }
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<SenderAccountLink>> =
        readDao.observeAll().map { entities ->
            entities.mapNotNull { e -> with(mapper) { e.toDomain() }.getOrNull() }
        }

    override suspend fun findByAccountId(accountId: AccountId): Either<String, List<SenderAccountLink>> = withContext(dispatchers.io) {
        runCatching { readDao.findByAccountId(accountId.value.toString()) }.fold(
            onSuccess = { entities ->
                val mapped = entities.mapNotNull { e ->
                    with(mapper) { e.toDomain() }.getOrNull()
                }
                mapped.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun upsert(link: SenderAccountLink): Either<String, Unit> = withContext(dispatchers.io) {
        val existing = runCatching { readDao.findBySenderId(link.senderId) }.getOrNull()
        if (existing != null && existing.accountId != link.accountId.value.toString()) {
            val conflictingId = AccountId(java.util.UUID.fromString(existing.accountId))
            val walletName = accountRepository.findById(conflictingId)?.name?.value ?: existing.accountId
            return@withContext "LINK_CONFLICT: sender '${link.senderId}' already linked to wallet '$walletName'".left()
        }
        runCatching {
            writeDao.delete(link.senderId)
            writeDao.insert(with(mapper) { link.toEntity() })
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun delete(senderId: String): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching { writeDao.delete(senderId) }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }
}
