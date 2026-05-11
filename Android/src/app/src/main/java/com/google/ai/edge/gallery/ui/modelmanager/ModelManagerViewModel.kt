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

package com.google.ai.edge.gallery.ui.modelmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL =
    "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"
private const val TEST_MODEL_ALLOW_LIST = ""

// ... (data classes remain unchanged) ...
data class ModelInitializationStatus(
    val status: ModelInitializationStatusType,
    var error: String = "",
    var initializedBackends: Set<String> = setOf(),
) {
    fun isFirstInitialization(model: Model): Boolean {
        val backend =
            model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
        return !initializedBackends.contains(backend)
    }
}

enum class ModelInitializationStatusType {
    NOT_INITIALIZED, INITIALIZING, INITIALIZED, ERROR
}

enum class TokenStatus {
    NOT_STORED, EXPIRED, NOT_EXPIRED
}

enum class TokenRequestResultType {
    FAILED, SUCCEEDED, USER_CANCELLED
}

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)
data class TokenRequestResult(val status: TokenRequestResultType, val errorMessage: String? = null)

data class ModelManagerUiState(
    val tasks: List<Task>,
    val tasksByCategory: Map<String, List<Task>>,
    val modelDownloadStatus: Map<String, ModelDownloadStatus>,
    val modelInitializationStatus: Map<String, ModelInitializationStatus>,
    val loadingModelAllowlist: Boolean = true,
    val loadingModelAllowlistError: String = "",
    val selectedModel: Model = EMPTY_MODEL,
    val textInputHistory: List<String> = listOf(),
    val configValuesUpdateTrigger: Long = 0L,
    val modelImportingUpdateTrigger: Long = 0L,
) {
    fun isModelInitialized(model: Model): Boolean =
        modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED

    fun isModelInitializing(model: Model): Boolean =
        modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZING
}

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
    NumberSliderConfig(
        key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
        sliderMin = 1f,
        sliderMax = 30f,
        defaultValue = 3f,
        valueType = ValueType.INT,
    )

private val PREDEFINED_LLM_TASK_ORDER =
    listOf(
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_AGENT_CHAT,
        BuiltInTaskId.LLM_PROMPT_LAB,
        BuiltInTaskId.LLM_TINY_GARDEN,
        BuiltInTaskId.LLM_MOBILE_ACTIONS,
    )

@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
    private val downloadRepository: DownloadRepository,
    val dataStoreRepository: DataStoreRepository,
    private val lifecycleProvider: AppLifecycleProvider,
    private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
    private val systemPromptRepository: SystemPromptRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // <-- CHANGED: dynamic storage root
 // Внутри класса ModelManagerViewModel, замените существующий метод

