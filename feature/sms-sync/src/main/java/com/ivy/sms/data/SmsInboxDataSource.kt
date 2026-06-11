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
     * Reads inbox rows with `DATE >= lowerBoundEpochMillis` (inclusive period
     * boundary) AND `DATE > watermarkEpochMillis` (strictly greater: the
     * watermark stores the newest DATE already processed, so the boundary row
     * is never re-read — the old inclusive bound re-routed the newest message
     * on every scan and duplicated its transaction; pass 0 to disable the
     * incremental bound).
     * If [senderFilter] is non-null, results are scoped to that sender — used by the
     * per-wallet sync flow (2026-04-28 redesign).
     */
    suspend fun read(
        lowerBoundEpochMillis: Long,
        watermarkEpochMillis: Long,
        senderFilter: String? = null,
    ): Either<String, List<SmsRow>>

    /**
     * Returns the device inbox's distinct sender IDs ranked by recency
     * (newest message first, message count as the tiebreak). Used by the
     * SMS setup sheet's inline sender selector — "the bank that texted me
     * most recently" is almost always the right default.
     */
    suspend fun listSenders(): Either<String, List<SenderSummary>>
}

data class SenderSummary(
    val senderId: String,
    val messageCount: Int,
    /** Epoch millis of the sender's newest inbox message (0 when unknown). */
    val lastMessageEpochMillis: Long = 0L,
)

/** Recency-first ranking shared by the impl and unit tests. */
internal fun List<SenderSummary>.rankedByRecency(): List<SenderSummary> =
    sortedWith(
        compareByDescending<SenderSummary> { it.lastMessageEpochMillis }
            .thenByDescending { it.messageCount },
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
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        // Two separate bounds on purpose: the period bound is inclusive, the
        // watermark bound is strictly greater (see interface doc).
        val (selection, selectionArgs) = if (senderFilter == null) {
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} > ?" to arrayOf(
                lowerBoundEpochMillis.toString(),
                watermarkEpochMillis.toString(),
            )
        } else {
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.ADDRESS} = ?" to arrayOf(
                lowerBoundEpochMillis.toString(),
                watermarkEpochMillis.toString(),
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
            val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE)
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
                    val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                    val counts = HashMap<String, Int>()
                    val newest = HashMap<String, Long>()
                    while (c.moveToNext()) {
                        val address = c.getString(addrIdx) ?: continue
                        if (address.isBlank()) continue
                        counts[address] = (counts[address] ?: 0) + 1
                        val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                        if (date > (newest[address] ?: 0L)) newest[address] = date
                    }
                    counts.entries
                        .map { SenderSummary(it.key, it.value, newest[it.key] ?: 0L) }
                        .rankedByRecency()
                        .right()
                }
            } catch (e: SecurityException) {
                "PERMISSION_DENIED:${e.message ?: "READ_SMS denied"}".left()
            } catch (e: Exception) {
                "READ_ERROR:${e.message ?: e::class.simpleName}".left()
            }
        }
}
