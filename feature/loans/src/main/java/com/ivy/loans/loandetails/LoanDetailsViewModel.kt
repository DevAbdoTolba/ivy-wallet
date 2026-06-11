package com.ivy.loans.loandetails

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import com.ivy.base.legacy.Transaction
import com.ivy.base.model.LoanRecordType
import com.ivy.base.time.TimeConverter
import com.ivy.base.time.TimeProvider
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.data.model.LoanItemId
import com.ivy.data.repository.LoanRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.frp.test.TestIdlingResource
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.legacy.datamodel.temp.toLegacy
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import com.ivy.legacy.domain.deprecated.logic.AccountCreator
import com.ivy.legacy.utils.computationThread
import com.ivy.legacy.utils.ioThread
import com.ivy.loans.loan.data.DisplayLoanItem
import com.ivy.loans.loan.data.DisplayLoanRecord
import com.ivy.loans.loandetails.events.DeleteLoanModalEvent
import com.ivy.loans.loandetails.events.LoanDetailsScreenEvent
import com.ivy.loans.loandetails.events.LoanModalEvent
import com.ivy.loans.loandetails.events.LoanRecordModalEvent
import com.ivy.navigation.LoanDetailsScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import com.ivy.ui.time.impl.DateTimePicker
import com.ivy.wallet.domain.action.account.AccountsAct
import com.ivy.wallet.domain.action.loan.LoanByIdAct
import com.ivy.wallet.domain.deprecated.logic.LoanCreator
import com.ivy.wallet.domain.deprecated.logic.LoanRecordCreator
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LoanTransactionsLogic
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import com.ivy.wallet.domain.deprecated.logic.model.EditLoanRecordData
import com.ivy.wallet.ui.theme.modal.LoanModalData
import com.ivy.wallet.ui.theme.modal.LoanRecordModalData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

