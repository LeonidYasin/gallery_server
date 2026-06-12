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
                Log.d(TAG, "Starting Ktor embeddedServer on port $port...")
                serverEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/status") {
                            val isReady = InferenceBridge.isModelReady.value
                            val path = InferenceBridge.latestModelPath ?: "None"
                            call.respond(StatusResponse("running", isReady, path))
                        }
                        post("/generate") {
                            try {
                                val request = call.receive<PromptRequest>()
                                val responseText = InferenceBridge.generateResponse(request.prompt)
                                call.respond(mapOf("response" to responseText))
                            } catch (e: Exception) {
                                // Возвращаем полный StackTrace ошибки инференса в JSON, чтобы прочитать его из Termux
                                val fullStackTrace = e.stackTraceToString()
                                call.respond(mapOf("response" to "Error during inference:\n$fullStackTrace"))
                            }
                        }
                    }
                }
                
                serverEngine?.start(wait = false)
                _status.value = ServerStatus.Running
                Log.i(TAG, "Server successfully started on port $port")
                
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Unknown error"
                val stackTrace = e.stackTraceToString()
                
                _status.value = ServerStatus.Error(errorMsg, stackTrace)
                Log.e(TAG, "Server fail: $errorMsg")
                
                // Запись лога в общедоступную папку Загрузки (/sdcard/Download)
                try {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val logFile = File(downloadDir, "gallery_server_error.log")
                    logFile.writeText("=== KTOR SERVER CRASH LOG ===\nTimestamp: ${java.util.Date()}\nError: $errorMsg\n\n$stackTrace")
                    Log.i(TAG, "Crash log successfully saved to public Downloads folder")
                } catch (fsException: Exception) {
                    Log.e(TAG, "Failed to write public log file: ${fsException.localizedMessage}")
                }
            }
        }
    }

    fun stop() {
        serverScope.launch {
            serverEngine?.stop(1000, 2000)
            serverEngine = null
            _status.value = ServerStatus.Idle
        }
    }
}
