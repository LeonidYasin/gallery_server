package com.google.ai.edge.gallery

import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.http.*
import kotlinx.coroutines.*
import com.google.ai.edge.litertlm.LlmInference
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

class GalleryServer(private val viewModel: ModelManagerViewModel) {
    private var server: CIOApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverScope.launch {
            server = embeddedServer(CIO, port = 8080) {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    // Простая проверка связи
                    get("/") {
                        call.respondText("Сервер Gallery запущен!")
                    }

                    // Маршрут для чата с Gemma
                    post("/generate") {
                                        post("/generate") {
                        val prompt = call.receiveText()
                        val uiState = viewModel.uiState.value
                        val modelInstance = uiState.selectedModel.instance

                        if (modelInstance is LlmInference) {
                            try {
                                // В актуальном SDK метод называется generate
                                val result = modelInstance.generate(prompt)
                                call.respondText(result ?: "Модель вернула пустой ответ")
                            } catch (e: Exception) {
                                call.respondText("Ошибка инференса: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
                        } else {
                            call.respondText(
                                "Ошибка: Модель не загружена в приложении.", 
                                status = HttpStatusCode.BadRequest
                            )
                        }
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