@Stable
@HiltViewModel
class LoanDetailsViewModel @Inject constructor(
    private val loanRecordDao: LoanRecordDao,
    private val loanCreator: LoanCreator,
    private val loanRecordCreator: LoanRecordCreator,
    private val settingsDao: SettingsDao,
    private val transactionRepository: TransactionRepository,
    private val transactionMapper: TransactionMapper,
    private val accountCreator: AccountCreator,
    private val loanTransactionsLogic: LoanTransactionsLogic,
    private val nav: Navigation,
    private val accountsAct: AccountsAct,
    private val loanByIdAct: LoanByIdAct,
    private val timeConverter: TimeConverter,
    private val timeProvider: TimeProvider,
    private val dateTimePicker: DateTimePicker,
    private val loanRepository: LoanRepository,
) : ComposeViewModel<LoanDetailsScreenState, LoanDetailsScreenEvent>() {

    private val baseCurrency = mutableStateOf("")
    private val loan = mutableStateOf<Loan?>(null)
    private val displayLoanRecords =
        mutableStateOf<ImmutableList<DisplayLoanRecord>>(persistentListOf())
    private val displayLoanItems =
        mutableStateOf<ImmutableList<DisplayLoanItem>>(persistentListOf())
    private val loanTotalAmount = mutableDoubleStateOf(0.0)
    private val amountPaid = mutableDoubleStateOf(0.0)
    private val accounts = mutableStateOf<ImmutableList<Account>>(persistentListOf())
    private val loanInterestAmountPaid = mutableDoubleStateOf(0.0)
    private val selectedLoanAccount = mutableStateOf<Account?>(null)
    private var associatedTransaction: Transaction? = null
    private val createLoanTransaction = mutableStateOf(false)
    private var defaultCurrencyCode = ""
    private val loanModalData = mutableStateOf<LoanModalData?>(null)
    private val loanRecordModalData = mutableStateOf<LoanRecordModalData?>(null)
    private val loanItemModalVisible = mutableStateOf(false)
    private val selectedLoanItem = mutableStateOf<LoanItem?>(null)
    private val deleteLoanItemId = mutableStateOf<LoanItemId?>(null)
    private val waitModalVisible = mutableStateOf(false)
    private val isDeleteModalVisible = mutableStateOf(false)
    private var dateTime = mutableStateOf<Instant>(timeProvider.utcNow())
    private val isLoading = mutableStateOf(false)

    // amountPaid = settled checklist items + non-interest DECREASE records.
    // The two components arrive from independent async sources (items flow vs
    // records load), so each is kept separately and the sum is re-published
    // whenever either side updates.
    private var settledItemsAmount = 0.0
    private var recordsPaidAmount = 0.0

    // Job for the current loan-items flow collection. The VM is scoped to the
    // Activity (custom router — not NavHost), so it's reused across loans.
    // We cancel the prior collector before starting a new one to avoid two
    // flows racing to overwrite displayLoanItems.
    private var itemsJob: Job? = null

    // Job for the current load(). A stale load resuming after the user
    // navigated to another loan used to clobber loan.value and re-point the
    // items collector at the wrong loan, so the whole load is tracked and
    // cancelled on loan change.
    private var loadJob: Job? = null

    private var _screen: LoanDetailsScreen? = null
    var screen: LoanDetailsScreen
        get() = _screen!!
        set(value) {
            val changed = _screen?.loanId != value.loanId
            _screen = value
            if (changed) {
                // Reset synchronously so the first recomposition on the new
                // loan doesn't flash the previous loan's data.
                resetStateForNewLoan()
            }
        }

    private fun resetStateForNewLoan() {
        Timber.tag("LoanTrace").d("resetStateForNewLoan: clearing items (was ${displayLoanItems.value.size}) for loanId=${_screen?.loanId}")
        loadJob?.cancel()
        loadJob = null
        itemsJob?.cancel()
        itemsJob = null
        loan.value = null
        displayLoanRecords.value = persistentListOf()
        displayLoanItems.value = persistentListOf()
        loanTotalAmount.doubleValue = 0.0
        amountPaid.doubleValue = 0.0
        loanInterestAmountPaid.doubleValue = 0.0
        settledItemsAmount = 0.0
        recordsPaidAmount = 0.0
        selectedLoanAccount.value = null
        createLoanTransaction.value = false
        loanModalData.value = null
        loanRecordModalData.value = null
        loanItemModalVisible.value = false
        selectedLoanItem.value = null
        deleteLoanItemId.value = null
        waitModalVisible.value = false
        isDeleteModalVisible.value = false
        associatedTransaction = null
        isLoading.value = true
    }

    @Composable
    override fun uiState(): LoanDetailsScreenState {
        LaunchedEffect(Unit) {
            start()
        }

        return LoanDetailsScreenState(
            baseCurrency = baseCurrency.value,
            loan = loan.value,
            displayLoanRecords = displayLoanRecords.value,
            displayLoanItems = displayLoanItems.value,
            loanTotalAmount = loanTotalAmount.doubleValue,
            amountPaid = amountPaid.doubleValue,
            loanAmountPaid = loanInterestAmountPaid.doubleValue,
            accounts = accounts.value,
            selectedLoanAccount = selectedLoanAccount.value,
            createLoanTransaction = createLoanTransaction.value,
            loanModalData = loanModalData.value,
            loanRecordModalData = loanRecordModalData.value,
            loanItemModalVisible = loanItemModalVisible.value,
            selectedLoanItem = selectedLoanItem.value,
            deleteLoanItemId = deleteLoanItemId.value,
            waitModalVisible = waitModalVisible.value,
            isDeleteModalVisible = isDeleteModalVisible.value,
            dateTime = dateTime.value,
            isLoading = isLoading.value,
        )
    }

    override fun onEvent(event: LoanDetailsScreenEvent) {
        when (event) {
            is LoanRecordModalEvent -> handleLoanRecordModalEvents(event)
            is LoanModalEvent -> handleLoanModalEvents(event)
            is DeleteLoanModalEvent -> handleDeleteLoanModalEvents(event)
            is LoanDetailsScreenEvent -> handleLoanDetailsScreenEvents(event)
        }
    }

    private fun handleLoanRecordModalEvents(event: LoanDetailsScreenEvent) {
        when (event) {
            is LoanRecordModalEvent.OnClickLoanRecord -> {
                loanRecordModalData.value = LoanRecordModalData(
                    loanRecord = event.displayLoanRecord.loanRecord,
                    baseCurrency = event.displayLoanRecord.loanRecordCurrencyCode,
                    selectedAccount = event.displayLoanRecord.account,
                    createLoanRecordTransaction = event.displayLoanRecord.loanRecordTransaction,
                    isLoanInterest = event.displayLoanRecord.loanRecord.interest,
                    loanAccountCurrencyCode = event.displayLoanRecord.loanCurrencyCode
                )
            }

            is LoanRecordModalEvent.OnCreateLoanRecord -> {
                createLoanRecord(event.loanRecordData)
            }

            is LoanRecordModalEvent.OnDeleteLoanRecord -> {
                deleteLoanRecord(event.loanRecord)
            }

            LoanRecordModalEvent.OnDismissLoanRecord -> {
                loanRecordModalData.value = null
                dateTime.value = timeProvider.utcNow()
            }

            is LoanRecordModalEvent.OnEditLoanRecord -> {
                editLoanRecord(event.loanRecordData)
            }

            is LoanRecordModalEvent.OnChangeDate -> {
                handleChangeDate()
            }
            is LoanRecordModalEvent.OnChangeTime -> {
                handleChangeTime()
            }
            else -> {}
        }
    }

    private fun handleLoanModalEvents(event: LoanDetailsScreenEvent) {
        when (event) {
            LoanModalEvent.OnDismissLoanModal -> {
                loanModalData.value = null
                dateTime.value = timeProvider.utcNow()
            }

            is LoanModalEvent.OnEditLoanModal -> {
                editLoan(event.loan, event.createLoanTransaction)
            }

            LoanModalEvent.PerformCalculation -> {
                waitModalVisible.value = true
            }

            LoanModalEvent.OnChangeDate -> {
                handleLoanChangeDate()
            }

            LoanModalEvent.OnChangeTime -> {
                handleLoanChangeTime()
            }

            else -> {}
        }
    }

    private fun handleDeleteLoanModalEvents(event: LoanDetailsScreenEvent) {
        when (event) {
            DeleteLoanModalEvent.OnDeleteLoan -> {
                deleteLoan()
                isDeleteModalVisible.value = false
            }

            is DeleteLoanModalEvent.OnDismissDeleteLoan -> {
                isDeleteModalVisible.value = event.isDeleteModalVisible
            }

            else -> {}
        }
    }

    private fun handleLoanDetailsScreenEvents(event: LoanDetailsScreenEvent) {
        when (event) {
            LoanDetailsScreenEvent.OnAmountClick -> {
                loanModalData.value = LoanModalData(
                    loan = loan.value,
                    baseCurrency = baseCurrency.value,
                    autoFocusKeyboard = false,
                    autoOpenAmountModal = true,
                    selectedAccount = selectedLoanAccount.value,
                    createLoanTransaction = createLoanTransaction.value
                )
            }

            LoanDetailsScreenEvent.OnEditLoanClick -> {
                loanModalData.value = LoanModalData(
                    loan = loan.value,
                    baseCurrency = baseCurrency.value,
                    autoFocusKeyboard = false,
                    selectedAccount = selectedLoanAccount.value,
                    createLoanTransaction = createLoanTransaction.value
                )
            }

            LoanDetailsScreenEvent.OnAddRecord -> {
                loanRecordModalData.value = LoanRecordModalData(
                    loanRecord = null,
                    baseCurrency = baseCurrency.value,
                    selectedAccount = selectedLoanAccount.value
                )
            }

            is LoanDetailsScreenEvent.OnCreateAccount -> {
                createAccount(event.data)
            }

            is LoanDetailsScreenEvent.OnToggleLoanItemSettled -> {
                toggleLoanItemSettled(event.id, event.isSettled)
            }

            LoanDetailsScreenEvent.OnAddLoanItem -> {
                selectedLoanItem.value = null
                loanItemModalVisible.value = true
            }

            is LoanDetailsScreenEvent.OnSaveLoanItem -> {
                saveLoanItem(event.title, event.amount, event.editingItemId)
                loanItemModalVisible.value = false
                selectedLoanItem.value = null
            }

            is LoanDetailsScreenEvent.OnDeleteLoanItem -> {
                deleteLoanItemId.value = event.id
            }

            LoanDetailsScreenEvent.OnConfirmDeleteLoanItem -> {
                deleteLoanItemId.value?.let { deleteLoanItem(it) }
                deleteLoanItemId.value = null
            }

            LoanDetailsScreenEvent.OnDismissDeleteLoanItem -> {
                deleteLoanItemId.value = null
            }

            is LoanDetailsScreenEvent.OnEditLoanItem -> {
                selectedLoanItem.value = event.loanItem
                loanItemModalVisible.value = true
            }

            LoanDetailsScreenEvent.OnDismissLoanItemModal -> {
                loanItemModalVisible.value = false
                selectedLoanItem.value = null
            }

            else -> {}
        }
    }

    private fun start() {
        load(loanId = screen.loanId)
    }

    /**
     * True when the screen no longer targets [loanId] — i.e. this coroutine
     * resumed after the user navigated to a different loan. State mutations
     * must be skipped in that case or they'd corrupt the new loan's screen.
     */
    private fun isStale(loanId: UUID): Boolean = _screen?.loanId != loanId

    private fun load(loanId: UUID) {
        Timber.tag("LoanTrace").d("load() called for loanId=$loanId (will cancel+restart loadJob/itemsJob)")
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            TestIdlingResource.increment()
            try {
                loadInternal(loanId)
            } finally {
                TestIdlingResource.decrement()
            }
        }
    }

    private suspend fun loadInternal(loanId: UUID) {
        isLoading.value = true
        dateTime.value = timeProvider.utcNow()

        val currency = ioThread {
            settingsDao.findFirst().currency
        }
        if (isStale(loanId)) return
        defaultCurrencyCode = currency
        baseCurrency.value = currency

        val loadedAccounts = accountsAct(Unit)
        if (isStale(loanId)) return
        accounts.value = loadedAccounts

        val loadedLoan = loanByIdAct(loanId)
        if (isStale(loanId)) return
        loan.value = loadedLoan
        // Note: isLoading stays true until the loan-items flow emits once —
        // the header already renders the moment `loan.value` is non-null,
        // and keeping isLoading true in the meantime suppresses the
        // "No items" empty state from briefly flashing before the first
        // items emission arrives.

        loadedLoan?.let { loan ->
            selectedLoanAccount.value = accounts.value.find {
                loan.accountId == it.id
            }

            selectedLoanAccount.value?.let { acc ->
                baseCurrency.value = acc.currency ?: defaultCurrencyCode
            }
        }

        // Observe loan items for the checklist. If the user never itemized
        // this loan, fall back to the loan's headline amount so the header
        // doesn't show 0.0.
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch {
            loanRepository.getLoanItems(LoanId(loanId)).collect { items ->
                Timber.tag("LoanTrace").d("itemsFlow emit: loanId=$loanId count=${items.size} ids=${items.map { it.id.value }}")
                displayLoanItems.value = items.map { DisplayLoanItem(it) }.toImmutableList()

                if (items.isEmpty()) {
                    loanTotalAmount.doubleValue = loan.value?.amount ?: 0.0
                    settledItemsAmount = 0.0
                } else {
                    loanTotalAmount.doubleValue = items.sumOf { it.amount }
                    settledItemsAmount = items.filter { it.isSettled }.sumOf { it.amount }
                }
                amountPaid.doubleValue = settledItemsAmount + recordsPaidAmount

                // First emission after (re)loading — screen is now fully
                // populated, so it's safe to let the empty state show if
                // the list really is empty.
                if (isLoading.value) isLoading.value = false
            }
        }

        val records = computationThread {
            ioThread { loanRecordDao.findAllByLoanId(loanId = loanId) }.map {
                val trans = ioThread {
                    transactionRepository.findLoanRecordTransaction(
                        it.id
                    )
                }

                val account = findAccount(
                    accounts = accounts.value,
                    accountId = it.accountId,
                )

                DisplayLoanRecord(
                    it.toLegacyDomain(),
                    account = account,
                    loanRecordTransaction = trans != null,
                    loanRecordCurrencyCode = account?.currency ?: defaultCurrencyCode,
                    loanCurrencyCode = selectedLoanAccount.value?.currency
                        ?: defaultCurrencyCode
                )
            }.toImmutableList()
        }
        if (isStale(loanId)) return
        displayLoanRecords.value = records

        // amountPaid and loanInterestAmountPaid calculation logic for header
        val (recordsPaid, interestPaid) = computationThread {
            var paid = 0.0
            var interest = 0.0
            records.forEach {
                if (it.loanRecord.loanRecordType == LoanRecordType.INCREASE) return@forEach
                val convertedAmount = it.loanRecord.convertedAmount ?: it.loanRecord.amount
                if (it.loanRecord.interest) {
                    interest += convertedAmount
                } else {
                    paid += convertedAmount
                }
            }
            paid to interest
        }
        if (isStale(loanId)) return
        recordsPaidAmount = recordsPaid
        loanInterestAmountPaid.doubleValue = interestPaid
        amountPaid.doubleValue = settledItemsAmount + recordsPaidAmount

        val loanTransaction = ioThread {
            transactionRepository.findLoanTransaction(loanId = loanId).let {
                it?.toLegacy(transactionMapper)
            }
        }
        if (isStale(loanId)) return
        associatedTransaction = loanTransaction

        associatedTransaction?.let {
            createLoanTransaction.value = true
        } ?: run {
            createLoanTransaction.value = false
        }
    }

    private fun toggleLoanItemSettled(id: LoanItemId, isSettled: Boolean) {
        viewModelScope.launch {
            loanRepository.updateSettledStatus(id, isSettled)
        }
    }

    private fun saveLoanItem(title: String, amount: Double, editingItemId: LoanItemId?) {
        // Stamp the item with the SCREEN's loanId — loan.value can briefly
        // belong to a previously visited loan while a stale load resumes.
        val loanId = _screen?.loanId ?: return
        // Resolve the edited item synchronously at event time so a later
        // mutation of selectedLoanItem can't flip an edit into a create.
        val editing = editingItemId?.let { id ->
            selectedLoanItem.value?.takeIf { it.id == id }
        }
        val item = editing?.copy(title = title, amount = amount)
            ?: LoanItem(
                contactId = LoanId(loanId),
                title = title,
                amount = amount
            )
        Timber.tag("LoanTrace").d(
            "saveLoanItem: loanId=$loanId editingExisting=${editing != null} " +
                "itemId=${item.id.value} contactId=${item.contactId.value}"
        )
        viewModelScope.launch {
            loanRepository.saveLoanItem(item)
        }
    }

    private fun deleteLoanItem(id: LoanItemId) {
        viewModelScope.launch {
            loanRepository.deleteLoanItem(id)
        }
    }

    fun editLoan(loan: Loan, createLoanTransaction: Boolean = false) {
        viewModelScope.launch {
            TestIdlingResource.increment()

            this@LoanDetailsViewModel.loan.value?.let {
                loanTransactionsLogic.Loan.recalculateLoanRecords(
                    oldLoanAccountId = it.accountId,
                    newLoanAccountId = loan.accountId,
                    loanId = loan.id
                )
            }

            loanTransactionsLogic.Loan.editAssociatedLoanTransaction(
                loan = loan,
                createLoanTransaction = createLoanTransaction,
                transaction = associatedTransaction
            )

            loanCreator.edit(loan) {
                load(loanId = it.id)
            }

            TestIdlingResource.decrement()
        }
    }

    private fun deleteLoan() {
        val loan = loan.value ?: return

        viewModelScope.launch {
            TestIdlingResource.increment()

            loanTransactionsLogic.Loan.deleteAssociatedLoanTransactions(loan.id)

            loanCreator.delete(loan) {
                // close screen
                nav.back()
            }

            TestIdlingResource.decrement()
        }
    }

    private fun createLoanRecord(data: CreateLoanRecordData) {
        if (loan.value == null) return
        val loanId = loan.value?.id ?: return
        val localLoan = loan.value!!

        viewModelScope.launch {
            TestIdlingResource.increment()

            val modifiedData = data.copy(
                convertedAmount = loanTransactionsLogic.LoanRecord.calculateConvertedAmount(
                    data = data,
                    loanAccountId = localLoan.accountId
                )
            )

            val loanRecordUUID = loanRecordCreator.create(
                loanId = loanId,
                data = modifiedData
            ) {
                load(loanId = loanId)
            }

            loanRecordUUID?.let {
                loanTransactionsLogic.LoanRecord.createAssociatedLoanRecordTransaction(
                    data = modifiedData,
                    loan = localLoan,
                    loanRecordId = it
                )
            }

            TestIdlingResource.decrement()
        }
    }

    private fun editLoanRecord(editLoanRecordData: EditLoanRecordData) {
        viewModelScope.launch {
            val loanRecord = editLoanRecordData.newLoanRecord
            TestIdlingResource.increment()

            val localLoan: Loan = loan.value ?: return@launch

            val convertedAmount = loanTransactionsLogic.LoanRecord.calculateConvertedAmount(
                loanAccountId = localLoan.accountId,
                newLoanRecord = editLoanRecordData.newLoanRecord,
                oldLoanRecord = editLoanRecordData.originalLoanRecord,
                reCalculateLoanAmount = editLoanRecordData.reCalculateLoanAmount
            )

            val modifiedLoanRecord =
                editLoanRecordData.newLoanRecord.copy(convertedAmount = convertedAmount)

            loanTransactionsLogic.LoanRecord.editAssociatedLoanRecordTransaction(
                loan = localLoan,
                createLoanRecordTransaction = editLoanRecordData.createLoanRecordTransaction,
                loanRecord = loanRecord,
            )

            loanRecordCreator.edit(modifiedLoanRecord) {
                load(loanId = it.loanId)
            }

            TestIdlingResource.decrement()
        }
    }

    private fun deleteLoanRecord(loanRecord: LoanRecord) {
        val loanId = loan.value?.id ?: return

        viewModelScope.launch {
            TestIdlingResource.increment()

            loanRecordCreator.delete(loanRecord) {
                load(loanId = loanId)
            }

            loanTransactionsLogic.LoanRecord.deleteAssociatedLoanRecordTransaction(loanRecordId = loanRecord.id)

            TestIdlingResource.decrement()
        }
    }

    private fun handleChangeDate() {
        dateTimePicker.pickDate(
            initialDate = loanRecordModalData.value?.loanRecord?.dateTime?.let {
                with(timeConverter) { it.toLocalDateTime().toUTC() }
            } ?: timeProvider.utcNow()
        ) { localDate ->

            val localTime = loanRecordModalData.value?.loanRecord?.dateTime?.let {
                with(timeConverter) { it.toLocalTime() }
            } ?: timeProvider.localTimeNow()

            updateDateTime(localDate.atTime(localTime))
        }
    }

    private fun handleChangeTime() {
        dateTimePicker.pickTime(
            initialTime = loanRecordModalData.value?.loanRecord?.dateTime?.let {
                with(timeConverter) { it.toLocalTime() }
            } ?: timeProvider.localTimeNow()
        ) { localTime ->
            val localDate = loanRecordModalData.value?.loanRecord?.dateTime?.let {
                with(timeConverter) { it.toLocalDate() }
            } ?: timeProvider.localDateNow()

            updateDateTime(localDate.atTime(localTime))
        }
    }

    private fun updateDateTime(newDateTime: LocalDateTime) {
        val newDateTimeUtc = with(timeConverter) { newDateTime.toUTC() }
        loanRecordModalData.value?.let { currentData ->
            loanRecordModalData.value = currentData.copy(
                loanRecord = currentData.loanRecord?.copy(
                    dateTime = newDateTimeUtc
                )
            )
            dateTime.value = newDateTimeUtc
        }
    }

    private fun handleLoanChangeDate() {
        dateTimePicker.pickDate(
            initialDate = loanModalData.value?.loan?.dateTime?.let {
                with(timeConverter) { it.toUTC() }
            } ?: timeProvider.utcNow()
        ) { localDate ->

            val localTime = loanModalData.value?.loan?.dateTime?.let {
                with(timeConverter) { it.toLocalTime() }
            } ?: timeProvider.localTimeNow()

            updateLoanDateTime(localDate.atTime(localTime))
        }
    }

    private fun handleLoanChangeTime() {
        dateTimePicker.pickTime(
            initialTime = loanModalData.value?.loan?.dateTime?.let {
                with(timeConverter) { it.toLocalTime() }
            } ?: timeProvider.localTimeNow()
        ) { localTime ->
            val localDate = loanModalData.value?.loan?.dateTime?.let {
                with(timeConverter) { it.toLocalDate() }
            } ?: timeProvider.localDateNow()

            updateLoanDateTime(localDate.atTime(localTime))
        }
    }

    private fun updateLoanDateTime(newDateTime: LocalDateTime) {
        val newDateTimeUtc = with(timeConverter) { newDateTime.toUTC() }
        loanModalData.value?.let { currentData ->
            loanModalData.value = currentData.copy(
                loan = currentData.loan?.copy(
                    dateTime = newDateTime
                )
            )
            dateTime.value = newDateTimeUtc
        }
    }

    fun onLoanTransactionChecked(boolean: Boolean) {
        createLoanTransaction.value = boolean
    }

    private fun createAccount(data: CreateAccountData) {
        viewModelScope.launch {
            TestIdlingResource.increment()

            accountCreator.createAccount(data) {
                accounts.value = accountsAct(Unit)
            }

            TestIdlingResource.decrement()
        }
    }

    private fun findAccount(
        accounts: List<Account>,
        accountId: UUID?,
    ): Account? {
        return accountId?.let { uuid ->
            accounts.find { acc ->
                acc.id == uuid
            }
        }
    }
}
