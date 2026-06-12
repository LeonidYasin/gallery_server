package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.LiteRtLm
import com.google.ai.edge.litertlm.Model
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.lang.StringBuilder

object InferenceBridge {
    private const val TAG = "InferenceBridge"
    
    var activeConversation: Conversation? = null
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null
    private var context: Context? = null
    
    /**
     * Инициализация модели из публичной папки Download/AIEdgeGallery/
     * Вызывается при старте сервера автоматически
     */
    fun initialize(context: Context, modelFileName: String = "gemma2-2b-it-cpu-int4.bin"): Boolean {
        this.context = context.applicationContext
        
        // 1. Получаем путь к модели через Model.getPath
        val modelPath = getModelPath(context, modelFileName)
        latestModelPath = modelPath
        
        Log.i(TAG, "Initializing model from: $modelPath")
        
        return try {
            // 2. Загружаем модель LiteRT-LM
            val model = Model.create(context, modelPath)
            
            // 3. Создаём сессию чата
            activeConversation = model.startConversation()
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
     * Получение пути к модели с приоритетом:
     * 1. Публичная папка Download/AIEdgeGallery/
     * 2. Приватная папка приложения (бэкап)
     */
    private fun getModelPath(context: Context, fileName: String): String {
        val privateDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        val privateFile = File(privateDir, fileName)
        
        val publicDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), 
            "AIEdgeGallery"
        )
        val publicFile = File(publicDir, fileName)
        
        // Если в публичной папке есть модель — копируем в приватную (если нужно)
        if (publicFile.exists()) {
            if (!privateFile.exists() || privateFile.length() != publicFile.length()) {
                try {
                    if (!privateFile.parentFile?.exists()!!) {
                        privateFile.parentFile?.mkdirs()
                    }
                    publicFile.copyTo(privateFile, overwrite = true)
                    Log.i(TAG, "📦 Model copied from public to private: ${privateFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy model from public to private", e)
                    return publicFile.absolutePath // Всё равно используем публичную
                }
            }
            return privateFile.absolutePath
        }
        
        // Если есть в приватной — используем её
        if (privateFile.exists()) {
            Log.i(TAG, "📁 Using private model: ${privateFile.absolutePath}")
            return privateFile.absolutePath
        }
        
        // Иначе ошибка
        throw IllegalStateException("Model not found in $publicDir or $privateDir. Please download model first.")
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
            val result = conversation.sendMessage(prompt)
            
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
            // Пробрасываем с полным stacktrace
            throw RuntimeException("Inference failed: ${e.message}", e)
        }
    }
}
