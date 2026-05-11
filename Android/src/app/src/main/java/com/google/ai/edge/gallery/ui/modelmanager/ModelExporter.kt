package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "ModelExporter"

/**
 * Утилита для экспорта/импорта моделей между приватной папкой приложения
 * (getExternalFilesDir) и публичной папкой, выбранной пользователем через SAF.
 */
object ModelExporter {

    /**
     * Экспортирует модель в папку, на которую указывает [folderUri].
     *
     * @param context Контекст приложения.
     * @param folderUri URI выбранной пользователем папки (DocumentFile).
     * @param modelName Нормализованное имя модели (папка в приватном хранилище).
     * @return true если хотя бы один файл был скопирован.
     */
    suspend fun exportModel(
        context: Context,
        folderUri: Uri,
        modelName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val privateDir = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("External files dir is null")
            val modelDir = File(privateDir, modelName)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                return@withContext Result.failure(Exception("Model directory not found: $modelName"))
            }

            val targetFolder = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw Exception("Cannot open target folder")
            // Создаём подпапку с именем модели в публичной папке
            var modelSubFolder = targetFolder.findFile(modelName)
            if (modelSubFolder == null) {
                modelSubFolder = targetFolder.createDirectory(modelName)
                    ?: throw Exception("Cannot create directory $modelName")
            }

            copyDirectory(modelDir, modelSubFolder, context)
            Log.d(TAG, "Exported model $modelName successfully")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed for $modelName", e)
            Result.failure(e)
        }
    }

    /**
     * Импортирует модель из выбранной пользователем папки обратно в приватное хранилище.
     *
     * @param context Контекст.
     * @param folderUri URI папки с моделью.
     * @param modelName Имя модели (папка).
     */
    suspend fun importModel(
        context: Context,
        folderUri: Uri,
        modelName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val privateDir = context.getExternalFilesDir(null)
                ?: throw Exception("External files dir is null")
            val modelDir = File(privateDir, modelName)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            modelDir.mkdirs()

            val sourceFolder = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw Exception("Cannot open source folder")
            val modelSubFolder = sourceFolder.findFile(modelName)
                ?: throw Exception("Model folder $modelName not found in selected location")

            copyDirectoryFromDocument(modelSubFolder, modelDir, context)
            Log.d(TAG, "Imported model $modelName successfully")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed for $modelName", e)
            Result.failure(e)
        }
    }

    /**
     * Рекурсивно копирует все файлы из [sourceDir] (java.io.File) в [targetFolder] (DocumentFile).
     */
    private fun copyDirectory(sourceDir: File, targetFolder: DocumentFile, context: Context) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                var subFolder = targetFolder.findFile(file.name)
                if (subFolder == null) {
                    subFolder = targetFolder.createDirectory(file.name)
                        ?: throw Exception("Cannot create dir ${file.name}")
                }
                copyDirectory(file, subFolder, context)
            } else {
                // Удаляем существующий файл, если есть
                targetFolder.findFile(file.name)?.delete()
                val createdFile = targetFolder.createFile(
                    file.extension.ifEmpty { "bin" },
                    file.nameWithoutExtension
                ) ?: throw Exception("Cannot create file ${file.name}")
                context.contentResolver.openOutputStream(createdFile.uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }
            }
        }
    }

    /**
     * Копирует из DocumentFile (рекурсивно) в java.io.File.
     */
    private fun copyDirectoryFromDocument(
        sourceFolder: DocumentFile,
        targetDir: File,
        context: Context
    ) {
        sourceFolder.listFiles().forEach { docFile ->
            if (docFile.isDirectory) {
                val subDir = File(targetDir, docFile.name ?: "unknown")
                subDir.mkdirs()
                copyDirectoryFromDocument(docFile, subDir, context)
            } else {
                val targetFile = File(targetDir, docFile.name ?: "unnamed_file")
                context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
