package com.google.ai.edge.gallery

import android.util.Log
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

class GalleryServer(private val viewModel: ModelManagerViewModel) {
    private var server: CIOApplicationEngine? = null
    // Используем SupervisorJob, чтобы ошибка в сервере не убила всё приложение
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverScope.launch {
            try {
                server = embeddedServer(CIO, port = 8080) {
                    routing {
                        get("/") { call.respondText("Gallery AI Server is Active") }

                        post("/generate") {
                            try {
                                val prompt = call.receiveText()
                                val uiState = viewModel.uiState.value
                                val instance = uiState.selectedModel.instance

                                if (instance != null) {
                                    // Используем безопасный вызов через рефлексию
                                    val method = instance.javaClass.methods.find { 
                                        it.name == "generateResponse" || it.name == "generate" 
                                    }
                                    
                                    if (method != null) {
                                        val response = method.invoke(instance, prompt)
                                        call.respondText(response?.toString() ?: "Empty response")
                                    } else {
                                        call.respondText("Method not found", status = HttpStatusCode.InternalServerError)
                                    }
                                } else {
                                    call.respondText("Model not loaded", status = HttpStatusCode.BadRequest)
                                }
                            } catch (e: Exception) {
                                Log.e("GalleryServer", "Inference error: ${e.message}")
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
                        }
                    }
                }
                server?.start(wait = false) // wait = false, чтобы не блокировать корутину
                Log.d("GalleryServer", "Server started on port 8080")
            } catch (e: Exception) {
                Log.e("GalleryServer", "Could not start server: ${e.message}")
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
