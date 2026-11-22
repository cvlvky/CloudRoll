package com.cvlvky.cloudroll.tg

import com.cvlvky.cloudroll.data.Prefs
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.SearchMessagesFilter
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterPhoto
import dev.g000sha256.tdl.dto.SearchMessagesFilterPhotoAndVideo
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import dev.g000sha256.tdl.dto.SearchMessagesFilterVoiceNote

/** A page of results and where the next one starts (null when there is no more). */
data class Page(val items: List<TgMedia>, val next: String?)

/** What to list: the Telegram search filter behind each tab and chip. */
enum class MediaFilter(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), MUSIC("Music"), VOICE("Voice"), FILES("Files");

    fun tdFilter(): SearchMessagesFilter = when (this) {
        ALL -> SearchMessagesFilterPhotoAndVideo()
        PHOTOS -> SearchMessagesFilterPhoto()
        VIDEOS -> SearchMessagesFilterVideo()
        MUSIC -> SearchMessagesFilterAudio()
        VOICE -> SearchMessagesFilterVoiceNote()
        FILES -> SearchMessagesFilterDocument()
    }
}

data class Sponsored(val chatId: Long, val messageId: Long, val title: String, val text: String, val button: String, val url: String, val recommended: Boolean)

object Repo {
    private const val PAGE = 60

    /** Media across every chat, newest first. Hidden chats are left out. */
    suspend fun searchAll(filter: MediaFilter, query: String, cursor: String): Page {
        val c = Tg.client ?: throw TgError(0, "Not connected")
        var offset = cursor
        val out = ArrayList<TgMedia>()
        // A page made only of hidden chats would stop the list, so keep going for a few pages.
        repeat(4) {
            val r = c.searchMessages(
                chatList = ChatListMain(),
                query = query,
                offset = offset,
                limit = PAGE,
                filter = filter.tdFilter(),
                chatTypeFilter = null,
                minDate = 0,
                maxDate = 0,
            ).orThrow()
            r.messages.forEach { m -> if (!Prefs.isHidden(m.chatId)) m.toMedia()?.let(out::add) }
            offset = r.nextOffset
            if (offset.isEmpty() || out.size >= 12) return Page(out, offset.ifEmpty { null })
        }
        return Page(out, offset.ifEmpty { null })
    }

    /** Media in one chat, newest first. The cursor is the message id to continue from. */
    suspend fun searchChat(chatId: Long, filter: MediaFilter, query: String, cursor: String): Page {
        val c = Tg.client ?: throw TgError(0, "Not connected")
        val from = cursor.toLongOrNull() ?: 0L
        val r = c.searchChatMessages(
            chatId = chatId,
            topicId = null,
            query = query,
            senderId = null,
            fromMessageId = from,
            offset = 0,
            limit = PAGE,
            filter = filter.tdFilter(),
        ).orThrow()
        val items = r.messages.mapNotNull { it.toMedia() }
        val next = r.nextFromMessageId
        return Page(items, if (next == 0L || r.messages.isEmpty()) null else next.toString())
    }

    /** Every item of one kind in a chat, for "Save all" and restoring a backup. */
    suspend fun allInChat(chatId: Long, filter: MediaFilter, max: Int = 5000): List<TgMedia> {
        val out = ArrayList<TgMedia>()
        var cursor = ""
        while (out.size < max) {
            val p = searchChat(chatId, filter, "", cursor)
            out.addAll(p.items)
            cursor = p.next ?: break
        }
        return out
    }

    suspend fun media(chatId: Long, messageId: Long): TgMedia? {
        val c = Tg.client ?: return null
        return c.getMessage(chatId = chatId, messageId = messageId).orNull()?.toMedia()
    }

    /** A t.me link that opens this message in Telegram, or null when Telegram has none. */
    suspend fun messageLink(chatId: Long, messageId: Long): String? {
        val c = Tg.client ?: return null
        val r = c.getMessageLink(
            chatId = chatId,
            messageId = messageId,
            mediaTimestamp = 0,
            checklistTaskId = 0,
            pollOptionId = "",
            forAlbum = false,
            inMessageThread = false,
        )
        return (r as? TdlResult.Success)?.result?.link
    }

    /** A link that opens a chat in the Telegram app. */
    suspend fun chatLink(chatId: Long): String? {
        val info = Tg.chat(chatId) ?: return null
        if (info.kind == ChatKind.PRIVATE) return "tg://user?id=${info.userId}"
        if (info.lastMessageId != 0L) messageLink(chatId, info.lastMessageId)?.let { return it }
        return null
    }

    /** The sponsored message Telegram wants shown in a channel, if any. */
    suspend fun sponsored(chatId: Long): Sponsored? {
        val c = Tg.client ?: return null
        val list = c.getChatSponsoredMessages(chatId = chatId).orNull() ?: return null
        val m = list.messages.firstOrNull() ?: return null
        val text = (m.content as? MessageText)?.text?.text.orEmpty()
        return Sponsored(chatId, m.messageId, m.title, text, m.buttonText, m.sponsor.url, m.isRecommended)
    }

    suspend fun sponsoredViewed(s: Sponsored) {
        Tg.client?.viewMessages(chatId = s.chatId, messageIds = longArrayOf(s.messageId), source = null, forceRead = false)
    }

    suspend fun sponsoredClicked(s: Sponsored) {
        Tg.client?.clickChatSponsoredMessage(chatId = s.chatId, messageId = s.messageId, isMediaClick = false, fromFullscreen = false)
    }
}
