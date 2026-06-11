package com.google.ai.edge.gallery

import android.content.Context
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
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class PromptRequest(val prompt: String)

@Serializable
data class StatusResponse(val status: String, val modelReady: Boolean, val activePath: String?)

object GalleryServer {
    private const val TAG = "GalleryServer"
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(context: Context, port: Int = 8080) {
        if (serverEngine != null) return
        serverScope.launch {
            try {
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
                                call.respond(mapOf("error" to (e.localizedMessage ?: "Unknown error")))
                            }
                        }
                    }
                }.start(wait = false)
                Log.i(TAG, "Server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Server fail: ${e.localizedMessage}")
                try {
                    File(context.getExternalFilesDir(null), "server_error.log")
                        .writeText("Error: ${e.stackTraceToString()}")
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        serverScope.launch {
            serverEngine?.stop(1000, 2000)
            serverEngine = null
        }
    }
}
