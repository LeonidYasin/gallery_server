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
                // 🔥 АВТОМАТИЧЕСКИЙ ПОИСК МОДЕЛИ в Download/AIEdgeGallery/
                Log.i(TAG, "Searching for .litertlm model in Download/AIEdgeGallery/...")
                val initSuccess = InferenceBridge.initialize(context)
                
                if (!initSuccess) {
                    Log.e(TAG, "❌ No model found! Please download model via app first.")
                    _status.value = ServerStatus.Error("Model not found", "No .litertlm file in Download/AIEdgeGallery/")
                    return@launch
                }
                
                Log.i(TAG, "✅ Model loaded: ${InferenceBridge.latestModelPath}")
                
                Log.d(TAG, "Starting Ktor embeddedServer on port $port...")
                serverEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/") {
                            call.respondText("AI Edge Gallery Server | Model: ${InferenceBridge.latestModelPath?.substringAfterLast("/") ?: "none"}")
                        }
                        get("/status") {
                            val isReady = InferenceBridge.isModelReady.value
                            val path = InferenceBridge.latestModelPath ?: "None"
                            call.respond(StatusResponse("running", isReady, path))
                        }
                        post("/generate") {
                            try {
                                if (!InferenceBridge.isModelReady.value) {
                                    call.respond(mapOf("error" to "Model not ready. Check /status endpoint."))
                                    return@post
                                }
                                
                                val request = call.receive<PromptRequest>()
                                Log.d(TAG, "Generating response for: ${request.prompt.take(50)}...")
                                
                                val responseText = InferenceBridge.generateResponse(request.prompt)
                                call.respond(mapOf("response" to responseText))
                            } catch (e: Exception) {
                                val fullStackTrace = e.stackTraceToString()
                                Log.e(TAG, "Inference error", e)
                                call.respond(mapOf("error" to fullStackTrace))
                            }
                        }
                    }
                }

                serverEngine?.start(wait = false)
                _status.value = ServerStatus.Running
                Log.i(TAG, "✅ Server started on port $port")

            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Unknown error"
                val stackTrace = e.stackTraceToString()
                _status.value = ServerStatus.Error(errorMsg, stackTrace)
                Log.e(TAG, "Server failed", e)

                try {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val logFile = File(downloadDir, "gallery_server_error.log")
                    logFile.writeText("=== KTOR SERVER ERROR ===\n${java.util.Date()}\n$errorMsg\n\n$stackTrace")
                } catch (fsException: Exception) {
                    Log.e(TAG, "Failed to write log", fsException)
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
