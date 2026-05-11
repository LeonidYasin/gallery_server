package com.google.ai.edge.gallery

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
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverScope.launch {
            server = embeddedServer(CIO, port = 8080) {
                routing {
                    get("/") { call.respondText("Server is running") }

                    post("/generate") {
                        val prompt = call.receiveText()
                        val uiState = viewModel.uiState.value
                        val model = uiState.selectedModel
                        val instance = model.instance

                        if (instance != null) {
                            try {
                                // Используем рефлексию, чтобы вызвать метод генерации.
                                // Это самый безопасный способ, который не зависит от импортов SDK.
                                // В этом проекте метод обычно называется generateResponse или generate.
                                val method = instance.javaClass.methods.find { 
                                    it.name == "generateResponse" || it.name == "generate" 
                                }
                                
                                if (method != null) {
                                    // Вызываем метод асинхронно, если он suspend (через соответствующий хендлер)
                                    // или напрямую, если это обычный метод SDK.
                                    val response = if (method.isVarArgs) {
                                        method.invoke(instance, prompt)
                                    } else {
                                        method.invoke(instance, prompt)
                                    }
                                    
                                    call.respondText(response?.toString() ?: "Empty response")
                                } else {
                                    call.respondText("Method not found in model instance", status = HttpStatusCode.InternalServerError)
                                }
                            } catch (e: Exception) {
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
                        } else {
                            call.respondText("Model not initialized. Please load it in the app.", status = HttpStatusCode.BadRequest)
                        }
                    }
                }
            }
            server?.start(wait = true)
        }
    }

    fun stop() {
        server?.stop(1000, 5000)
    }
}
