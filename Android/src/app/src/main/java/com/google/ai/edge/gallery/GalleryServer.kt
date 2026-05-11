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
import com.google.ai.edge.litertlm.LlmInference // Убедись, что путь совпадает с твоим проектом
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
                    get("/") {
                        call.respondText("Сервер Gallery запущен!")
                    }

                    // Явно указываем Route, чтобы избежать ошибки с implicit receiver
                    this@routing.post("/generate") {
                        val prompt = call.receiveText()
                        val uiState = viewModel.uiState.value
                        val modelInstance = uiState.selectedModel.instance

                        // Проверяем тип через полное имя класса, если обычный импорт барахлит
                        if (modelInstance is com.google.ai.edge.litertlm.LlmInference) {
                            try {
                                // В новом SDK метод может называться generate или generateResponse
                                // Если 'generate' не находит, попробуй 'generateResponse'
                                val result = modelInstance.generate(prompt)
                                call.respondText(result ?: "Пустой ответ")
                            } catch (e: Exception) {
                                call.respondText("Ошибка: ${e.message}", status = HttpStatusCode.InternalServerError)
                            }
                        } else {
                            call.respondText("Модель не загружена", status = HttpStatusCode.BadRequest)
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
