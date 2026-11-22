package com.cvlvky.cloudroll.tg

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Immutable
import com.cvlvky.cloudroll.BuildConfig
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Prefs
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthenticationCodeType
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatPosition
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.FileType
import dev.g000sha256.tdl.dto.Minithumbnail
import dev.g000sha256.tdl.dto.NetworkTypeMobile
import dev.g000sha256.tdl.dto.NetworkTypeNone
import dev.g000sha256.tdl.dto.NetworkTypeOther
import dev.g000sha256.tdl.dto.NetworkTypeWiFi
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import dev.g000sha256.tdl.dto.File as TdFile

class TgError(val code: Int, override val message: String) : Exception(message)

fun <T> TdlResult<T>.orNull(): T? = (this as? TdlResult.Success<T>)?.result

fun <T> TdlResult<T>.orThrow(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TgError(code, message)
}

/** Where the sign-in stands, as the screens show it. */
sealed interface Auth {
    data object Starting : Auth
    data object NeedKeys : Auth
    data object Phone : Auth
    data class Code(val phone: String, val via: String, val length: Int, val canResend: Boolean) : Auth
    data class Qr(val link: String) : Auth
    data class Password(val hint: String) : Auth
    data object Email : Auth
    data class EmailCode(val pattern: String) : Auth
    data class Blocked(val reason: String) : Auth
    data object Ready : Auth
    data object LoggingOut : Auth
    data class Failed(val reason: String) : Auth
}

enum class ChatKind { PRIVATE, GROUP, CHANNEL, SECRET }

@Immutable
data class ChatInfo(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val userId: Long,
    val photo: TdFile?,
    val mini: Minithumbnail?,
    val order: Long,
    val isProtected: Boolean,
    val lastMessageId: Long,
)

/** The result of one message sent by the backup: its new id, or why it failed. */
data class SendResult(val oldMessageId: Long, val newMessageId: Long, val error: String?)

/**
 * The one Telegram connection of the app, through TDLib (Telegram's own library).
 * It signs in, keeps the chat list, and tells downloads and uploads how far they have got.
 */
object Tg {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    @Volatile
    var client: TdlClient? = null
        private set

    private val _auth = MutableStateFlow<Auth>(Auth.Starting)
    val auth: StateFlow<Auth> = _auth

    private val _me = MutableStateFlow<User?>(null)
    val me: StateFlow<User?> = _me

    private val _chats = MutableStateFlow<Map<Long, ChatInfo>>(emptyMap())
    val chats: StateFlow<Map<Long, ChatInfo>> = _chats

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private val fileFlows = ConcurrentHashMap<Int, MutableStateFlow<TdFile?>>()

    /** The backup listens here to learn when each upload has finished. */
    val sendResults = MutableSharedFlow<SendResult>(replay = 64, extraBufferCapacity = 64)

    @Volatile
    private var useQr = false

    @Volatile
    private var restartWhenClosed = false

