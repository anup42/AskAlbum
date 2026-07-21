package com.askphotos.android

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bridges LiteRT-LM's blocking conversation API to structured coroutine cancellation. */
internal suspend fun Engine.generateTextCancellable(
    config: ConversationConfig,
    prompt: String,
    extraContext: Map<String, Any> = emptyMap(),
): String = generateTextCancellable(config, Contents.of(prompt), extraContext)

/** Cancelling the caller interrupts native decoding before the conversation is closed. */
internal suspend fun Engine.generateTextCancellable(
    config: ConversationConfig,
    contents: Contents,
    extraContext: Map<String, Any> = emptyMap(),
): String {
    currentCoroutineContext().ensureActive()
    return suspendCancellableCoroutine { continuation ->
        val conversation = createConversation(config)
        continuation.invokeOnCancellation {
            runCatching { conversation.cancelProcess() }
        }
        if (!continuation.isActive) {
            runCatching { conversation.close() }
            return@suspendCancellableCoroutine
        }
        try {
            val message = conversation.sendMessage(contents, extraContext = extraContext)
            val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (continuation.isActive) continuation.resume(text)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            runCatching { conversation.close() }
        }
    }
}
