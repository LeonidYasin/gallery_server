package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.lang.StringBuilder

object InferenceBridge {
    private const val TAG = "InferenceBridge"
    
    var activeConversation: Any? = null
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null
    private var context: Context? = null
    
    /**
     * Инициализирует модель из обнаруженного хранилища. 
     * Использует StoragePaths для надежного нахождения файла в public/private местах.
     */
    fun initialize(context: Context, specificModelPath: String? = null): Boolean {
        this.context = context.applicationContext
        
        // --- ИНТЕГРАЦИЯ С STORAGEPATHS КУДА НЕ ДОЛЖНО БЫТЬ НЕТ. ---
        val modelPath = specificModelPath ?: findModelFile() ?: run {
            Log.e(TAG, "No .litertlm model file found")
            return false
        }
        // ------------------------------------------
        
        latestModelPath = modelPath
        Log.i(TAG, "Initializing model from: $modelPath")
        
        return try {
            // Динамическая загрузка классов LiteRT-LM через рефлексию (старая рабочая логика)
            val modelClass = Class.forName("com.google.ai.edge.litertlm.Model")
            val createMethod = modelClass.getMethod("create", Context::class.java, String::class.java)
            val model = createMethod.invoke(null, context, modelPath)
            
            val startConversationMethod = model.javaClass.getMethod("startConversation")
            activeConversation = startConversationMethod.invoke(model)
            
            isModelReady.value = true
            Log.i(TAG, "✅ Model initialized successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize model", e)
            isModelReady.value = false
            activeConversation = null
            false
        }
    }

    private fun findModelFile(): String? {
        // Делегируем поиск StoragePaths. Это гарантирует, что мы найдем файл 
        // независимо от того, в какой из двух директорий он находится.
        val file = StoragePaths.findModelFile(context?.applicationContext ?: return null)
        return if (file != null) file.absolutePath else null
    }

    fun shutdown() {
        activeConversation = null
        isModelReady.value = false
        latestModelPath = null
        Log.i(TAG, "Model session shutdown")
    }

    // Это suspend функция должна быть вызвана через CoroutineScope в Activity или ViewModel
    suspend fun generateResponse(prompt: String): String {
        val conversation = activeConversation
        if (conversation == null) {
            throw IllegalStateException("No active model session. Call initialize() first.")
        }
        
        if (!isModelReady.value) {
            throw IllegalStateException("Model is not ready.")
        }
        
        return try {
            val sendMessageMethod = conversation.javaClass.getMethod("sendMessage", String::class.java)
            val result = sendMessageMethod.invoke(conversation, prompt)
            
            when (result) {
                is Flow<*> -> {
                    val stringBuilder = StringBuilder()
                    result.collect { chunk ->
                        if (chunk != null) {
                            stringBuilder.append(chunk.toString())
                        }
                    }
                    stringBuilder.toString()
                }
                is String -> result
                else -> result?.toString() ?: "Error: Empty response from model"
            }
        } catch (e: Exception) {
             // Логируем в публичный и приватный лог, используя нашу систему!
            StoragePaths.logE(context!!, TAG, "Ошибка генерации ответа", e)
            throw RuntimeException("Failed to generate response from model", e)
        }
    }