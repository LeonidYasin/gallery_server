package com.google.ai.edge.gallery

import kotlinx.coroutines.flow.MutableStateFlow

object InferenceBridge {
    var activeConversation: Any? = null
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null

    suspend fun generateResponse(prompt: String): String {
        val conversation = activeConversation ?: return "Error: No active model session. Open chat in app first."
        return try {
            val method = conversation.javaClass.getMethod("sendMessage", String::class.java)
            val result = method.invoke(conversation, prompt)
            if (result is String) result else result.toString()
        } catch (e: Exception) {
            "Error during inference: ${e.localizedMessage}"
        }
    }
}
