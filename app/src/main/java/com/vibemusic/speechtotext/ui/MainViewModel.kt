package com.vibemusic.speechtotext.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.pipeline.PipelineSettings
import com.vibemusic.speechtotext.pipeline.PipelineUiState
import com.vibemusic.speechtotext.pipeline.SpeechPipeline
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing SpeechPipeline lifecycle and state across configuration changes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline: SpeechPipeline = SpeechPipeline(
        context = application.applicationContext,
        scope = viewModelScope
    )

    val uiState: StateFlow<PipelineUiState> = pipeline.uiState

    init {
        viewModelScope.launch {
            pipeline.initialize()
        }
    }

    fun selectLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            pipeline.switchLanguage(language)
        }
    }

    fun importModel(language: SpeechLanguage, uri: Uri) {
        viewModelScope.launch {
            pipeline.importModelZip(language, uri)
        }
    }

    fun downloadModel(language: SpeechLanguage, url: String) {
        viewModelScope.launch {
            pipeline.downloadModel(language, url)
        }
    }

    fun scanAndInstallModel(language: SpeechLanguage) {
        viewModelScope.launch {
            pipeline.scanAndInstallModel(language)
        }
    }

    fun startListening(): Result<Unit> {
        return pipeline.startListening()
    }

    fun stopListening() {
        pipeline.stopListening()
    }

    fun toggleListening() {
        if (uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun clearTranscript() {
        pipeline.clearTranscript()
    }

    fun updateSettings(settings: PipelineSettings) {
        pipeline.updateSettings(settings)
    }

    fun setBypassVad(bypass: Boolean) {
        pipeline.setBypassVad(bypass)
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.release()
    }
}

