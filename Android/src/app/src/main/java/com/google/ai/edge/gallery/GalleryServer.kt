package com.google.ai.edge.gallery

import android.content.Context
import android.os.Environment
import android.util.Log
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class PromptRequest(val prompt: String)

@Serializable
data class StatusResponse(val status: String, val modelReady: Boolean, val activePath: String?)

sealed class ServerStatus {
    object Idle : ServerStatus()
    object Running : ServerStatus()
    data class Error(val message: String, val stackTrace: String) : ServerStatus()
}

object GalleryServer {
    private const val TAG = "GalleryServer"
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Имя файла модели (можно изменить под свою модель)
    private const val MODEL_FILE_NAME = "gemma2-2b-it-cpu-int4.bin"

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Idle)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    fun start(context: Context, port: Int = 8080) {
        if (serverEngine != null) {
            Log.d(TAG, "Server is already initialized")
            return
        }

        _status.value = ServerStatus.Idle

        serverScope.launch {
            try {
                // 🔥 КРИТИЧНО: Инициализируем модель ДО старта сервера
                Log.i(TAG, "Initializing model from Download/AIEdgeGallery/...")
                val initSuccess = InferenceBridge.initialize(context, MODEL_FILE_NAME)
                
                if (!initSuccess) {
                    Log.e(TAG, "Failed to initialize model. Server will start but /generate will fail.")
                } else {
                    Log.i(TAG, "✅ Model loaded successfully from: ${InferenceBridge.latestModelPath}")
                }
                
                Log.d(TAG, "Starting Ktor embeddedServer on port $port...")
                serverEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/") {
                            call.respondText("AI Edge Gallery Server | Model ready: ${InferenceBridge.isModelReady.value}")
                        }
                        get("/status") {
                            val isReady = InferenceBridge.isModelReady.value
                            val path = InferenceBridge.latestModelPath ?: "None"
                            call.respond(StatusResponse("running", isReady, path))
                        }
                        post("/generate") {
                            try {
                                // Проверяем, готова ли модель
                                if (!InferenceBridge.isModelReady.value) {
                                    call.respond(mapOf("error" to "Model not ready. Check /status endpoint."))
                                    return@post
                                }
                                
                                val request = call.receive<PromptRequest>()
                                Log.d(TAG, "Received prompt: ${request.prompt.take(100)}...")
                                
                                val responseText = InferenceBridge.generateResponse(request.prompt)
                                call.respond(mapOf("response" to responseText))
                            } catch (e: Exception) {
                                // Полный stacktrace для диагностики
                                val fullStackTrace = e.stackTraceToString()
                                Log.e(TAG, "Inference error", e)
                                call.respond(mapOf("error" to fullStackTrace))
                            }
                        }
                    }
                }

                serverEngine?.start(wait = false)
                _status.value = ServerStatus.Running
                Log.i(TAG, "✅ Server successfully started on port $port")

            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Unknown error"
                val stackTrace = e.stackTraceToString()

                _status.value = ServerStatus.Error(errorMsg, stackTrace)
                Log.e(TAG, "Server fail: $errorMsg", e)

                // Запись лога в общедоступную папку Загрузки
                try {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val logFile = File(downloadDir, "gallery_server_error.log")
                    logFile.writeText("=== KTOR SERVER CRASH LOG ===\nTimestamp: ${java.util.Date()}\nError: $errorMsg\n\n$stackTrace")
                    Log.i(TAG, "Crash log saved to ${logFile.absolutePath}")
                } catch (fsException: Exception) {
                    Log.e(TAG, "Failed to write log file", fsException)
                }
            }
        }
    }

    fun stop() {
        serverScope.launch {
            serverEngine?.stop(1000, 2000)
            serverEngine = null
            InferenceBridge.shutdown()
            _status.value = ServerStatus.Idle
            Log.i(TAG, "Server stopped")
        }
    }
}
