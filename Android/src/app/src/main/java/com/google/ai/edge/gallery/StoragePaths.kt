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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central place for all on-disk paths used by the gallery server and worker.
 *
 * Strategy (targetSdk = 35, minSdk = 31):
 *  - Public path:  /storage/emulated/0/Download/AIEdgeGallery/  (visible in Files / Total Commander)
 *  - Private path: /Android/data/<pkg>/files/Download/AIEdgeGallery/  (no permission, stable for JNI)
 *  - Logs:         /Download/gallery_server/gallery_server-<date>.log  (public, via MediaStore on API 29+)
 *                  <filesDir>/logs/gallery_server-<date>.log           (private, always writable)
 *
 *  We always write to BOTH locations when possible. If public is not writable (no permission
 *  or scoped storage on API 30+), we still succeed on the private path and surface a warning
 *  in the private log so we never silently lose diagnostics.
 */
object StoragePaths {
  private const val TAG = "StoragePaths"
  const val PUBLIC_MODELS_SUBDIR = "AIEdgeGallery"
  const val PUBLIC_LOGS_SUBDIR = "gallery_server"
  const val LOG_PREFIX = "gallery_server"

  /** Public path under /Download. May be unwritable on API 30+ without MANAGE_EXTERNAL_STORAGE. */
  fun publicModelsDir(context: Context): File =
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_MODELS_SUBDIR)

  /** App-specific external path. Always writable, no runtime permission needed. */
  fun privateModelsDir(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "Download")
    return File(base, PUBLIC_MODELS_SUBDIR)
  }

  /** App-specific external logs dir (always writable). */
  fun privateLogsDir(context: Context): File =
      File(context.filesDir, "logs")

  /** Public logs dir under /Download/gallery_server (used as a subdir for MediaStore relative_path). */
  fun publicLogsRelativePath(): String = "Download/$PUBLIC_LOGS_SUBDIR"

  fun todayLogFileName(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return "${LOG_PREFIX}-${fmt.format(Date())}.log"
  }

  /**
   * Find a .litertlm model file, preferring the public Download/AIEdgeGallery tree, then the
   * private /Android/data/<pkg>/files/Download/AIEdgeGallery tree. If a model exists in only
   * one location, copies it to the other (best effort) so subsequent builds can find it.
   */
  fun findModelFile(context: Context): File? {
    val pub = publicModelsDir(context)
    val priv = privateModelsDir(context)

    val pubModels = listLitertLm(pub)
    val privModels = listLitertLm(priv)

    Log.i(TAG, "Scanning: public=${pub.absolutePath} (${pubModels.size}) private=${priv.absolutePath} (${privModels.size})")

    // Pick the largest one across both locations; tie -> public wins.
    val candidate: File? = (pubModels + privModels).maxByOrNull { it.length() }
    if (candidate == null) {
      Log.e(TAG, "No .litertlm model found in any location")
      return null
    }

    val isInPublic = pubModels.any { it.absolutePath == candidate.absolutePath }
    Log.i(TAG, "Selected ${candidate.name} (${candidate.length() / 1024 / 1024} MB) from ${if (isInPublic) "public" else "private"}")

    // Best-effort: copy to the other location so the next launch survives either.
    try {
      val other = if (isInPublic) priv else pub
      val target = File(other, candidate.name)
      if (!target.exists() || target.length() != candidate.length()) {
        if (!other.exists()) other.mkdirs()
        if (canWriteTo(other)) {
          candidate.copyTo(target, overwrite = true)
          Log.i(TAG, "Mirrored model to ${target.absolutePath}")
        } else {
          Log.w(TAG, "Skip mirror: cannot write to ${other.absolutePath}")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Mirror model failed: ${e.message}")
    }

    return candidate
  }

  private fun listLitertLm(dir: File): List<File> {
    if (!dir.isDirectory) return emptyList()
    return runCatching {
      dir.walkTopDown()
          .filter { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
          .toList()
    }.getOrDefault(emptyList())
  }

  private fun canWriteTo(dir: File): Boolean = runCatching { dir.canWrite() }.getOrDefault(false)

  /**
   * Append a log line to BOTH the private and public log files. Never throws.
   * Public write uses MediaStore.Downloads on API 29+ (no permission), and direct file write
   * on API ≤ 28. On API 30+ without MANAGE_EXTERNAL_STORAGE the public write is a no-op and
   * we just log a warning.
   */
  fun writeLog(context: Context, level: String, tag: String, msg: String, throwable: Throwable? = null) {
    val line = buildLogLine(level, tag, msg, throwable)

    // Private log: always works.
    runCatching {
      val dir = privateLogsDir(context)
      if (!dir.exists()) dir.mkdirs()
      File(dir, todayLogFileName()).appendText(line + "\n")
    }.onFailure { Log.e(TAG, "Private log write failed", it) }

    // Public log: best-effort.
    runCatching { writePublicLog(context, line) }
        .onFailure { Log.w(TAG, "Public log write failed: ${it.message}") }
  }

  fun logI(context: Context, tag: String, msg: String) = writeLog(context, "I", tag, msg)
  fun logW(context: Context, tag: String, msg: String, t: Throwable? = null) = writeLog(context, "W", tag, msg, t)
  fun logE(context: Context, tag: String, msg: String, t: Throwable? = null) = writeLog(context, "E", tag, msg, t)

  private fun buildLogLine(level: String, tag: String, msg: String, throwable: Throwable?): String {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    val base = "$ts $level/$tag: $msg"
    return if (throwable != null) {
      val sw = StringWriter()
      throwable.printStackTrace(PrintWriter(sw))
      "$base\n$sw"
    } else base
  }

  private fun writePublicLog(context: Context, line: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writePublicLogMediaStore(context, line)
    } else {
      @Suppress("DEPRECATION")
      val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_LOGS_SUBDIR)
      if (!dir.exists()) dir.mkdirs()
      if (!canWriteTo(dir)) {
        Log.w(TAG, "Public log dir not writable: ${dir.absolutePath}")
        return
      }
      File(dir, todayLogFileName()).appendText(line + "\n")
    }
  }

  private fun writePublicLogMediaStore(context: String, @Suppress("UNUSED_PARAMETER") line: String) {
    // Intentionally split into a separate method overload below; this stub keeps signature stable.
  }

  private fun writePublicLogMediaStore(context: Context, line: String) {
    val resolver = context.contentResolver
    val fileName = todayLogFileName()
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val relativePath = publicLogsRelativePath()

    // Try to find an existing entry for today and append; otherwise create a new one.
    val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME)
    val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
    val args = arrayOf(fileName, "$relativePath/")
    val existing: Uri? = resolver.query(collection, projection, selection, args, null)?.use { c ->
      if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null
    }

    val uri: Uri = existing ?: run {
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, "$relativePath/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
      }
      resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
    }

    try {
      resolver.openOutputStream(uri, "wa")?.use { os ->
        os.write((line + "\n").toByteArray(Charsets.UTF_8))
        os.flush()
      } ?: throw IOException("openOutputStream returned null")
    } finally {
      if (existing == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
      }
    }
  }

  /**
   * Copy a freshly downloaded model into BOTH public and private locations.
   * Best-effort: never throws; logs failures into the private log.
   */
  fun mirrorModel(context: Context, source: File, preferredName: String = source.name) {
    val src = if (!source.exists()) {
      Log.w(TAG, "mirrorModel: source missing ${source.absolutePath}")
      return
    } else File(source.parentFile, preferredName).let { if (it.exists()) it else source }

    listOf(publicModelsDir(context), privateModelsDir(context)).forEach { dir ->
      runCatching {
        if (!dir.exists()) dir.mkdirs()
        if (canWriteTo(dir)) {
          val target = File(dir, preferredName)
          if (!target.exists() || target.length() != src.length()) {
            src.copyTo(target, overwrite = true)
            Log.i(TAG, "Mirrored to ${target.absolutePath}")
          }
        } else {
          Log.w(TAG, "Cannot mirror to ${dir.absolutePath} (not writable)")
        }
      }.onFailure { Log.w(TAG, "Mirror to ${dir.absolutePath} failed", it) }
    }
  }
}
