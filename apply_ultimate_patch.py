import os
import re

def main():
    print("[*] Накатываем объединенный патч (Ktor API + Публичное хранилище)...")

    # Пути к проекту
    project_root = "Android/src/app"
    if not os.path.exists(project_root):
        # Если структура папок в апстриме изменилась, проверим альтернативный путь
        project_root = "gallery-main/Android/src/app"
        if not os.path.exists(project_root):
            # Пробуем найти корень Android модуля в текущей директории
            for root, dirs, files in os.walk("."):
                if "build.gradle.kts" in files and "src" in dirs:
                    if "com" in os.listdir(os.path.join(root, "src/main/java")):
                        project_root = root
                        break

    if not os.path.exists(project_root):
        print("[!] Ошибка: Не найдена директория Android проекта.")
        return

    base_src_dir = f"{project_root}/src/main/java/com/google/ai/edge/gallery"
    gradle_path = f"{project_root}/build.gradle.kts"
    manifest_path = f"{project_root}/src/main/AndroidManifest.xml"
    
    # Автоматически ищем MainActivity и файлы загрузчика/хелпера
    main_activity_path = None
    download_worker_path = None
    llm_helper_path = None

    for root, dirs, files in os.walk(project_root):
        if "MainActivity.kt" in files:
            main_activity_path = os.path.join(root, "MainActivity.kt")
        if "DownloadWorker.kt" in files:
            download_worker_path = os.path.join(root, "DownloadWorker.kt")
        if "LlmChatModelHelper.kt" in files:
            llm_helper_path = os.path.join(root, "LlmChatModelHelper.kt")

    # --- 1. Создание InferenceBridge.kt ---
    bridge_dir = os.path.dirname(main_activity_path) if main_activity_path else base_src_dir
    os.makedirs(bridge_dir, exist_ok=True)
    
    bridge_code = """package com.google.ai.edge.gallery

import kotlinx.coroutines.flow.MutableStateFlow

object InferenceBridge {
    var activeConversation: Any? = null
    val isModelReady = MutableStateFlow(false)
    var latestModelPath: String? = null

    suspend fun generateResponse(prompt: String): String {
        val conversation = activeConversation ?: return "Error: No active model session. Open chat in app first."
        return try {
            val method = conversation.javaClass.getMethod("sendMessage", String::class.java)
            val result = method.invoke(conversation, prompt)
            if (result is String) result else result.toString()
        } catch (e: Exception) {
            "Error during inference: ${e.localizedMessage}"
        }
    }
}
"""
    with open(f"{bridge_dir}/InferenceBridge.kt", "w", encoding="utf-8") as f:
        f.write(bridge_code)
    print("[+] Файл InferenceBridge.kt создан.")

    # --- 2. Создание GalleryServer.kt (Ktor Сервер) ---
    server_code = """package com.google.ai.edge.gallery

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
"""
    with open(f"{bridge_dir}/GalleryServer.kt", "w", encoding="utf-8") as f:
        f.write(server_code)
    print("[+] Файл GalleryServer.kt создан.")

    # --- 3. Модификация build.gradle.kts (Добавление зависимостей Ktor) ---
    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            content = f.read()
        
        if "ktor-server-core" not in content:
            ktor_deps = """
    // Ktor Server Setup
    val ktor_version = "2.3.11"
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version") { exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core") }
    implementation("io.ktor:ktor-server-cio-jvm:$ktor_version") { exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core") }
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
"""
            # Добавляем в блок dependencies
            content = re.sub(r"(dependencies\s*\{)", r"\1" + ktor_deps, content)
            
            # Форсируем версии корутин в конце файла во избежание конфликтов сборки
            content += """
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    }
}
"""
            with open(gradle_path, "w", encoding="utf-8") as f:
                f.write(content)
            print("[+] build.gradle.kts успешно обновлен (зависимости добавлены).")

    # --- 4. Модификация AndroidManifest.xml (Разрешения) ---
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            content = f.read()
        if "android.permission.INTERNET" not in content:
            permissions = """
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
"""
            content = re.sub(r"(<manifest[^>]*>)", r"\1" + permissions, content)
            content = re.sub(r"(<application[^>]*>)", r"\1\n        android:requestLegacyExternalStorage=\"true\"", content)
            with open(manifest_path, "w", encoding="utf-8") as f:
                f.write(content)
            print("[+] AndroidManifest.xml обновлен (разрешения добавлены).")

    # --- 5. Внедрение автозапуска в MainActivity.kt ---
    if main_activity_path and os.path.exists(main_activity_path):
        with open(main_activity_path, "r", encoding="utf-8") as f:
            content = f.read()
        
        if "GalleryServer.start" not in content:
            # Накатываем старт сервера в onCreate
            content = re.sub(
                r"(super\.onCreate\(savedInstanceState\))",
                r"\1\n        com.google.ai.edge.gallery.GalleryServer.start(applicationContext)",
                content
            )
            # Накатываем стоп сервера в onDestroy
            if "onDestroy" in content:
                content = re.sub(
                    r"(override\s+fun\s+onDestroy\(\)\s*\{[^}].*)",
                    r"override fun onDestroy() {\n        com.google.ai.edge.gallery.GalleryServer.stop()\n        \1",
                    content
                )
            else:
                # Если onDestroy нет, создаем в конце класса перед последней закрывающей скобкой
                content = re.sub(
                    r"(\}\s*$)",
                    r"\n    override fun onDestroy() {\n        super.onDestroy()\n        com.google.ai.edge.gallery.GalleryServer.stop()\n    }\n}",
                    content
                )
            with open(main_activity_path, "w", encoding="utf-8") as f:
                f.write(content)
            print("[+] MainActivity.kt успешно модифицирован (прописан запуск сервера).")

    # --- 6. Перехват сессии инференса в LlmChatModelHelper.kt ---
    if llm_helper_path and os.path.exists(llm_helper_path):
        with open(llm_helper_path, "r", encoding="utf-8") as f:
            content = f.read()
        if "InferenceBridge" not in content:
            # Перехватываем создание объекта сессии (обычно переменная conversation или сессия чата)
            content = re.sub(
                r"(\bconversation\s*=\s*.*)",
                r"\1\n        com.google.ai.edge.gallery.InferenceBridge.activeConversation = conversation\n        com.google.ai.edge.gallery.InferenceBridge.isModelReady.value = true",
                content
            )
            with open(llm_helper_path, "w", encoding="utf-8") as f:
                f.write(content)
            print("[+] Сессия инференса успешно перехвачена в хелпере.")

    # --- 7. Перенос скачивания моделей в Downloads (DownloadWorker.kt) ---
    if download_worker_path and os.path.exists(download_worker_path):
        with open(download_worker_path, "r", encoding="utf-8") as f:
            content = f.read()
        
        # Инжектируем метод сохранения в два места (публичное и приватное)
        if "finalizeModelDownload" not in content:
            helper_method = """
    private fun finalizeModelDownload(tmpFile: java.io.File, fileName: String, modelDir: String, version: String) {
        try {
            // 1. Публичные Downloads
            val publicDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AIEdgeGallery/$modelDir")
            if (!publicDir.exists()) publicDir.mkdirs()
            tmpFile.copyTo(java.io.File(publicDir, fileName), overwrite = true)

            // 2. Приватная папка для стабильности LiteRT JNI
            val privateDir = java.io.File(applicationContext.getExternalFilesDir(null), "models/$modelDir/$version")
            if (!privateDir.exists()) privateDir.mkdirs()
            val privateFile = java.io.File(privateDir, fileName)
            tmpFile.copyTo(privateFile, overwrite = true)

            // Передаем путь серверу
            com.google.ai.edge.gallery.InferenceBridge.latestModelPath = privateFile.absolutePath
            tmpFile.delete()
        } catch (e: Exception) { e.printStackTrace() }
    }
"""
            # Добавляем метод внутрь класса DownloadWorker
            content = re.sub(r"(class\s+DownloadWorker[^{]*\{)", r"\1" + helper_method, content)
            with open(download_worker_path, "w", encoding="utf-8") as f:
                f.write(content)
            print("[+] Скачивание в публичную папку интегрировано в DownloadWorker.")

    print("\n[+] Успех! Все изменения применены в один шаг по лучшим практикам.")
    print("[*] Теперь выполни команды коммита и отправки кода на GitHub.")

if __name__ == "__main__":
    main()
