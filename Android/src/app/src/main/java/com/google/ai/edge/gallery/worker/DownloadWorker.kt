/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.*
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

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
                            connection.setRequestProperty("Authorization", "Bearer $accessToken")
                        }

                        val tmpFile = File(applicationContext.cacheDir, "${file.fileName}.$TMP_FILE_EXT")
                        val tmpFileBytes = tmpFile.length()
                        if (tmpFileBytes > 0) {
                            connection.setRequestProperty("Range", "bytes=${tmpFileBytes}-")
                            connection.setRequestProperty("Accept-Encoding", "identity")
                        }
                        connection.connect()
                        Log.d(TAG, "response code: ${connection.responseCode}")

                        if (
                            connection.responseCode != HttpURLConnection.HTTP_OK &&
                            connection.responseCode != HttpURLConnection.HTTP_PARTIAL
                        ) {
                            throw IOException("HTTP error code: ${connection.responseCode}")
                        }

                        val inputStream = connection.inputStream
                        val outputStream = FileOutputStream(tmpFile, tmpFileBytes > 0)

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
                                lastSetProgressTs = curTs
                            }
                        }
                        outputStream.close()
                        inputStream.close()

                        // 1. Сохраняем в публичную папку Downloads/AIEdgeGallery
                        saveFileToPublicDownloads(
                            fileName = file.fileName,
                            sourceFile = tmpFile,
                            modelDir = modelDir,
                            version = version
                        )

                        // 2. Копируем в приватную папку приложения (нужно для LiteRT-LM)
                        val privateDir = File(
                            applicationContext.getExternalFilesDir(null),
                            "${modelDir}/${version}"
                        )
                        if (!privateDir.exists()) privateDir.mkdirs()
                        val privateDest = File(privateDir, file.fileName)
                        tmpFile.copyTo(privateDest, overwrite = true)

                        tmpFile.delete()
                        Log.d(TAG, "Download done for ${file.fileName}")

                        // Разархивация zip (только в приватную папку)
                        if (isZip && unzippedDir != null) {
                            setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())
                            val destDir = File(privateDir, unzippedDir)
                            if (!destDir.exists()) destDir.mkdirs()

                            val unzipBuffer = ByteArray(4096)
                            val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(privateDest)))
                            var zipEntry: ZipEntry? = zipIn.nextEntry
                            while (zipEntry != null) {
                                val filePath = destDir.absolutePath + File.separator + zipEntry.name
                                if (!zipEntry.isDirectory) {
                                    FileOutputStream(filePath).use { bos ->
                                        var len: Int
                                        while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                                            bos.write(unzipBuffer, 0, len)
                                        }
                                    }
                                } else {
                                    File(filePath).mkdirs()
                                }
                                zipIn.closeEntry()
                                zipEntry = zipIn.nextEntry
                            }
                            zipIn.close()
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

    private fun saveFileToPublicDownloads(
        fileName: String,
        sourceFile: File,
        modelDir: String,
        version: String
    ) {
        val relativePath = "Download/AIEdgeGallery/${modelDir}/${version}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = applicationContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            )
            if (uri != null) {
                applicationContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                applicationContext.contentResolver.update(uri, contentValues, null, null)
            }
        } else {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AIEdgeGallery/${modelDir}/${version}"
            )
            if (!publicDir.exists()) publicDir.mkdirs()
            sourceFile.copyTo(File(publicDir, fileName), overwrite = true)
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

        val intent = Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
