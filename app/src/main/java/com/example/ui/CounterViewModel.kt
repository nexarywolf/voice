package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.InteractionEntry
import com.example.data.repository.CounterRepository
import com.example.service.VoiceCounterService
import com.example.voice.SpanishNumberParser
import com.example.voice.VoiceCommand
import com.example.voice.VoiceDiagnostic
import com.example.voice.VoiceRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class CounterStatistics(
    val totalInteractions: Int = 0,
    val maxNumberReached: Int = 0,
    val directVoiceCount: Int = 0,
    val phoneCallCount: Int = 0,
    val whatsAppCallCount: Int = 0,
    val resetsCount: Int = 0
)

class CounterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CounterRepository.getInstance(application)
    private val vibrator = application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Core states
    val count: StateFlow<Int> = VoiceCounterService.currentCount
    val isBackgroundServiceRunning: StateFlow<Boolean> = VoiceCounterService.isRunning

    private val _isVoiceActive = MutableStateFlow(false)
    val isVoiceActive: StateFlow<Boolean> = _isVoiceActive.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _lastSpokenPhrase = MutableStateFlow("")
    val lastSpokenPhrase: StateFlow<String> = _lastSpokenPhrase.asStateFlow()

    private val _lastCommandAction = MutableStateFlow("")
    val lastCommandAction: StateFlow<String> = _lastCommandAction.asStateFlow()

    private val _currentSource = MutableStateFlow("Voz directa")
    val currentSource: StateFlow<String> = _currentSource.asStateFlow()

    // Por defecto FALSE: cualquier número dicho ("80", "150", "ciento cincuenta")
    // FIJA el contador a ese valor. Solo cuando el usuario activa el "Modo
    // llamadas" en la UI, este flag pasa a true para que en llamadas cada
    // utterancia cuente como "+1" (pensado para contar en voz alta: "uno,
    // dos, tres" → +1, +1, +1).
    private val _callAlwaysIncrement = MutableStateFlow(false)
    val callAlwaysIncrement: StateFlow<Boolean> = _callAlwaysIncrement.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    // Estado "vacío" por defecto; se rellena en init{} cuando se crea el manager.
    private val _voiceDiagnostic = MutableStateFlow(VoiceDiagnostic())
    val voiceDiagnostic: StateFlow<VoiceDiagnostic> = _voiceDiagnostic.asStateFlow()

    // History flows
    val recentHistory: StateFlow<List<InteractionEntry>> = repository.recentInteractions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHistory: StateFlow<List<InteractionEntry>> = repository.allInteractions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalInteractions: StateFlow<Int> = repository.totalCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val statistics: StateFlow<CounterStatistics> = repository.allInteractions.map { list ->
        var maxVal = count.value
        var directVoice = 0
        var phoneCall = 0
        var whatsApp = 0
        var resets = 0

        for (item in list) {
            if (item.newValue > maxVal) maxVal = item.newValue
            if (item.actionType == "REINICIO") resets++
            when {
                item.source.contains("WhatsApp", ignoreCase = true) || item.source.contains("VoIP", ignoreCase = true) -> whatsApp++
                item.source.contains("Normal", ignoreCase = true) || item.source.contains("Llamada", ignoreCase = true) -> phoneCall++
                else -> directVoice++
            }
        }

        CounterStatistics(
            totalInteractions = list.size,
            maxNumberReached = maxVal,
            directVoiceCount = directVoice,
            phoneCallCount = phoneCall,
            whatsAppCallCount = whatsApp,
            resetsCount = resets
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CounterStatistics())

    private var localVoiceManager: VoiceRecognitionManager? = null

    init {
        // Setup local voice manager for active screen
        localVoiceManager = VoiceRecognitionManager(application) { command, phrase, source ->
            processCommand(command, phrase, source)
        }
        localVoiceManager?.setCallModeSettings(_callAlwaysIncrement.value)

        // Mostrar SIEMPRE la última frase detectada (incluso si no genera
        // comando) para que el usuario vea qué entendió la app.
        viewModelScope.launch {
            localVoiceManager?.lastDetectedPhrase?.collect { phrase ->
                if (phrase.isNotBlank()) {
                    _lastSpokenPhrase.value = phrase
                }
            }
        }

        // Reenviar el diagnóstico del manager a nuestro StateFlow para la UI.
        viewModelScope.launch {
            localVoiceManager?.diagnostic?.collect { diag ->
                _voiceDiagnostic.value = diag
            }
        }
    }

    fun setCallAlwaysIncrement(value: Boolean) {
        _callAlwaysIncrement.value = value
        localVoiceManager?.setCallModeSettings(value)
    }

    /**
     * Abre la pantalla de Configuración → Voz e idiomas del sistema,
     * donde el usuario puede descargar el paquete de español offline
     * para el reconocimiento de voz.
     */
    fun openVoiceInputSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Ve a: Reconocimiento de voz offline → Español (España)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            // Fallback: abrir los settings generales de la app.
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "No se pudo abrir la configuración de voz",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Abre los detalles de la app en Configuración, donde el usuario
     * puede revisar/otorgar el permiso de micrófono manualmente.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir la configuración", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reinicia el estado de diagnóstico después de que el usuario haya
     * hecho un cambio (ej: instalar el idioma offline).
     */
    fun resetVoiceDiagnostics() {
        localVoiceManager?.resetDiagnostics()
        _voiceDiagnostic.value = VoiceDiagnostic()
    }

    fun toggleVoiceListening() {
        val newState = !_isVoiceActive.value
        _isVoiceActive.value = newState
        if (newState) {
            localVoiceManager?.startListening()
            triggerHaptic(50)
        } else {
            localVoiceManager?.stopListening()
            _audioLevel.value = 0f
        }
    }

    fun toggleBackgroundService(context: Context) {
        if (VoiceCounterService.isRunning.value) {
            VoiceCounterService.stopService(context)
            Toast.makeText(context, "Modo llamadas en segundo plano detenido", Toast.LENGTH_SHORT).show()
        } else {
            VoiceCounterService.startService(
                context,
                initialCount = count.value,
                callModeAlwaysIncrement = _callAlwaysIncrement.value
            )
            Toast.makeText(context, "Modo llamadas y WhatsApp activo en segundo plano", Toast.LENGTH_LONG).show()
        }
    }

    fun manualIncrement(delta: Int = 1) {
        viewModelScope.launch {
            val prev = count.value
            val next = (prev + delta).coerceAtLeast(0)
            VoiceCounterService._currentCount.value = next
            repository.recordInteraction(
                spokenText = if (delta > 0) "+$delta" else "$delta",
                actionType = "MANUAL",
                previousValue = prev,
                newValue = next,
                source = "Control Manual"
            )
            triggerHaptic(40)
        }
    }

    fun manualReset() {
        viewModelScope.launch {
            val prev = count.value
            VoiceCounterService._currentCount.value = 0
            repository.recordInteraction(
                spokenText = "reset",
                actionType = "REINICIO",
                previousValue = prev,
                newValue = 0,
                source = "Control Manual"
            )
            triggerHaptic(120)
        }
    }

    fun setDirectValue(value: Int) {
        viewModelScope.launch {
            val prev = count.value
            VoiceCounterService._currentCount.value = value
            repository.recordInteraction(
                spokenText = "$value",
                actionType = "VALOR_DIRECTO",
                previousValue = prev,
                newValue = value,
                source = "Control Manual"
            )
            triggerHaptic(60)
        }
    }

    fun simulatePhrase(phrase: String, simulatedSource: String = "Voz directa") {
        val command = SpanishNumberParser.parse(phrase, _callAlwaysIncrement.value)
        processCommand(command, phrase, simulatedSource)
    }

    private fun processCommand(command: VoiceCommand, phrase: String, source: String) {
        viewModelScope.launch {
            val prev = count.value
            val next: Int
            val actionType: String

            when (command) {
                is VoiceCommand.Reset -> {
                    next = 0
                    actionType = "REINICIO"
                    _lastCommandAction.value = "Reinicio a 0"
                    triggerHaptic(150)
                }
                is VoiceCommand.Increment -> {
                    next = prev + command.amount
                    actionType = "INCREMENTO"
                    _lastCommandAction.value = "+${command.amount}  →  $next"
                    triggerHaptic(40)
                }
                is VoiceCommand.Decrement -> {
                    next = (prev - command.amount).coerceAtLeast(0)
                    actionType = "DECREMENTO"
                    _lastCommandAction.value = "−${command.amount}  →  $next"
                    triggerHaptic(40)
                }
                is VoiceCommand.SetDirect -> {
                    next = command.targetValue
                    actionType = "VALOR_DIRECTO"
                    _lastCommandAction.value = "Fijar a $next"
                    triggerHaptic(80)
                }
                VoiceCommand.None -> {
                    _lastCommandAction.value = "No reconocido"
                    return@launch
                }
            }

            VoiceCounterService._currentCount.value = next
            _lastSpokenPhrase.value = phrase
            _currentSource.value = source

            repository.recordInteraction(
                spokenText = phrase,
                actionType = actionType,
                previousValue = prev,
                newValue = next,
                source = source
            )
        }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch {
            repository.deleteInteraction(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun shareCsv(context: Context) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val file = repository.exportCsvFile()
                val uri = repository.getFileUri(file)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Historial de Contador por Voz")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Exportar historial CSV")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Error exportando CSV: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                _isExporting.value = false
            }
        }
    }

    suspend fun getCsvContent(): String {
        return repository.generateCsvString()
    }

    private fun triggerHaptic(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        localVoiceManager?.stopListening()
    }
}
