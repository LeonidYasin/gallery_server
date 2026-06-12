package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
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
     * Инициализация модели с автоматическим поиском .litertlm файла
     */
    fun initialize(context: Context, specificModelPath: String? = null): Boolean {
        this.context = context.applicationContext
        
        val modelPath = specificModelPath ?: findModelFile() ?: run {
            Log.e(TAG, "No .litertlm model file found in Download/AIEdgeGallery/")
            return false
        }
        
        latestModelPath = modelPath
        Log.i(TAG, "Initializing model from: $modelPath")
        
        return try {
            val model = Model.create(context, modelPath)
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
            Log.e(TAG, "No .litertlm files found in ${publicDir.absolutePath}")
            return null
        }
        
        // Выбираем самую большую модель (обычно она основная)
        val selected = litertlmFiles.maxByOrNull { it.length() } ?: litertlmFiles.first()
        Log.i(TAG, "Found ${litertlmFiles.size} model(s). Selected: ${selected.name} (${selected.length() / 1024 / 1024} MB)")
        
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
            throw RuntimeException("Inference failed: ${e.message}", e)
        }
    }
}
