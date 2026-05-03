package com.ivy.sms.data

import android.content.Context
import android.provider.Telephony
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface SmsInboxDataSource {
    /**
     * Reads inbox rows greater than `max(lowerBoundEpochMillis, watermarkEpochMillis)`.
     * If [senderFilter] is non-null, results are scoped to that sender — used by the
     * per-wallet sync flow (2026-04-28 redesign).
     */
    suspend fun read(
        lowerBoundEpochMillis: Long,
        watermarkEpochMillis: Long,
        senderFilter: String? = null,
    ): Either<String, List<SmsRow>>

    /**
     * Returns the device inbox's distinct sender IDs ranked by descending message count.
     * Used by the wallet edit modal's "Link SMS chat" picker (top-10 + free-text validation).
     */
    suspend fun listSenders(): Either<String, List<SenderSummary>>
}

data class SenderSummary(
    val senderId: String,
    val messageCount: Int,
)

class SmsInboxDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
) : SmsInboxDataSource {
    override suspend fun read(
        lowerBoundEpochMillis: Long,
        watermarkEpochMillis: Long,
        senderFilter: String?,
    ): Either<String, List<SmsRow>> = withContext(dispatchers.io) {
        val effectiveLower = maxOf(lowerBoundEpochMillis, watermarkEpochMillis)
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        val (selection, selectionArgs) = if (senderFilter == null) {
            "${Telephony.Sms.DATE} >= ?" to arrayOf(effectiveLower.toString())
        } else {
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.ADDRESS} = ?" to arrayOf(
                effectiveLower.toString(),
                senderFilter,
            )
        }
        val sortOrder = "${Telephony.Sms.DATE} ASC"

        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            ) ?: return@withContext emptyList<SmsRow>().right()

            cursor.use { c ->
                val idIdx = c.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val rows = ArrayList<SmsRow>(c.count)
                while (c.moveToNext()) {
                    val address = if (addrIdx >= 0) c.getString(addrIdx) else null
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) else null
                    if (address.isNullOrBlank() || body == null) continue
                    rows.add(
                        SmsRow(
                            id = if (idIdx >= 0) c.getLong(idIdx) else 0L,
                            address = address,
                            dateEpochMillis = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                            body = body,
                        )
                    )
                }
                rows.right()
            }
        } catch (e: SecurityException) {
            "PERMISSION_DENIED:${e.message ?: "READ_SMS denied"}".left()
        } catch (e: Exception) {
            "READ_ERROR:${e.message ?: e::class.simpleName}".left()
        }
    }

    override suspend fun listSenders(): Either<String, List<SenderSummary>> =
        withContext(dispatchers.io) {
            val projection = arrayOf(Telephony.Sms.ADDRESS)
            try {
                val cursor = context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null,
                ) ?: return@withContext emptyList<SenderSummary>().right()

                cursor.use { c ->
                    val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    if (addrIdx < 0) return@withContext emptyList<SenderSummary>().right()
                    val counts = HashMap<String, Int>()
                    while (c.moveToNext()) {
                        val address = c.getString(addrIdx) ?: continue
                        if (address.isBlank()) continue
                        counts[address] = (counts[address] ?: 0) + 1
                    }
                    counts.entries
                        .map { SenderSummary(it.key, it.value) }
                        .sortedByDescending { it.messageCount }
                        .right()
                }
            } catch (e: SecurityException) {
                "PERMISSION_DENIED:${e.message ?: "READ_SMS denied"}".left()
            } catch (e: Exception) {
                "READ_ERROR:${e.message ?: e::class.simpleName}".left()
            }
        }
}
