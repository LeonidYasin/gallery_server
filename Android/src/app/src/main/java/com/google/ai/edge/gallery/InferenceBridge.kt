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
    
    var activeConversation: Any? = null  // Используем Any вместо Conversation
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null
    private var context: Context? = null
    
    /**
     * Инициализация модели через рефлексию (без прямой зависимости от LiteRT-LM)
     */
    fun initialize(context: Context, specificModelPath: String? = null): Boolean {
        this.context = context.applicationContext
        
        val modelPath = specificModelPath ?: findModelFile() ?: run {
            Log.e(TAG, "No .litertlm model file found")
            return false
        }
        
        latestModelPath = modelPath
        Log.i(TAG, "Initializing model from: $modelPath")
        
        return try {
            // Динамическая загрузка классов LiteRT-LM
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
    
    /**
     * Рекурсивный поиск .litertlm файла в /sdcard/Download/AIEdgeGallery/
     */
    private fun findModelFile(): String? {
        val publicDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), 
            "AIEdgeGallery"
        )
        
        if (!publicDir.exists()) {
            Log.e(TAG, "Public directory does not exist: ${publicDir.absolutePath}")
            return null
        }
        
        Log.d(TAG, "Scanning for .litertlm files in: ${publicDir.absolutePath}")
        
        val litertlmFiles = publicDir.walkTopDown()
            .filter { it.isFile && it.extension == "litertlm" }
            .toList()
        
        if (litertlmFiles.isEmpty()) {
            Log.e(TAG, "No .litertlm files found")
            return null
        }
        
        val selected = litertlmFiles.maxByOrNull { it.length() } ?: litertlmFiles.first()
        Log.i(TAG, "Found ${litertlmFiles.size} model(s). Selected: ${selected.absolutePath} (${selected.length() / 1024 / 1024} MB)")
        
        return selected.absolutePath
    }
    
    fun shutdown() {
        activeConversation = null
        isModelReady.value = false
        latestModelPath = null
        Log.i(TAG, "Model session shutdown")
    }
    
    suspend fun generateResponse(prompt: String): String {
        val conversation = activeConversation
        if (conversation == null) {
            throw IllegalStateException("No active model session. Call initialize() first.")
        }
        
        if (!isModelReady.value) {
            throw IllegalStateException("Model is not ready.")
        }
        
        return try {
            // Используем рефлексию для вызова sendMessage
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
                else -> result?.toString() ?: "Error: Empty response"
            }
        } catch (e: Exception) {
            throw RuntimeException("Inference failed: ${e.message}", e)
        }
    }
}
