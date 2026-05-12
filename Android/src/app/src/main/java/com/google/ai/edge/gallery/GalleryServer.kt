package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GalleryServer(
    private val viewModel: ModelManagerViewModel,
    private val context: Context
) {

    private var server: ServerSocket? = null
    private var isRunning = false
    private val logLock = Any()

    companion object {
        private const val LOG_FILE_PREFIX = "gallery_server_log_"
    }

    private fun logToFileWithTime(logMessage: String) {
        synchronized(logLock) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val dateStr = timestamp.substring(0, 10)
                val logFile = File(getStorageDir(), "${LOG_FILE_PREFIX}$dateStr.txt")
                val entry = "[$timestamp] $logMessage\n"
                logFile.appendText(entry)
                Log.d("GalleryServer", entry)
            } catch (e: Exception) {
                Log.e("GalleryServer", "Logger failure: ${e.message}", e)
            }
        }
    }

    fun start() {
        try {
            server = ServerSocket(5000, 10, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            logToFileWithTime("Server started on port 5000")

            while (isRunning) {
                val socket = server?.accept() ?: break
                logToFileWithTime("Client connected: ${socket.inetAddress.hostAddress}")

                Thread {
                    handleClient(socket)
                }.start()
            }
        } catch (e: Exception) {
            logToFileWithTime("Failed to start server: ${e.message}")
            Log.e("GalleryServer", "Failed to start server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val request = StringBuilder()
            var line: String?
            var headersEnd = false
            var contentLength = 0

            while (input.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) {
                    headersEnd = true
                    continue
                }
                if (line!!.startsWith("Content-Length: ")) {
                    contentLength = line!!.substring("Content-Length: ".length).trim().toInt()
                }
                if (!headersEnd) {
                    request.append(line).append("\n")
                }
            }

            val body = CharArray(contentLength)
            if (contentLength > 0) {
                input.read(body, 0, contentLength)
            }
            val bodyString = String(body)

            logToFileWithTime("Request: $request")
            logToFileWithTime("Body: $bodyString")

            val response = processCommand(bodyString)

            val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    response

            output.write(httpResponse.toByteArray())
            output.flush()
        } catch (e: Exception) {
            logToFileWithTime("handleClient error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                logToFileWithTime("Socket close error: ${e.message}")
            }
        }
    }

    private fun processCommand(bodyString: String): String {
        return try {
            val gson = Gson()
            val requestObj = gson.fromJson(bodyString, JsonObject::class.java)
            val command = requestObj.get("command")?.asString ?: ""
            val message = requestObj.get("message")?.asString ?: ""
            val model = requestObj.get("model")?.asString ?: ""

            when (command) {
                "chat" -> processChat(message, model)
                "list" -> listModels()
                "status" -> getStatus(model)
                else -> {
                    if (message.isNotEmpty() && model.isNotEmpty()) {
                        processChat(message, model)
                    } else {
                        """{"error": "Unknown command. Use: chat, list, status"}"""
                    }
                }
            }
        } catch (e: Exception) {
            logToFileWithTime("processCommand exception: ${e.message}")
            """{"error": "Invalid JSON or command failed: ${e.message}"}"""
        }
    }

    private fun processChat(message: String, modelName: String): String {
        logToFileWithTime("Chat request: model=$modelName, message=$message")

        val model = viewModel.getModelByName(modelName)
        if (model == null) {
            return """{"error": "Model '$modelName' not found. Use 'list' command."}"""
        }

        val downloadStatus = viewModel.uiState.value.modelDownloadStatus[model.name]
        if (downloadStatus?.status != ModelDownloadStatusType.SUCCEEDED) {
            return """{"error": "Model '$modelName' is not downloaded."}"""
        }

        if (!model.isLlm) {
            return """{"error": "Model '$modelName' is not an LLM."}"""
        }

        val instance = model.instance as? LlmModelInstance
        if (instance == null) {
            return """{"error": "Model '$modelName' is not initialized. Please run it in the app first (tap 'Run')."}"""
        }

        return try {
            val latch = CountDownLatch(1)
            val responseText = StringBuilder()
            val errorText = StringBuilder()

            instance.conversation.sendMessageAsync(
                com.google.ai.edge.litertlm.Contents.of(
                    listOf(com.google.ai.edge.litertlm.Content.Text(message))
                ),
                object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(msg: com.google.ai.edge.litertlm.Message) {
                        responseText.append(msg.toString())
                    }

                    override fun onDone() {
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        errorText.append(throwable.message ?: "Unknown error")
                        latch.countDown()
                    }
                },
                emptyMap()
            )

            latch.await(120, TimeUnit.SECONDS)

            if (errorText.isNotEmpty()) {
                """{"error": "${errorText}"}"""
            } else {
                Gson().toJson(mapOf(
                    "response" to responseText.toString(),
                    "model" to modelName
                ))
            }
        } catch (e: Exception) {
            logToFileWithTime("Inference error: ${e.message}")
            """{"error": "Inference failed: ${e.message}"}"""
        }
    }

    private fun listModels(): String {
        val allModels = viewModel.getAllModels()
        val gson = Gson()
        val modelsArray = gson.toJsonTree(allModels.map { model ->
            val downloadStatus = viewModel.uiState.value.modelDownloadStatus[model.name]
            val initStatus = viewModel.uiState.value.modelInitializationStatus[model.name]
            mapOf(
                "name" to model.name,
                "displayName" to model.displayName.ifEmpty { model.name },
                "isLlm" to model.isLlm,
                "runtimeType" to model.runtimeType.name,
                "downloaded" to (downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED),
                "initialized" to (initStatus?.status.toString() == "INITIALIZED"),
                "url" to model.url,
                "sizeInBytes" to model.sizeInBytes
            )
        }).asJsonArray
        return gson.toJson(mapOf("models" to modelsArray))
    }

    private fun getStatus(modelName: String): String {
        if (modelName.isEmpty()) {
            val allModels = viewModel.getAllModels()
            val statuses = allModels.map { model ->
                val downloadStatus = viewModel.uiState.value.modelDownloadStatus[model.name]
                val initStatus = viewModel.uiState.value.modelInitializationStatus[model.name]
                mapOf(
                    "name" to model.name,
                    "downloaded" to (downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED),
                    "initialized" to (initStatus?.status.toString() == "INITIALIZED"),
                    "downloadedBytes" to (downloadStatus?.receivedBytes ?: 0),
                    "totalBytes" to (downloadStatus?.totalBytes ?: 0)
                )
            }
            return Gson().toJson(mapOf("models" to statuses))
        }

        val model = viewModel.getModelByName(modelName)
        if (model == null) {
            return """{"error": "Model '$modelName' not found"}"""
        }

        val downloadStatus = viewModel.uiState.value.modelDownloadStatus[model.name]
        val initStatus = viewModel.uiState.value.modelInitializationStatus[model.name]

        return Gson().toJson(mapOf(
            "name" to model.name,
            "displayName" to model.displayName.ifEmpty { model.name },
            "downloaded" to (downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED),
            "initialized" to (initStatus?.status.toString() == "INITIALIZED"),
            "downloadStatus" to downloadStatus?.status?.name,
            "initStatus" to initStatus?.status?.name,
            "receivedBytes" to (downloadStatus?.receivedBytes ?: 0),
            "totalBytes" to (downloadStatus?.totalBytes ?: 0),
            "error" to (downloadStatus?.errorMessage ?: "")
        ))
    }

    fun stop() {
        isRunning = false
        try {
            server?.close()
            logToFileWithTime("Server stopped")
        } catch (e: Exception) {
            logToFileWithTime("Server stop error: ${e.message}")
        }
    }

    private fun getStorageDir(): File {
        val baseDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val galleryDir = File(baseDir, "AIEdgeGallery")
        if (!galleryDir.exists()) {
            galleryDir.mkdirs()
        }
        return galleryDir
    }
}
