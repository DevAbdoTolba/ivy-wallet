package com.ivy.sms.data

import android.database.sqlite.SQLiteConstraintException
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.ReadPendingReviewItemDao
import com.ivy.data.db.dao.write.WritePendingReviewItemDao
import com.ivy.sms.domain.model.PendingReviewItem
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface PendingReviewItemRepository {
    suspend fun findAll(): Either<String, List<PendingReviewItem>>
    suspend fun findByTemplateId(templateId: SmsTemplateId): Either<String, List<PendingReviewItem>>
    suspend fun count(): Either<String, Int>
    fun observeCount(): Flow<Int>
    fun observeAllRaw(): Flow<List<com.ivy.data.db.entity.PendingReviewItemEntity>>

    suspend fun enqueue(item: PendingReviewItem): Either<String, Unit>
    suspend fun dismiss(id: PendingReviewItemId): Either<String, Unit>
    suspend fun clearByTemplate(templateId: SmsTemplateId): Either<String, Unit>
}

@Singleton
class PendingReviewItemRepositoryImpl @Inject constructor(
    private val readDao: ReadPendingReviewItemDao,
    private val writeDao: WritePendingReviewItemDao,
    private val templateRepo: SmsTemplateRepository,
    private val mapper: PendingReviewItemMapper,
    private val dispatchers: DispatchersProvider,
    private val prefs: SmsWatermarkPreferences,
) : PendingReviewItemRepository {

    override suspend fun findAll(): Either<String, List<PendingReviewItem>> = withContext(dispatchers.io) {
        runCatching { readDao.findAll() }.fold(
            onSuccess = { entities ->
                val mapped = mutableListOf<PendingReviewItem>()
                for (e in entities) {
                    val template = templateRepo.findById(SmsTemplateId(java.util.UUID.fromString(e.templateId)))
                        .getOrNull() ?: continue
                    with(mapper) { e.toDomain(template) }.onRight { mapped.add(it) }
                }
                mapped.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun findByTemplateId(templateId: SmsTemplateId): Either<String, List<PendingReviewItem>> = withContext(dispatchers.io) {
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return@withContext emptyList<PendingReviewItem>().right()
        runCatching { readDao.findByTemplateId(templateId.value.toString()) }.fold(
            onSuccess = { entities ->
                val mapped = mutableListOf<PendingReviewItem>()
                for (e in entities) {
                    with(mapper) { e.toDomain(template) }.onRight { mapped.add(it) }
                }
                mapped.right()
            },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun count(): Either<String, Int> = withContext(dispatchers.io) {
        runCatching { readDao.count() }.fold(
            onSuccess = { it.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override fun observeCount(): Flow<Int> = readDao.observeCount().flowOn(dispatchers.io)

    override fun observeAllRaw(): Flow<List<com.ivy.data.db.entity.PendingReviewItemEntity>> =
        readDao.observeAll().flowOn(dispatchers.io)

    override suspend fun enqueue(item: PendingReviewItem): Either<String, Unit> = withContext(dispatchers.io) {
        try {
            writeDao.insert(with(mapper) { item.toEntity() })
            // Bump the lifetime "discovered" counter so the review-screen
            // hero's denominator climbs monotonically. Dedup hits below skip
            // this on purpose — the same dedupKey shouldn't double-count.
            prefs.incrementDiscoveredTotal()
            Unit.right()
        } catch (e: SQLiteConstraintException) {
            Timber.d("Pending item already enqueued for dedupKey=${item.sms.dedupKey}")
            Unit.right()
        } catch (e: Exception) {
            "STORAGE_ERROR:${e.message}".left()
        }
    }

    override suspend fun dismiss(id: PendingReviewItemId): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching { writeDao.deleteById(id.value.toString()) }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    override suspend fun clearByTemplate(templateId: SmsTemplateId): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching { writeDao.deleteByTemplateId(templateId.value.toString()) }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }
}
