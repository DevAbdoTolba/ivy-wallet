package com.ivy.sms.data

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.ReadSmsTemplateDao
import com.ivy.data.db.dao.write.WriteSmsTemplateDao
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface SmsTemplateRepository {
    suspend fun findAll(): Either<String, List<SmsTemplate>>
    suspend fun findById(id: SmsTemplateId): Either<String, SmsTemplate?>
    suspend fun findByPattern(pattern: String): Either<String, SmsTemplate?>
    suspend fun findActive(): Either<String, List<SmsTemplate>>
    suspend fun upsert(template: SmsTemplate): Either<String, Unit>
    suspend fun delete(id: SmsTemplateId): Either<String, Unit>
    fun observeAll(): Flow<List<SmsTemplate>>
}

@Singleton
class SmsTemplateRepositoryImpl @Inject constructor(
    private val readDao: ReadSmsTemplateDao,
    private val writeDao: WriteSmsTemplateDao,
    private val mapper: SmsTemplateMapper,
    private val dispatchers: DispatchersProvider,
) : SmsTemplateRepository {

    override suspend fun findAll(): Either<String, List<SmsTemplate>> = withContext(dispatchers.io) {
        runCatching { readDao.findAll() }.fold(
            onSuccess = { entities ->
                val mapped = entities.mapNotNull { entity ->
                    with(mapper) { entity.toDomain() }.getOrNull()
                }
                mapped.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun findById(id: SmsTemplateId): Either<String, SmsTemplate?> = withContext(dispatchers.io) {
        runCatching { readDao.findById(id.value.toString()) }.fold(
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

    override suspend fun findByPattern(pattern: String): Either<String, SmsTemplate?> = withContext(dispatchers.io) {
        runCatching { readDao.findByPattern(pattern) }.fold(
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

    override suspend fun findActive(): Either<String, List<SmsTemplate>> = withContext(dispatchers.io) {
        runCatching { readDao.findActive() }.fold(
            onSuccess = { entities ->
                entities.mapNotNull { with(mapper) { it.toDomain() }.getOrNull() }.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun upsert(template: SmsTemplate): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            writeDao.upsert(with(mapper) { template.toEntity() })
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun delete(id: SmsTemplateId): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            writeDao.delete(id.value.toString())
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override fun observeAll(): Flow<List<SmsTemplate>> = readDao.observeAll()
        .map { list -> list.mapNotNull { entity -> with(mapper) { entity.toDomain() }.getOrNull() } }
        .flowOn(dispatchers.io)
}
