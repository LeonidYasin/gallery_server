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

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // <-- CHANGED: Убираем фиксированный externalFilesDir, теперь корень будет из InputData
    private val fallbackStorageDir = context.getExternalFilesDir(null)
    // -->

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notificationId: Int = params.id.hashCode()

    init {
        if (!channelCreated) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                "Model Downloading",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Notifications for model downloading" }
            notificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    override suspend fun doWork(): Result {
        val fileUrl = inputData.getString(KEY_MODEL_URL)
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
        val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
        val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
        val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
        val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
        val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
        val extraDataFileNames =
            inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
        val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
        val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

        // <-- CHANGED: Получаем корневую папку, если передана. Иначе используем старую (fallback)
        val storageRootPath = inputData.getString(KEY_MODEL_STORAGE_ROOT)
        val storageRoot = if (storageRootPath != null) File(storageRootPath) else fallbackStorageDir
        // -->

        return withContext(Dispatchers.IO) {
            if (fileUrl == null || fileName == null) {
                Result.failure()
            } else {
                return@withContext try {
                    setForeground(createForegroundInfo(progress = 0, modelName = modelName))

                    val allFiles: MutableList<UrlAndFileName> = mutableListOf()
                    allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
                    for (index in extraDataFileUrls.indices) {
                        allFiles.add(
                            UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
                        )
                    }
                    Log.d(TAG, "About to download: $allFiles")

                    var downloadedBytes = 0L
                    val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
                    val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
                    for (file in allFiles) {
                        val url = URL(file.url)
                        val connection = url.openConnection() as HttpURLConnection
                        if (accessToken != null) {
                            Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
                            connection.setRequestProperty("Authorization", "Bearer $accessToken")
                        }

                        // <-- CHANGED: Формируем выходную папку на основе storageRoot
                        val outputDir = File(storageRoot, listOf(modelDir, version).joinToString(File.separator))
                        if (!outputDir.exists()) {
                            outputDir.mkdirs()
                        }

                        // <-- CHANGED: TMP-файл тоже в новой директории
                        val outputTmpFile = File(
                            storageRoot,
                            listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                                .joinToString(File.separator)
                        )
                        val outputFileBytes = outputTmpFile.length()
                        if (outputFileBytes > 0) {
                            Log.d(
                                TAG,
                                "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
                            )
                            connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
                            connection.setRequestProperty("Accept-Encoding", "identity")
                        }
                        connection.connect()
                        Log.d(TAG, "response code: ${connection.responseCode}")

                        if (
                            connection.responseCode == HttpURLConnection.HTTP_OK ||
                            connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                        ) {
                            val contentRange = connection.getHeaderField("Content-Range")
                            if (contentRange != null) {
                                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                                val byteRange = rangeParts[0].split("-")
                                val startByte = byteRange[0].toLong()
                                val endByte = byteRange[1].toLong()
                                Log.d(TAG, "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte")
                                downloadedBytes += startByte
                            } else {
                                Log.d(TAG, "Download starts from beginning.")
                            }
                        } else {
                            throw IOException("HTTP error code: ${connection.responseCode}")
                        }

                        val inputStream = connection.inputStream
                        val outputStream = FileOutputStream(outputTmpFile, true)

                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var lastSetProgressTs: Long = 0
                        var deltaBytes = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            deltaBytes += bytesRead

                            val curTs = System.currentTimeMillis()
                            if (curTs - lastSetProgressTs > 200) {
                                var bytesPerMs = 0f
                                if (lastSetProgressTs != 0L) {
                                    if (bytesReadSizeBuffer.size == 5) bytesReadSizeBuffer.removeAt(0)
                                    bytesReadSizeBuffer.add(deltaBytes)
                                    if (bytesReadLatencyBuffer.size == 5) bytesReadLatencyBuffer.removeAt(0)
                                    bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                                    deltaBytes = 0L
                                    bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                                }
                                var remainingMs = 0f
                                if (bytesPerMs > 0f && totalBytes > 0L) {
                                    remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                                }
                                setProgress(
                                    Data.Builder()
                                        .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                                        .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                        .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                                        .build()
                                )
                                setForeground(
                                    createForegroundInfo(
                                        progress = (downloadedBytes * 100 / totalBytes).toInt(),
                                        modelName = modelName,
                                    )
                                )
                                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                                lastSetProgressTs = curTs
                            }
                        }

                        outputStream.close()
                        inputStream.close()

                        val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
                        val originalFile = File(originalFilePath)
                        if (originalFile.exists()) {
                            originalFile.delete()
                        }
                        outputTmpFile.renameTo(originalFile)
                        Log.d(TAG, "Download done")

                        // <-- CHANGED: Разархивация в новое место
                        if (isZip && unzippedDir != null) {
                            setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

                            val destDir = File(
                                storageRoot,
                                listOf(modelDir, version, unzippedDir).joinToString(File.separator)
                            )
                            if (!destDir.exists()) {
                                destDir.mkdirs()
                            }

                            val unzipBuffer = ByteArray(4096)
                            // zipFilePath теперь строится относительно storageRoot
                            val zipFilePath = File(
                                storageRoot,
                                listOf(modelDir, version, fileName).joinToString(File.separator)
                            ).absolutePath
                            val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
                            var zipEntry: ZipEntry? = zipIn.nextEntry

                            while (zipEntry != null) {
                                val filePath = destDir.absolutePath + File.separator + zipEntry.name
                                if (!zipEntry.isDirectory) {
                                    val bos = FileOutputStream(filePath)
                                    bos.use { curBos ->
                                        var len: Int
                                        while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                                            curBos.write(unzipBuffer, 0, len)
                                        }
                                    }
                                } else {
                                    val dir = File(filePath)
                                    dir.mkdirs()
                                }
                                zipIn.closeEntry()
                                zipEntry = zipIn.nextEntry
                            }
                            zipIn.close()

                            // Удаляем оригинальный zip
                            File(zipFilePath).delete()
                        }
                    }
                    Result.success()
                } catch (e: IOException) {
                    Log.e(TAG, e.message, e)
                    Result.failure(
                        Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
                    )
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0)
    }

    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        var title = "Downloading model"
        if (modelName != null) {
            title = "Downloading \"$modelName\""
        }
        val content = "Downloading in progress: $progress%"

        val intent =
            Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val notification =
            NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setProgress(100, progress, false)
                .setContentIntent(pendingIntent)
                .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