    @Volatile
    private var allChatsLoaded = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        Prefs.init(appContext)
        Db.init(appContext)
        start()
    }

    /** Starts TDLib when the API keys are known. Safe to call any number of times. */
    @Synchronized
    fun start() {
        if (client != null) return
        if (Prefs.apiKeys() == null) {
            _auth.value = Auth.NeedKeys
            return
        }
        _auth.value = Auth.Starting
        val c = TdlClient.create()
        client = c
        scope.launch {
            runClient(c)
            synchronized(this@Tg) { if (client === c) client = null }
            if (restartWhenClosed) {
                restartWhenClosed = false
                start()
            }
        }
    }

    private suspend fun runClient(c: TdlClient) = coroutineScope {
        // Every update flow is subscribed before the first request, so nothing is missed.
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.authorizationStateUpdates.collect { onAuthState(c, it.authorizationState) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.fileUpdates.collect { u -> fileFlows[u.file.id]?.value = u.file }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.newChatUpdates.collect { u -> putChat(u.chat) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.chatTitleUpdates.collect { u -> editChat(u.chatId) { it.copy(title = u.title) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.chatPhotoUpdates.collect { u -> editChat(u.chatId) { it.copy(photo = u.photo?.small, mini = u.photo?.minithumbnail) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.chatPositionUpdates.collect { u -> applyPosition(u.chatId, u.position) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.chatLastMessageUpdates.collect { u ->
                editChat(u.chatId) { it.copy(lastMessageId = u.lastMessage?.id ?: it.lastMessageId) }
                u.positions.forEach { applyPosition(u.chatId, it) }
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.chatHasProtectedContentUpdates.collect { u -> editChat(u.chatId) { it.copy(isProtected = u.hasProtectedContent) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageSendSucceededUpdates.collect { u -> sendResults.emit(SendResult(u.oldMessageId, u.message.id, null)) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.messageSendFailedUpdates.collect { u -> sendResults.emit(SendResult(u.oldMessageId, 0L, u.error.message)) }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            c.connectionStateUpdates.collect { u -> _online.value = u.state is ConnectionStateReady }
        }
        // The first request wakes TDLib up; it answers with the sign-in state.
        c.setLogVerbosityLevel(newVerbosityLevel = 1)
    }

    private suspend fun onAuthState(c: TdlClient, s: AuthorizationState) {
        when (s) {
            is AuthorizationStateWaitTdlibParameters -> sendParameters(c)
            is AuthorizationStateWaitPhoneNumber -> {
                if (useQr) {
                    _auth.value = Auth.Starting
                    val r = c.requestQrCodeAuthentication(otherUserIds = LongArray(0))
                    if (r is TdlResult.Failure) {
                        useQr = false
                        _auth.value = Auth.Failed(humanize(r.message))
                    }
                } else {
                    _auth.value = Auth.Phone
                }
            }
            is AuthorizationStateWaitOtherDeviceConfirmation -> _auth.value = Auth.Qr(s.link)
            is AuthorizationStateWaitCode -> {
                val info = s.codeInfo
                _auth.value = Auth.Code(info.phoneNumber, describeCode(info.type), codeLength(info.type), info.nextType != null)
            }
            is AuthorizationStateWaitPassword -> _auth.value = Auth.Password(s.passwordHint)
            is AuthorizationStateWaitEmailAddress -> _auth.value = Auth.Email
            is AuthorizationStateWaitEmailCode -> _auth.value = Auth.EmailCode(s.codeInfo.emailAddressPattern)
            is AuthorizationStateWaitRegistration -> _auth.value = Auth.Blocked(
                "This phone number has no Telegram account yet. Create the account in the Telegram app first, then sign in here.",
            )
            is AuthorizationStateWaitPremiumPurchase -> _auth.value = Auth.Blocked(
                "Telegram asks this account to buy Premium before it can sign in on a new app. Sign in once from the official Telegram app, then try again.",
            )
            is AuthorizationStateReady -> {
                _auth.value = Auth.Ready
                onReady(c)
            }
            is AuthorizationStateLoggingOut -> _auth.value = Auth.LoggingOut
            is AuthorizationStateClosed -> {
                _chats.value = emptyMap()
                _me.value = null
                allChatsLoaded = false
            }
            else -> Unit
        }
    }

    private suspend fun sendParameters(c: TdlClient) {
        val keys = Prefs.apiKeys()
        if (keys == null) {
            _auth.value = Auth.NeedKeys
            return
        }
        val db = File(appContext.filesDir, "tdlib").apply { mkdirs() }
        val files = File(appContext.filesDir, "td_files").apply { mkdirs() }
        val r = c.setTdlibParameters(
            useTestDc = false,
            databaseDirectory = db.absolutePath,
            filesDirectory = files.absolutePath,
            databaseEncryptionKey = ByteArray(0),
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            useSecretChats = false,
            apiId = keys.id,
            apiHash = keys.hash,
            systemLanguageCode = Locale.getDefault().language.ifBlank { "en" },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            applicationVersion = "CloudRoll ${BuildConfig.VERSION_NAME}",
        )
        if (r is TdlResult.Failure) _auth.value = Auth.Failed(humanize(r.message))
    }

    private suspend fun onReady(c: TdlClient) {
        _me.value = c.getMe().orNull()
        tellNetworkType()
        c.loadChats(chatList = ChatListMain(), limit = 100)
        scope.launch { trimCache() }
    }

    // ---- Sign in ----

    fun chooseQr(qr: Boolean) {
        useQr = qr
        val c = client ?: return
        if (qr && _auth.value is Auth.Phone) {
            scope.launch {
                _auth.value = Auth.Starting
                val r = c.requestQrCodeAuthentication(otherUserIds = LongArray(0))
                if (r is TdlResult.Failure) {
                    useQr = false
                    _auth.value = Auth.Failed(humanize(r.message))
                }
            }
        } else if (!qr && _auth.value is Auth.Qr) {
            // TDLib only takes a phone number from its first question, so the sign-in starts over.
            restartClient()
        }
    }

    suspend fun submitPhone(phone: String): String? {
        val c = client ?: return "Not connected yet. Try again in a moment."
        val clean = phone.filter { it.isDigit() || it == '+' }
        val r = withTimeoutOrNull(60_000) { c.setAuthenticationPhoneNumber(phoneNumber = clean) }
            ?: return "Telegram did not answer. Check the internet connection and try again."
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    suspend fun submitCode(code: String): String? {
        val c = client ?: return "Not connected yet."
        val r = c.checkAuthenticationCode(code = code.trim())
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    suspend fun resendCode(): String? {
        val c = client ?: return "Not connected yet."
        val r = c.resendAuthenticationCode()
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    suspend fun submitPassword(password: String): String? {
        val c = client ?: return "Not connected yet."
        val r = c.checkAuthenticationPassword(password = password)
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    suspend fun submitEmail(email: String): String? {
        val c = client ?: return "Not connected yet."
        val r = c.setAuthenticationEmailAddress(emailAddress = email.trim())
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    suspend fun submitEmailCode(code: String): String? {
        val c = client ?: return "Not connected yet."
        val r = c.checkAuthenticationEmailCode(code = EmailAddressAuthenticationCode(code = code.trim()))
        return (r as? TdlResult.Failure)?.let { humanize(it.message) }
    }

    /** Saves new API keys and starts the sign-in again with them. */
    fun saveKeys(id: Int, hash: String) {
        Prefs.apiId.value = id
        Prefs.apiHash.value = hash.trim()
        if (client == null) File(appContext.filesDir, "tdlib").deleteRecursively()
        restartClient()
    }

    /** Closes TDLib and opens it again, back at the first sign-in question. */
    fun restartClient() {
        val c = client
        if (c == null) {
            start()
            return
        }
        restartWhenClosed = true
        _auth.value = Auth.Starting
        scope.launch { c.close() }
    }

    fun retry() = restartClient()

    fun logOut() {
        val c = client ?: return
        _auth.value = Auth.LoggingOut
        restartWhenClosed = true
        Prefs.clearAccountData()
        Db.clearBackups()
        Db.clearSaved()
        scope.launch { c.logOut() }
    }

    // ---- Chats ----

    private fun kindOf(type: ChatType): ChatKind = when (type) {
        is ChatTypePrivate -> ChatKind.PRIVATE
        is ChatTypeBasicGroup -> ChatKind.GROUP
        is ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.GROUP
        is ChatTypeSecret -> ChatKind.SECRET
        else -> ChatKind.GROUP
    }

    private fun putChat(chat: Chat) {
        val main = chat.positions.firstOrNull { it.list is ChatListMain }
        val info = ChatInfo(
            id = chat.id,
            title = chat.title,
            kind = kindOf(chat.type),
            userId = (chat.type as? ChatTypePrivate)?.userId ?: 0L,
            photo = chat.photo?.small,
            mini = chat.photo?.minithumbnail,
            order = main?.order ?: 0L,
            isProtected = chat.hasProtectedContent,
            lastMessageId = chat.lastMessage?.id ?: 0L,
        )
        _chats.update { it + (chat.id to info) }
    }

    private fun editChat(chatId: Long, change: (ChatInfo) -> ChatInfo) {
        _chats.update { map ->
            val old = map[chatId] ?: return@update map
            map + (chatId to change(old))
        }
    }

    private fun applyPosition(chatId: Long, position: ChatPosition) {
        if (position.list !is ChatListMain) return
        editChat(chatId) { it.copy(order = position.order) }
    }

    fun chat(chatId: Long): ChatInfo? = _chats.value[chatId]

    fun isSavedMessages(info: ChatInfo): Boolean {
        val myId = _me.value?.id ?: return false
        return info.kind == ChatKind.PRIVATE && info.userId == myId
    }

    fun chatTitle(chatId: Long): String {
        val info = _chats.value[chatId] ?: return "Chat"
        return if (isSavedMessages(info)) "Saved Messages" else info.title.ifBlank { "Chat" }
    }

    /** Loads every chat of the main list into the cache, a hundred at a time. */
    suspend fun loadAllChats() {
        if (allChatsLoaded) return
        val c = client ?: return
        repeat(30) {
            val r = c.loadChats(chatList = ChatListMain(), limit = 100)
            if (r is TdlResult.Failure) {
                if (r.code == 404) allChatsLoaded = true
                return
            }
        }
    }

    // ---- Files ----

    /** The live state of one file, updated as TDLib downloads or uploads it. */
    fun fileFlow(id: Int): MutableStateFlow<TdFile?> = fileFlows.getOrPut(id) { MutableStateFlow(null) }

    /** A path on this phone for [file], downloading it first if needed. Null when that failed. */
    suspend fun localPath(file: TdFile, priority: Int = 8): String? {
        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
            return file.local.path
        }
        val c = client ?: return null
        val f = c.downloadFile(fileId = file.id, priority = priority.coerceIn(1, 32), offset = 0L, limit = 0L, synchronous = true).orNull()
            ?: return null
        return if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) f.local.path else null
    }

    /** Starts a download and reports progress through [fileFlow]. */
    suspend fun startDownload(fileId: Int, priority: Int = 16): TdFile? {
        val c = client ?: return null
        val f = c.downloadFile(fileId = fileId, priority = priority.coerceIn(1, 32), offset = 0L, limit = 0L, synchronous = false).orNull()
        if (f != null) fileFlow(fileId).value = f
        return f
    }

    fun cancelDownload(fileId: Int, onlyIfPending: Boolean) {
        val c = client ?: return
        scope.launch { c.cancelDownloadFile(fileId = fileId, onlyIfPending = onlyIfPending) }
    }

    /** Waits until the file is fully on the phone, or gives up after [timeoutMs] without progress. */
    suspend fun awaitDownloaded(fileId: Int, timeoutMs: Long = 120_000): TdFile? {
        val flow = fileFlow(fileId)
        while (true) {
            val now = flow.value
            if (now != null && now.local.isDownloadingCompleted) return now
            if (now != null && !now.local.isDownloadingActive && !now.local.isDownloadingCompleted) {
                // Not running: ask again, once more.
                startDownload(fileId)
            }
            val next = withTimeoutOrNull(timeoutMs) { flow.first { it != null && it != now } } ?: return null
            if (next.local.isDownloadingCompleted) return next
        }
    }

    // ---- Network and storage ----

    fun tellNetworkType() {
        val c = client ?: return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> NetworkTypeNone()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTypeWiFi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTypeMobile()
            else -> NetworkTypeOther()
        }
        scope.launch { c.setNetworkType(type = type) }
    }

    /** Keeps TDLib's cache under the limit set in Settings, oldest files first. */
    suspend fun trimCache(clearAll: Boolean = false) {
        val c = client ?: return
        val limit = if (clearAll) 0L else Prefs.cacheLimitGb.value.toLong() * 1024L * 1024L * 1024L
        c.optimizeStorage(
            size = limit,
            ttl = if (clearAll) 0 else -1,
            count = if (clearAll) 0 else -1,
            immunityDelay = if (clearAll) 0 else -1,
            fileTypes = emptyArray<FileType>(),
            chatIds = LongArray(0),
            excludeChatIds = LongArray(0),
            returnDeletedFileStatistics = false,
            chatLimit = 0,
        )
    }

    suspend fun cacheSize(): Long {
        val c = client ?: return 0L
        return c.getStorageStatisticsFast().orNull()?.filesSize ?: 0L
    }

    /** Waits for the sign-in to settle. True when signed in. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        start()
        val settled = withTimeoutOrNull(timeoutMs) {
            _auth.first { it !is Auth.Starting }
        } ?: return false
        if (settled is Auth.Ready) {
            // Give TDLib a moment to finish opening its database.
            delay(300)
            return true
        }
        return false
    }

    // ---- Words ----

    private fun describeCode(type: AuthenticationCodeType): String = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> "Telegram sent the code as a message to your Telegram app on another device."
        is AuthenticationCodeTypeSms -> "Telegram sent the code by SMS."
        is AuthenticationCodeTypeSmsWord -> "Telegram sent a word by SMS. Enter that word."
        is AuthenticationCodeTypeSmsPhrase -> "Telegram sent a phrase by SMS. Enter that phrase."
        is AuthenticationCodeTypeCall -> "Telegram is calling you to read the code out."
        is AuthenticationCodeTypeMissedCall -> "Telegram will call you. The code is the last digits of the calling number."
        is AuthenticationCodeTypeFragment -> "The code is waiting for you on Fragment."
        else -> "Telegram sent you a login code."
    }

    private fun codeLength(type: AuthenticationCodeType): Int = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> type.length
        is AuthenticationCodeTypeSms -> type.length
        is AuthenticationCodeTypeCall -> type.length
        is AuthenticationCodeTypeMissedCall -> type.length
        is AuthenticationCodeTypeFragment -> type.length
        else -> 0
    }

    fun humanize(message: String): String {
        val m = message.uppercase(Locale.ROOT)
        val wait = Regex("(?:FLOOD_WAIT_|RETRY AFTER )(\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            wait != null -> "Telegram asks you to wait ${if (wait >= 120) "${wait / 60} minutes" else "$wait seconds"} before trying again."
            "PHONE_NUMBER_INVALID" in m -> "That phone number isn't valid. Include the country code, like +974 5555 1234."
            "PHONE_NUMBER_BANNED" in m -> "Telegram has banned this phone number."
            "PHONE_CODE_INVALID" in m -> "That code is wrong. Check it and try again."
            "PHONE_CODE_EXPIRED" in m -> "That code has expired. Ask for a new one."
            "PASSWORD_HASH_INVALID" in m -> "That password is wrong."
            "API_ID_INVALID" in m || "API_ID_PUBLISHED_FLOOD" in m ->
                "Telegram did not accept the API ID and hash. Check them on my.telegram.org and enter them again."
            "EMAIL_INVALID" in m -> "That email address isn't valid."
            "CODE_INVALID" in m -> "That code is wrong."
            "NETWORK" in m || "REQUEST ABORTED" in m -> "No connection to Telegram. Check the internet and try again."
            else -> message
        }
    }
}
