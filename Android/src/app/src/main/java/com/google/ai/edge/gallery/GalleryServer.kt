import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.coroutines.*

class GalleryServer {
    private var server: CIOApplicationEngine? = null

    fun start() {
        // Запускаем в фоновом потоке
        GlobalScope.launch(Dispatchers.IO) {
            server = embeddedServer(CIO, port = 8080) {
                // Настраиваем поддержку JSON
                install(ContentNegotiation) {
                    json()
                }
                // Определяем маршруты
                routing {
                    get("/") {
                        call.respondText("Сервер галереи запущен успешно!")
                    }
                    get("/status") {
                        call.respond(mapOf("status" to "ok", "app" to "Gallery Server"))
                    }
                }
            }.start(wait = true)
        }
    }

    fun stop() {
        server?.stop(1000, 5000)
    }
}
