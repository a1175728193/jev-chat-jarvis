package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * Soul (cn.soulapp.android).
 *
 * Nodes are NOT obfuscated: every kind of message is locatable by a stable id,
 * so no OCR fallback is needed on this app.
 *
 * Chat screen activity: cn.soulapp.android/.component.chat.ConversationActivity
 *
 * All of the below verified on a real device 2026-09-29, on both a 1:1
 * real-person chat and the built-in AI-friend chat — the tree is the same shape.
 *
 * Message kinds and how each is read:
 *
 *   text    id/content_text        carries text
 *   voice   id/audioContent        Soul's OWN speech-to-text, carries text
 *           id/voice_bubble        the bubble itself — content-desc only, no text
 *           id/tv_length           "4s" / "3s", same row as the bubble
 *   image   id/img_static          no text at all -> placeholder
 *
 * Voice, in detail:
 *   - Soul runs its own speech-to-text and puts the recognised words in a row
 *     BELOW the bubble (not inside it), under id/audioContent. Voice therefore
 *     arrives as text with no OCR and no ASR on our side.
 *   - The transcript is EXPANDED BY DEFAULT (all three voice messages in the
 *     first dump came with a transcript and a "点击收起" tail), so the normal
 *     path is the transcript.
 *   - If the user collapses it, id/audioContent DISAPPEARS from the tree
 *     (verified: a screen with one voice_bubble has zero audioContent). The
 *     bubble alone is then all we get, so it is reported as "[语音 4s]".
 *     That is why both ids are collected and then paired by position.
 *
 * Sender side: same rule as [QQAdapter] — by which edge of the row hugs which
 * avatar column, NOT by the centre point, because a long incoming message can
 * be wide enough to push its centre past mid-screen. Verified on all four row
 * kinds at width = 1080 with avatarEdge = 108:
 *
 *            row      x-range        side
 *   text     me       570-839        other  241-703
 *   voice    me       528-876        other  241-589
 *   image    me       695-876        other  204-385
 *   transcript me     570-834        other  246-738
 *
 * (`id/li_message_state` and `id/message_read` hang off MY messages only, so
 * they are an independent cross-check if the geometry rule ever gets shaky —
 * not used yet.)
 *
 * Two things on Soul's chat screen look like messages but are NOT, and both are
 * excluded for free by matching on ids alone:
 *   - "灵感回复推荐" (Soul's own suggested replies) — plain TextViews, no id
 *   - chrome such as "关注后可邀请通话" — plain TextView, no id
 * Timestamps are also safe: they have their own id (`id/timestamp`).
 * Recalled messages do not exist on Soul (it has delete only), and long
 * messages are never folded, so there is no "expand / show more" state to
 * handle either. The voice transcript's "点击收起" is the only collapsible
 * thing on the screen, and it is handled above.
 *
 * "In a chat window" = any message row OR the input box is present. Nothing
 * else counts, so the conversation list / 星球 / 广场 screens return null.
 */
class SoulAdapter : ChatAppAdapter {
    override val pkg = "cn.soulapp.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels

        /** Every captured message row, in the order the tree handed it to us. */
        val rows = ArrayList<Row>()
        /** Voice bubbles and their transcripts are paired after the walk. */
        val voiceBubbles = ArrayList<Row>()
        val transcripts = ArrayList<Row>()
        /** (top, "4s") for each voice-length label. */
        val lengths = ArrayList<Pair<Int, String>>()

        var title: String? = null
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            val b = Rect()
            when {
                id == BODY_ID && !text.isNullOrBlank() -> {
                    node.getBoundsInScreen(b)
                    rows.add(Row(b.top, b.left, b.right, text))
                }
                id == TRANSCRIPT_ID && !text.isNullOrBlank() -> {
                    node.getBoundsInScreen(b)
                    transcripts.add(Row(b.top, b.left, b.right, text))
                }
                id == VOICE_BUBBLE_ID -> {
                    node.getBoundsInScreen(b)
                    voiceBubbles.add(Row(b.top, b.left, b.right, VOICE_PLACEHOLDER))
                }
                id == LENGTH_ID && !text.isNullOrBlank() -> {
                    node.getBoundsInScreen(b)
                    lengths.add(b.top to text)
                }
                id == IMAGE_ID -> {
                    node.getBoundsInScreen(b)
                    rows.add(Row(b.top, b.left, b.right, IMAGE_PLACEHOLDER))
                }
                id == INPUT_ID -> hasInput = true
                id == TITLE_ID && title == null -> if (!text.isNullOrBlank()) title = text
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (rows.isEmpty() && voiceBubbles.isEmpty() && !hasInput) return null

        // Pair each voice bubble with the transcript that sits under it. The
        // surviving rows keep the bubble's geometry, so side stays the bubble's.
        val claimed = BooleanArray(transcripts.size)
        for (vb in voiceBubbles) {
            val at = transcripts.indices.firstOrNull { i ->
                !claimed[i] && transcripts[i].top > vb.top &&
                    transcripts[i].top - vb.top < TRANSCRIPT_GAP
            } ?: -1
            if (at >= 0) {
                claimed[at] = true
                val t = transcripts[at]
                rows.add(Row(vb.top, t.left, t.right, t.text))
            } else {
                val len = lengths.firstOrNull { kotlin.math.abs(it.first - vb.top) <= LENGTH_ROW_SLACK }?.second
                rows.add(Row(vb.top, vb.left, vb.right, if (len != null) "[语音 $len]" else VOICE_PLACEHOLDER))
            }
        }
        for (i in transcripts.indices) if (!claimed[i]) rows.add(transcripts[i])

        val firstRowTop = rows.minOfOrNull { it.top } ?: Int.MAX_VALUE
        if (title == null) title = findTitleInActionBar(root, firstRowTop, width, res)
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())

        val avatarEdge = (width * AVATAR_EDGE_RATIO).toInt()
        rows.sortBy { it.top }
        val msgs = rows.map { r ->
            val dl = kotlin.math.abs(r.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - r.right)
            Msg(if (dr < dl) "me" else "other", r.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BODY_ID = "cn.soulapp.android:id/content_text"

        /** Soul's own speech-to-text for a voice message. Present only while the
         *  transcript is expanded — see the class comment. */
        private const val TRANSCRIPT_ID = "cn.soulapp.android:id/audioContent"

        /** The voice bubble itself. Carries a content-desc ("语音消息") but never
         *  text, so it is only used when no transcript is available. */
        private const val VOICE_BUBBLE_ID = "cn.soulapp.android:id/voice_bubble"

        private const val LENGTH_ID = "cn.soulapp.android:id/tv_length"

        /** Image / sticker / card messages. No text and no content-desc. */
        private const val IMAGE_ID = "cn.soulapp.android:id/img_static"

        private const val TITLE_ID = "cn.soulapp.android:id/tv_title"
        private const val INPUT_ID = "cn.soulapp.android:id/et_sendmessage"

        /**
         * Soul's avatar column centre measured at ≈103/1080 ≈ 0.095. QQ's 0.13
         * also classifies correctly here (the error is symmetric on both sides),
         * but 0.10 sits closer to the real column.
         */
        private const val AVATAR_EDGE_RATIO = 0.10

        /** A transcript sits roughly one bubble-height below its bubble. */
        private const val TRANSCRIPT_GAP = 400

        /** tv_length shares the bubble's row — allow a couple of pixels of slop. */
        private const val LENGTH_ROW_SLACK = 40

        private const val VOICE_PLACEHOLDER = "[语音]"
        private const val IMAGE_PLACEHOLDER = "[图片]"
    }
}
