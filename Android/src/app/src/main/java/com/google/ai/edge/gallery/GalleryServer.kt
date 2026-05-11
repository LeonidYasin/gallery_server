/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

class GalleryServer(
  private val viewModel: ModelManagerViewModel,
  private val context: Context // <-- Добавлен контекст для инференса
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

      logToFileWithTime("Received request: $request")
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
      return """{"error": "Model '$modelName' not found. Use 'list' command to see available models."}"""
    }

    val downloadStatus = viewModel.uiState.value.modelDownloadStatus[model.name]
    if (downloadStatus?.status != ModelDownloadStatusType.SUCCEEDED) {
      return """{"error": "Model '$modelName' is not downloaded. Download it first in the app."}"""
    }

    if (!model.isLlm) {
      return """{"error": "Model '$modelName' is not an LLM. Only LLMs can chat."}"""
    }

    return try {
      var responseText = ""
      
      runBlocking {
        val llmTask = viewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
        if (llmTask == null) {
          responseText = """{"error": "LLM Chat task not found."}"""
          return@runBlocking
        }

        // Проверяем, инициализирована ли модель (загружена в память)
        val needInit = viewModel.uiState.value.modelInitializationStatus[model.name]?.status !=
          ModelInitializationStatusType.INITIALIZED

        if (needInit) {
          logToFileWithTime("Model $modelName not initialized. Initializing...")
          val initLock = Object()
          var initComplete = false
          var initError: String? = null

          viewModel.initializeModel(
            context = context,
            task = llmTask,
            model = model,
            force = false
          ) {
            initComplete = true
            synchronized(initLock) {
              initLock.notify()
            }
          }

          synchronized(initLock) {
            try {
              initLock.wait(120000) // Ждём до 2 минут
            } catch (e: InterruptedException) {
              initError = "Initialization interrupted"
            }
          }

          if (!initComplete && initError == null) {
            initError = "Initialization timed out (120s)"
          }
          if (initError != null) {
            responseText = """{"error": "Failed to initialize model: $initError"}"""
            return@runBlocking
          }
          
          logToFileWithTime("Model $modelName initialized successfully")
        }

        // Выполняем инференс
        val chatTask = viewModel.getCustomTaskByTaskId(BuiltInTaskId.LLM_CHAT)
        if (chatTask == null) {
          responseText = """{"error": "LLM Chat custom task not found."}"""
          return@runBlocking
        }

        val chatMessage = Contents.of(message)
        chatTask.runModelFn?.invoke(
          context = context,
          model = model,
          coroutineScope = CoroutineScope(Dispatchers.IO),
          onProgress = { result -> responseText = result },
          onDone = { result -> responseText = result },
          message = chatMessage
        ) ?: run {
          responseText = """{"error": "runModelFn not available for this task"}"""
        }
      }
      
      responseText
    } catch (e: Exception) {
      logToFileWithTime("Chat error: ${e.message}")
      """{"error": "Chat failed: ${e.message}"}"""
    }
  }

  private fun listModels(): String {
    val allModels = viewModel.getAllModels()
    val gson = Gson()
    val modelsArray = gson.toJsonTree(allModels.map { model ->
      mapOf(
        "name" to model.name,
        "displayName" to model.displayName.ifEmpty { model.name },
        "isLlm" to model.isLlm,
        "runtimeType" to model.runtimeType.name,
        "downloaded" to (viewModel.uiState.value.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED),
        "initialized" to (viewModel.uiState.value.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED),
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
        mapOf(
          "name" to model.name,
          "downloaded" to (viewModel.uiState.value.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED),
          "initialized" to (viewModel.uiState.value.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED),
          "downloadedBytes" to (viewModel.uiState.value.modelDownloadStatus[model.name]?.receivedBytes ?: 0),
          "totalBytes" to (viewModel.uiState.value.modelDownloadStatus[model.name]?.totalBytes ?: 0)
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
      "initialized" to (initStatus?.status == ModelInitializationStatusType.INITIALIZED),
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
