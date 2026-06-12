package com.google.ai.edge.gallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import java.lang.StringBuilder

object InferenceBridge {
    var activeConversation: Any? = null
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null

    suspend fun generateResponse(prompt: String): String {
        val conversation = activeConversation ?: return "Error: No active model session. Open chat in app first."
        
        return try {
            // Получаем метод sendMessage
            val method = conversation.javaClass.getMethod("sendMessage", String::class.java)
            val result = method.invoke(conversation, prompt)
            
            // Если это Flow (как и должно быть в LiteRT-LM), собираем его элементы в строку
            if (result is Flow<*>) {
                val stringBuilder = StringBuilder()
                (result as Flow<*>).collect { chunk ->
                    if (chunk != null) {
                        stringBuilder.append(chunk.toString())
                    }
                }
                stringBuilder.toString()
            } else if (result is String) {
                result
            } else {
                result?.toString() ?: "Error: Empty response from model"
            }
        } catch (e: Exception) {
            "Error during inference: ${e.localizedMessage ?: e.toString()}"
        }
    }
}