private fun getStorageDir(): File {
    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .resolve("AIEdgeGallery")
    if (!publicDir.exists()) {
        publicDir.mkdirs()
    }
    return publicDir
}
    // -->

    protected val _uiState = MutableStateFlow(createEmptyUiState())
    open val uiState = _uiState.asStateFlow()

    private var _allowlistModels: MutableList<Model> = mutableListOf()
    val allowlistModels: List<Model>
        get() = _allowlistModels

    val authService = AuthorizationService(context)
    var curAccessToken: String = ""

    override fun onCleared() {
        authService.dispose()
    }

    // ... all other public methods unchanged except those that touch storage ...
    fun getTaskById(id: String): Task? = uiState.value.tasks.find { it.id == id }
    fun getTasksByIds(ids: Set<String>): List<Task> = uiState.value.tasks.filter { ids.contains(it.id) }
    fun getCustomTaskByTaskId(id: String): CustomTask? = getActiveCustomTasks().find { it.task.id == id }
    fun getActiveCustomTasks(): List<CustomTask> = customTasks.toList()
    fun getSelectedModel(): Model? = uiState.value.selectedModel

    fun getModelByName(name: String): Model? {
        for (task in uiState.value.tasks) {
            for (model in task.models) {
                if (model.name == name) return model
            }
        }
        return null
    }

    fun getAllModels(): List<Model> {
        val allModels = mutableSetOf<Model>()
        for (task in uiState.value.tasks) {
            for (model in task.models) {
                allModels.add(model)
            }
        }
        return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
    }

    fun getAllDownloadedModels(): List<Model> {
        return getAllModels().filter {
            uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED && it.isLlm
        }
    }

    fun processTasks() {
        val curTasks = getActiveCustomTasks().map { it.task }
        for (task in curTasks) {
            for (model in task.models) {
                model.preProcess()
            }
            val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
            if (bestModel != null) {
                task.models.remove(bestModel)
                task.models.add(0, bestModel)
            }
        }
    }

    fun updateConfigValuesUpdateTrigger() {
        _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
    }

    fun selectModel(model: Model) {
        if (_uiState.value.selectedModel.name != model.name) {
            _uiState.update { it.copy(selectedModel = model) }
        }
    }

    open fun downloadModel(task: Task?, model: Model) {
        setDownloadStatus(
            curModel = model,
            status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
        )
        if (model.runtimeType == RuntimeType.AICORE) {
            AICoreModelHelper.downloadModel(
                context = context,
                coroutineScope = viewModelScope,
                model = model,
                onProgress = { downloaded, total ->
                    setDownloadStatus(
                        curModel = model,
                        status = ModelDownloadStatus(
                            status = ModelDownloadStatusType.IN_PROGRESS,
                            receivedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                },
                onDone = {
                    setDownloadStatus(
                        curModel = model,
                        status = ModelDownloadStatus(
                            status = ModelDownloadStatusType.SUCCEEDED,
                            receivedBytes = model.sizeInBytes,
                            totalBytes = model.sizeInBytes,
                        ),
                    )
                },
                onError = { error ->
                    setDownloadStatus(
                        curModel = model,
                        status = ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
                    )
                },
            )
            return
        }

        deleteModel(model = model)
        downloadRepository.downloadModel(
            task = task,
            model = model,
            onStatusUpdated = this::setDownloadStatus,
        )
    }

    fun cancelDownloadModel(model: Model) {
        if (model.runtimeType == RuntimeType.AICORE) return
        downloadRepository.cancelDownloadModel(model)
        deleteModel(model = model)
    }

    fun deleteModel(model: Model) {
        if (model.updatable) {
            model.updatable = false
            model.latestModelFile?.let {
                model.version = it.commitHash
                model.downloadFileName = it.fileName
            }
        }

        if (model.imported) {
            deleteFilesFromImportDir(model.downloadFileName)
        } else {
            deleteDirFromStorage(model.normalizedName) // <-- CHANGED
        }

        val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
        curModelDownloadStatus[model.name] =
            ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

        if (model.imported) {
            for (curTask in uiState.value.tasks) {
                val index = curTask.models.indexOf(model)
                if (index >= 0) {
                    curTask.models.removeAt(index)
                }
                curTask.updateTrigger.value = System.currentTimeMillis()
            }
            curModelDownloadStatus.remove(model.name)

            val importedModels = dataStoreRepository.readImportedModels().toMutableList()
            val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
            if (importedModelIndex >= 0) {
                importedModels.removeAt(importedModelIndex)
            }
            dataStoreRepository.saveImportedModels(importedModels = importedModels)
        }
        val newUiState = uiState.value.copy(
            modelDownloadStatus = curModelDownloadStatus,
            tasks = uiState.value.tasks.toList(),
            modelImportingUpdateTrigger = System.currentTimeMillis(),
        )
        _uiState.update { newUiState }
    }

    fun initializeModel(
        context: Context,
        task: Task,
        model: Model,
        force: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!force &&
                uiState.value.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
            ) {
                Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
                return@launch
            }
            if (model.initializing) {
                model.cleanUpAfterInit = false
                Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
                return@launch
            }
            cleanupModel(context = context, task = task, model = model)

            Log.d(TAG, "Initializing model '${model.name}'...")
            model.initializing = true
            updateModelInitializationStatus(model, ModelInitializationStatusType.INITIALIZING)

            val onDoneFn: (error: String) -> Unit = { error ->
                model.initializing = false
                if (model.instance != null) {
                    Log.d(TAG, "Model '${model.name}' initialized successfully")
                    updateModelInitializationStatus(model, ModelInitializationStatusType.INITIALIZED)
                    if (model.cleanUpAfterInit) {
                        Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
                        cleanupModel(context = context, task = task, model = model)
                    }
                    onDone()
                } else if (error.isNotEmpty()) {
                    Log.d(TAG, "Model '${model.name}' failed to initialize")
                    updateModelInitializationStatus(model, ModelInitializationStatusType.ERROR, error = error)
                }
            }

            val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
            getCustomTaskByTaskId(id = task.id)
                ?.initializeModelFn(
                    context = context,
                    coroutineScope = viewModelScope,
                    model = model,
                    systemInstruction = Contents.of(systemPrompt),
                    onDone = onDoneFn,
                )
        }
    }

    fun cleanupModel(
        context: Context,
        task: Task,
        model: Model,
        instanceToCleanUp: Any? = model.instance,
        onDone: () -> Unit = {},
    ) {
        if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
            Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
            onDone()
            return
        }
        if (model.instance != null) {
            model.cleanUpAfterInit = false
            Log.d(TAG, "Cleaning up model '${model.name}'...")
            val onDoneFn: () -> Unit = {
                model.instance = null
                model.initializing = false
                updateModelInitializationStatus(model, ModelInitializationStatusType.NOT_INITIALIZED)
                Log.d(TAG, "Clean up model '${model.name}' done")
                onDone()
            }
            getCustomTaskByTaskId(id = task.id)
                ?.cleanUpModelFn(
                    context = context,
                    coroutineScope = viewModelScope,
                    model = model,
                    onDone = onDoneFn,
                )
        } else {
            if (model.initializing) {
                Log.d(TAG, "Model '${model.name}' is still initializing.. Will clean up after it is done initializing")
                model.cleanUpAfterInit = true
            }
        }
    }

    fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
        val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
        curModelDownloadStatus[curModel.name] = status
        val newUiState = uiState.value.copy(modelDownloadStatus = curModelDownloadStatus)

        if (status.status == ModelDownloadStatusType.FAILED ||
            status.status == ModelDownloadStatusType.NOT_DOWNLOADED
        ) {
            deleteFileFromStorage(curModel.downloadFileName) // <-- CHANGED
        }
        _uiState.update { newUiState }
    }

    fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
        val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
        if (curStatus.containsKey(model.name)) {
            val initializedBackends = curStatus[model.name]?.initializedBackends ?: setOf()
            val backend = model.getStringConfigValue(
                key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label
            )
            val newInitializedBackends =
                if (status.status == ModelInitializationStatusType.INITIALIZED) initializedBackends + backend
                else initializedBackends
            curStatus[model.name] = status.copy(initializedBackends = newInitializedBackends)
            _uiState.update { it.copy(modelInitializationStatus = curStatus) }
        }
    }

    // ... text history, theme, token methods unchanged ...

    fun addTextInputHistory(text: String) {
        if (uiState.value.textInputHistory.indexOf(text) < 0) {
            val newHistory = uiState.value.textInputHistory.toMutableList()
            newHistory.add(0, text)
            if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
                newHistory.removeAt(newHistory.size - 1)
            }
            _uiState.update { it.copy(textInputHistory = newHistory) }
            dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
        } else {
            promoteTextInputHistoryItem(text)
        }
    }

    fun promoteTextInputHistoryItem(text: String) {
        val index = uiState.value.textInputHistory.indexOf(text)
        if (index >= 0) {
            val newHistory = uiState.value.textInputHistory.toMutableList()
            newHistory.removeAt(index)
            newHistory.add(0, text)
            _uiState.update { it.copy(textInputHistory = newHistory) }
            dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
        }
    }

    fun deleteTextInputHistory(text: String) {
        val index = uiState.value.textInputHistory.indexOf(text)
        if (index >= 0) {
            val newHistory = uiState.value.textInputHistory.toMutableList()
            newHistory.removeAt(index)
            _uiState.update { it.copy(textInputHistory = newHistory) }
            dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
        }
    }

    fun clearTextInputHistory() {
        _uiState.update { it.copy(textInputHistory = mutableListOf()) }
        dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }

    fun readThemeOverride(): Theme = dataStoreRepository.readTheme()
    fun saveThemeOverride(theme: Theme) = dataStoreRepository.saveTheme(theme = theme)

    fun getModelUrlResponse(model: Model, accessToken: String? = null): Int {
        try {
            val url = URL(model.url)
            val connection = url.openConnection() as HttpURLConnection
            if (accessToken != null) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            connection.connect()
            return connection.responseCode
        } catch (e: Exception) {
            Log.e(TAG, "$e")
            return -1
        }
    }

    fun addImportedLlmModel(info: ImportedModel) {
        Log.d(TAG, "adding imported llm model: $info")
        val model = createModelFromImportedModelInfo(info = info)

        val setOfTasks = mutableSetOf(
            BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_ASK_IMAGE, BuiltInTaskId.LLM_ASK_AUDIO,
            BuiltInTaskId.LLM_PROMPT_LAB, BuiltInTaskId.LLM_TINY_GARDEN, BuiltInTaskId.LLM_MOBILE_ACTIONS,
            BuiltInTaskId.LLM_AGENT_CHAT,
        )
        for (task in getTasksByIds(ids = setOfTasks)) {
            val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
            if (modelIndex >= 0) {
                Log.d(TAG, "duplicated imported model found in task. Removing it first")
                task.models.removeAt(modelIndex)
            }
            if (
                (task.id == BuiltInTaskId.LLM_ASK_IMAGE && model.llmSupportImage) ||
                (task.id == BuiltInTaskId.LLM_ASK_AUDIO && model.llmSupportAudio) ||
                (task.id == BuiltInTaskId.LLM_TINY_GARDEN && model.llmSupportTinyGarden) ||
                (task.id == BuiltInTaskId.LLM_MOBILE_ACTIONS && model.llmSupportMobileActions) ||
                (task.id != BuiltInTaskId.LLM_ASK_IMAGE && task.id != BuiltInTaskId.LLM_ASK_AUDIO &&
                    task.id != BuiltInTaskId.LLM_TINY_GARDEN && task.id != BuiltInTaskId.LLM_MOBILE_ACTIONS)
            ) {
                task.models.add(model)
                if (task.id == BuiltInTaskId.LLM_TINY_GARDEN) {
                    val newConfigs = model.configs.toMutableList()
                    newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
                    model.configs = newConfigs
                    model.preProcess()
                }
            }
            task.updateTrigger.value = System.currentTimeMillis()
        }

        val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
        val modelInstances = uiState.value.modelInitializationStatus.toMutableMap()
        modelDownloadStatus[model.name] = ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = info.fileSize,
            totalBytes = info.fileSize,
        )
        modelInstances[model.name] = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

        _uiState.update {
            uiState.value.copy(
                tasks = uiState.value.tasks.toList(),
                modelDownloadStatus = modelDownloadStatus,
                modelInitializationStatus = modelInstances,
                modelImportingUpdateTrigger = System.currentTimeMillis(),
            )
        }

        val importedModels = dataStoreRepository.readImportedModels().toMutableList()
        val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
        if (importedModelIndex >= 0) {
            Log.d(TAG, "duplicated imported model found in data store. Removing it first")
            importedModels.removeAt(importedModelIndex)
        }
        importedModels.add(info)
        dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }

    fun getTokenStatusAndData(): TokenStatusAndData {
        Log.d(TAG, "Reading token data from data store...")
        val tokenData = dataStoreRepository.readAccessTokenData()
        if (tokenData != null && tokenData.accessToken.isNotEmpty()) {
            Log.d(TAG, "Token exists and loaded.")
            val curTs = System.currentTimeMillis()
            val expirationTs = tokenData.expiresAtMs - 5 * 60
            Log.d(TAG, "Checking whether token has expired or not. Current ts: $curTs, expires at: $expirationTs")
            if (curTs >= expirationTs) {
                Log.d(TAG, "Token expired!")
                return TokenStatusAndData(TokenStatus.EXPIRED, tokenData)
            } else {
                Log.d(TAG, "Token not expired.")
                curAccessToken = tokenData.accessToken
                return TokenStatusAndData(TokenStatus.NOT_EXPIRED, tokenData)
            }
        }
        Log.d(TAG, "Token doesn't exists.")
        return TokenStatusAndData(TokenStatus.NOT_STORED, null)
    }

    fun getAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            ProjectConfig.authServiceConfig,
            ProjectConfig.clientId,
            ResponseTypeValues.CODE,
            ProjectConfig.redirectUri.toUri(),
        )
            .setScope("read-repos")
            .build()
    }

    fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) {
        val dataIntent = result.data
        if (dataIntent == null) {
            onTokenRequested(TokenRequestResult(TokenRequestResultType.FAILED, "Empty auth result"))
            return
        }
        val response = AuthorizationResponse.fromIntent(dataIntent)
        val exception = AuthorizationException.fromIntent(dataIntent)
        when {
            response?.authorizationCode != null -> {
                var errorMessage: String? = null
                authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
                    if (tokenResponse != null) {
                        if (tokenResponse.accessToken == null) {
                            errorMessage = "Empty access token"
                        } else if (tokenResponse.refreshToken == null) {
                            errorMessage = "Empty refresh token"
                        } else if (tokenResponse.accessTokenExpirationTime == null) {
                            errorMessage = "Empty expiration time"
                        } else {
                            Log.d(TAG, "Token exchange successful. Storing tokens...")
                            saveAccessToken(
                                accessToken = tokenResponse.accessToken!!,
                                refreshToken = tokenResponse.refreshToken!!,
                                expiresAt = tokenResponse.accessTokenExpirationTime!!,
                            )
                            curAccessToken = tokenResponse.accessToken!!
                            Log.d(TAG, "Token successfully saved.")
                        }
                    } else if (tokenEx != null) {
                        errorMessage = "Token exchange failed: ${tokenEx.message}"
                    } else {
                        errorMessage = "Token exchange failed"
                    }
                    if (errorMessage == null) {
                        onTokenRequested(TokenRequestResult(TokenRequestResultType.SUCCEEDED))
                    } else {
                        onTokenRequested(TokenRequestResult(TokenRequestResultType.FAILED, errorMessage))
                    }
                }
            }
            exception != null -> {
                onTokenRequested(
                    TokenRequestResult(
                        status = if (exception.message == "User cancelled flow")
                            TokenRequestResultType.USER_CANCELLED else TokenRequestResultType.FAILED,
                        errorMessage = exception.message,
                    )
                )
            }
            else -> {
                onTokenRequested(TokenRequestResult(TokenRequestResultType.USER_CANCELLED))
            }
        }
    }

    fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
        dataStoreRepository.saveAccessTokenData(accessToken, refreshToken, expiresAt)
    }

    fun clearAccessToken() {
        dataStoreRepository.clearAccessTokenData()
    }

    private fun checkAICoreModelStatuses() {
        viewModelScope.launch(Dispatchers.Main) {
            val aicoreModels = uiState.value.tasks.flatMap { it.models }
                .filter { it.runtimeType == RuntimeType.AICORE }
                .distinctBy { it.name }
            for (model in aicoreModels) {
                downloadModel(task = null, model = model)
            }
        }
    }

    private fun processPendingDownloads() {
        downloadRepository.cancelAll {
            Log.d(TAG, "All workers are cancelled.")
            viewModelScope.launch(Dispatchers.Main) {
                val checkedModelNames = mutableSetOf<String>()
                val tokenStatusAndData = getTokenStatusAndData()
                for (task in uiState.value.tasks) {
                    for (model in task.models) {
                        if (checkedModelNames.contains(model.name)) continue
                        val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
                        if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
                            if (tokenStatusAndData.status == TokenStatus.NOT_EXPIRED && tokenStatusAndData.data != null) {
                                model.accessToken = tokenStatusAndData.data.accessToken
                            }
                            Log.d(TAG, "Sending a new download request for '${model.name}'")
                            downloadRepository.downloadModel(
                                task = task,
                                model = model,
                                onStatusUpdated = this@ModelManagerViewModel::setDownloadStatus,
                            )
                        }
                        checkedModelNames.add(model.name)
                    }
                }
            }
        }
    }

    fun loadModelAllowlist() {
        _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _allowlistModels.clear()
                var modelAllowlist: ModelAllowlist? = null
                Log.d(TAG, "Loading test model allowlist.")
                modelAllowlist = readModelAllowlistFromDisk(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

                if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
                    Log.d(TAG, "Loading local model allowlist for testing.")
                    try {
                        modelAllowlist = Gson().fromJson(TEST_MODEL_ALLOW_LIST, ModelAllowlist::class.java)
                    } catch (e: JsonSyntaxException) {
                        Log.e(TAG, "Failed to parse local test json", e)
                    }
                }

                if (modelAllowlist == null) {
                    var version = BuildConfig.VERSION_NAME.replace(".", "_")
                    val url = getAllowlistUrl(version)
                    Log.d(TAG, "Loading model allowlist from internet. Url: $url")
                    val data = getJsonResponse<ModelAllowlist>(url = url)
                    modelAllowlist = data?.jsonObj
                    if (modelAllowlist == null) {
                        Log.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
                        modelAllowlist = readModelAllowlistFromDisk()
                    } else {
                        Log.d(TAG, "Done: loading model allowlist from internet")
                        saveModelAllowlistToDisk(modelAllowlistContent = data?.textContent ?: "{}")
                    }
                }

                if (modelAllowlist == null) {
                    _uiState.update { it.copy(loadingModelAllowlistError = "Failed to load model list") }
                    return@launch
                }

                Log.d(TAG, "Allowlist: $modelAllowlist")

                val isAICoreAvailable by lazy {
                    val allowedDeviceModelsSet = modelAllowlist.aicoreRequirements
                        ?.allowedDeviceGroups
                        ?.asSequence()
                        ?.flatMap { it.deviceModels }
                        ?.map { it.lowercase() }
                        ?.toSet()
                    isAICoreSupported(allowedDeviceModelsSet)
                }

                val curTasks = getActiveCustomTasks().map { it.task }
                val nameToModel = mutableMapOf<String, Model>()
                for (allowedModel in modelAllowlist.models) {
                    if (allowedModel.disabled == true) continue
                    if (allowedModel.runtimeType == RuntimeType.AICORE && !isAICoreAvailable) continue

                    val accelerators = allowedModel.defaultConfig.accelerators ?: ""
                    val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
                        val socToModelFiles = allowedModel.socToModelFiles
                        if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
                            Log.d(TAG, "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC")
                            continue
                        }
                    }

                    val model = allowedModel.toModel()
                    _allowlistModels.add(model)
                    nameToModel[model.name] = model
                    for (taskType in allowedModel.taskTypes) {
                        val task = curTasks.find { it.id == taskType }
                        task?.models?.add(model)
                        if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
                            val newConfigs = model.configs.toMutableList()
                            newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
                            model.configs = newConfigs
                        }
                    }
                }

                for (task in curTasks) {
                    if (task.modelNames.isNotEmpty()) {
                        for (modelName in task.modelNames) {
                            val model = nameToModel[modelName]
                            if (model == null) {
                                Log.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
                                continue
                            }
                            task.models.add(model)
                        }
                    }
                }

                processTasks()
                _uiState.update {
                    createUiState().copy(
                        loadingModelAllowlist = false,
                        tasks = curTasks,
                        tasksByCategory = groupTasksByCategory(),
                    )
                }
                processPendingDownloads()
                checkAICoreModelStatuses()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearLoadModelAllowlistError() {
        val curTasks = getActiveCustomTasks().map { it.task }
        processTasks()
        _uiState.update {
            createUiState().copy(
                loadingModelAllowlist = false,
                tasks = curTasks,
                loadingModelAllowlistError = "",
                tasksByCategory = groupTasksByCategory(),
            )
        }
    }

    fun setAppInForeground(foreground: Boolean) {
        lifecycleProvider.isAppInForeground = foreground
    }

    // <-- NEW: Permission aware storage helpers
    fun onStoragePermissionGranted() {
        // could trigger rescan of models, for simplicity we just refresh allowlist which will update paths
        loadModelAllowlist()
    }
    // -->

    // ============ PRIVATE STORAGE METHODS REWRITTEN ============

    private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
        try {
            Log.d(TAG, "Saving model allowlist to disk...")
            val file = File(getStorageDir(), MODEL_ALLOWLIST_FILENAME)
            file.writeText(modelAllowlistContent)
            Log.d(TAG, "Done: saving model allowlist to disk.")
        } catch (e: Exception) {
            Log.e(TAG, "failed to write model allowlist to disk", e)
        }
    }

    private fun readModelAllowlistFromDisk(fileName: String = MODEL_ALLOWLIST_FILENAME): ModelAllowlist? {
        try {
            Log.d(TAG, "Reading model allowlist from disk: $fileName")
            val baseDir = if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else getStorageDir()
            val file = File(baseDir, fileName)
            if (file.exists()) {
                val content = file.readText()
                Log.d(TAG, "Model allowlist content from local file: $content")
                return Gson().fromJson(content, ModelAllowlist::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to read model allowlist from disk", e)
        }
        return null
    }

    private fun isModelPartiallyDownloaded(model: Model): Boolean {
        if (model.localModelFilePathOverride.isNotEmpty()) return false
        // Build path relative to dynamic storage root
        val tmpFilePath = File(
            getStorageDir(),
            "${model.normalizedName}/${model.downloadFileName}.$TMP_FILE_EXT"
        )
        return tmpFilePath.exists()
    }

    private fun createEmptyUiState(): ModelManagerUiState {
        return ModelManagerUiState(
            tasks = listOf(),
            tasksByCategory = mapOf(),
            modelDownloadStatus = mapOf(),
            modelInitializationStatus = mapOf(),
        )
    }

    private fun createUiState(): ModelManagerUiState {
        val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
        val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
        val tasks: MutableMap<String, Task> = mutableMapOf()
        val checkedModelNames = mutableSetOf<String>()
        for (customTask in getActiveCustomTasks()) {
            val task = customTask.task
            tasks[task.id] = task
            for (model in task.models) {
                if (checkedModelNames.contains(model.name)) continue
                modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
                modelInstances[model.name] =
                    ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
                checkedModelNames.add(model.name)
            }
        }

        for (importedModel in dataStoreRepository.readImportedModels()) {
            Log.d(TAG, "stored imported model: $importedModel")
            val model = createModelFromImportedModelInfo(info = importedModel)
            tasks[BuiltInTaskId.LLM_CHAT]?.models?.add(model)
            tasks[BuiltInTaskId.LLM_PROMPT_LAB]?.models?.add(model)
            tasks[BuiltInTaskId.LLM_AGENT_CHAT]?.models?.add(model)
            if (model.llmSupportImage) tasks[BuiltInTaskId.LLM_ASK_IMAGE]?.models?.add(model)
            if (model.llmSupportAudio) tasks[BuiltInTaskId.LLM_ASK_AUDIO]?.models?.add(model)
            if (model.llmSupportTinyGarden) {
                tasks[BuiltInTaskId.LLM_TINY_GARDEN]?.models?.add(model)
                val newConfigs = model.configs.toMutableList()
                newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
                model.configs = newConfigs
                model.preProcess()
            }
            if (model.llmSupportMobileActions) tasks[BuiltInTaskId.LLM_MOBILE_ACTIONS]?.models?.add(model)

            modelDownloadStatus[model.name] = ModelDownloadStatus(
                status = ModelDownloadStatusType.SUCCEEDED,
                receivedBytes = importedModel.fileSize,
                totalBytes = importedModel.fileSize,
            )
        }

        val textInputHistory = dataStoreRepository.readTextInputHistory()
        Log.d(TAG, "text input history: $textInputHistory")
        Log.d(TAG, "model download status: $modelDownloadStatus")
        return ModelManagerUiState(
            tasks = getActiveCustomTasks().map { it.task }.toList(),
            tasksByCategory = mapOf(),
            modelDownloadStatus = modelDownloadStatus,
            modelInitializationStatus = modelInstances,
            textInputHistory = textInputHistory,
        )
    }

    private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
        val accelerators = info.llmConfig.compatibleAcceleratorsList.mapNotNull { label ->
            when (label.trim()) {
                Accelerator.GPU.label -> Accelerator.GPU
                Accelerator.CPU.label -> Accelerator.CPU
                Accelerator.NPU.label -> Accelerator.NPU
                else -> null
            }
        }.toMutableList()
        val configs = createLlmChatConfigs(
            defaultMaxToken = info.llmConfig.defaultMaxTokens,
            defaultTopK = info.llmConfig.defaultTopk,
            defaultTopP = info.llmConfig.defaultTopp,
            defaultTemperature = info.llmConfig.defaultTemperature,
            accelerators = accelerators,
            supportThinking = info.llmConfig.supportThinking,
            supportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding,
        ).toMutableList()
        val capabilities = mutableListOf<ModelCapability>()
        val capabilityToTaskTypes = mutableMapOf<ModelCapability, List<String>>()
        if (info.llmConfig.supportThinking) {
            capabilities.add(ModelCapability.LLM_THINKING)
            capabilityToTaskTypes[ModelCapability.LLM_THINKING] = listOf(
                BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_ASK_IMAGE, BuiltInTaskId.LLM_ASK_AUDIO
            )
        }
        if (info.llmConfig.supportSpeculativeDecoding) {
            capabilities.add(ModelCapability.SPECULATIVE_DECODING)
            capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] = listOf(
                BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_ASK_IMAGE, BuiltInTaskId.LLM_ASK_AUDIO,
                BuiltInTaskId.LLM_PROMPT_LAB
            )
        }
        val model = Model(
            name = info.fileName,
            url = "",
            configs = configs,
            sizeInBytes = info.fileSize,
            downloadFileName = "$IMPORTS_DIR/${info.fileName}",
            showBenchmarkButton = false,
            showRunAgainButton = false,
            imported = true,
            llmSupportImage = info.llmConfig.supportImage,
            llmSupportAudio = info.llmConfig.supportAudio,
            llmSupportTinyGarden = info.llmConfig.supportTinyGarden,
            llmSupportMobileActions = info.llmConfig.supportMobileActions,
            capabilities = capabilities.toList(),
            capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
            llmMaxToken = info.llmConfig.defaultMaxTokens,
            accelerators = accelerators,
            isLlm = true,
            runtimeType = RuntimeType.LITERT_LM,
        )
        model.preProcess()
        return model
    }

    private fun groupTasksByCategory(): Map<String, List<Task>> {
        val tasks = getActiveCustomTasks().map { it.task }
        val categoryMap: Map<String, CategoryInfo> = tasks.associateBy { it.category.id }.mapValues { it.value.category }
        val groupedTasks = tasks.groupBy { it.category.id }
        val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
        for (categoryId in groupedTasks.keys) {
            val sortedTasks = groupedTasks[categoryId]!!.sortedWith { a, b ->
                if (categoryId == Category.LLM.id) {
                    val order = PREDEFINED_LLM_TASK_ORDER
                    val indexA = order.indexOf(a.id)
                    val indexB = order.indexOf(b.id)
                    if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
                    else if (indexA != -1) -1
                    else if (indexB != -1) 1
                    else {
                        val ca = categoryMap[a.id]!!
                        val cb = categoryMap[b.id]!!
                        getCategoryLabel(context, ca).compareTo(getCategoryLabel(context, cb))
                    }
                } else a.label.compareTo(b.label)
            }
            sortedTasks.forEachIndexed { index, task -> task.index = index }
            groupedSortedTasks[categoryId] = sortedTasks
        }
        return groupedSortedTasks
    }

    private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
        val stringRes = category.labelStringRes
        val label = category.label
        return if (stringRes != null) context.getString(stringRes)
        else if (label != null) label
        else context.getString(R.string.category_unlabeled)
    }

    private fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
        Log.d(TAG, "Checking model ${model.name} download status...")
        if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
            Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
            return ModelDownloadStatus(
                status = ModelDownloadStatusType.SUCCEEDED,
                receivedBytes = 0,
                totalBytes = 0,
            )
        }
        var status = ModelDownloadStatusType.NOT_DOWNLOADED
        var receivedBytes = 0L
        var totalBytes = 0L

        if (isModelPartiallyDownloaded(model = model)) {
            status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
            val tmpFilePath = File(
                getStorageDir(),
                "${model.normalizedName}/${model.downloadFileName}.$TMP_FILE_EXT"
            )
            val tmpFile = tmpFilePath
            receivedBytes = if (tmpFile.exists()) tmpFile.length() else 0
            totalBytes = model.totalBytes
            Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
        } else if (isModelDownloaded(model = model)) {
            status = ModelDownloadStatusType.SUCCEEDED
            Log.d(TAG, "${model.name} has been downloaded.")
        } else {
            Log.d(TAG, "${model.name} has not been downloaded.")
        }

        return ModelDownloadStatus(
            status = status,
            receivedBytes = receivedBytes,
            totalBytes = totalBytes,
        )
    }

    @androidx.annotation.VisibleForTesting
    fun isModelDownloaded(model: Model): Boolean {
        model.updatable = false
        if (checkIfModelDownloaded(model, model.version)) return true

        for (updatableFile in model.updatableModelFiles) {
            if (updatableFile.commitHash.isEmpty()) continue
            if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
                model.version = updatableFile.commitHash
                model.downloadFileName = updatableFile.fileName
                model.updatable = true
                return true
            }
        }
        return false
    }

    private fun checkIfModelDownloaded(
        model: Model,
        version: String,
        fileName: String = model.downloadFileName,
    ): Boolean {
        val root = getStorageDir()
        val modelRelativePath = listOf(model.normalizedName, version, fileName).joinToString(File.separator)
        val downloadedFileExists =
            fileName.isNotEmpty() &&
            ((model.localModelFilePathOverride.isEmpty() && File(root, modelRelativePath).exists()) ||
                (model.localModelFilePathOverride.isNotEmpty() && File(model.localModelFilePathOverride).exists()))

        val unzippedDirectoryExists =
            model.isZip &&
            model.unzipDir.isNotEmpty() &&
            File(root, listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator)).exists()

        return downloadedFileExists || unzippedDirectoryExists
    }

    private fun deleteFileFromStorage(fileName: String) {
        val file = File(getStorageDir(), fileName)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun deleteFilesFromImportDir(fileName: String) {
        val dir = getStorageDir()
        val prefixAbsolutePath = "${dir.absolutePath}${File.separator}$fileName"
        val importDir = File(dir, IMPORTS_DIR)
        val filesToDelete = importDir.listFiles { _, name ->
            File(importDir, name).absolutePath.startsWith(prefixAbsolutePath)
        } ?: arrayOf()
        for (file in filesToDelete) {
            Log.d(TAG, "Deleting file: ${file.name}")
            file.delete()
        }
    }

    private fun deleteDirFromStorage(dir: String) {
        val file = File(getStorageDir(), dir)
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    private fun updateModelInitializationStatus(
        model: Model,
        status: ModelInitializationStatusType,
        error: String = "",
    ) {
        val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
        val initializedBackends = curModelInstance[model.name]?.initializedBackends ?: setOf()
        val backend = model.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label
        )
        val newInitializedBackends =
            if (status == ModelInitializationStatusType.INITIALIZED) initializedBackends + backend
            else initializedBackends
        curModelInstance[model.name] = ModelInitializationStatus(
            status = status, error = error, initializedBackends = newInitializedBackends
        )
        _uiState.update { it.copy(modelInitializationStatus = curModelInstance) }
    }
}

private fun getAllowlistUrl(version: String): String {
    return "$ALLOWLIST_BASE_URL/${version}.json"
}
