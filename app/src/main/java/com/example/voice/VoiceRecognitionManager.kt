package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Estado del reconocimiento de voz, expuesto a la UI para mostrar
 * mensajes de diagnóstico accionables (no solo "no funciona").
 */
data class VoiceDiagnostic(
    val errorCode: Int = 0,
    val errorMessage: String = "",
    val consecutiveErrors: Int = 0,
    val needsLanguagePack: Boolean = false,
    val recognizerAvailable: Boolean = true,
    val permissionGranted: Boolean = true,
    val mode: String = "offline-es-ES"  // info para depurar
)

class VoiceRecognitionManager(
    private val context: Context,
    private val onCommandDetected: (VoiceCommand, String, String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentSource = MutableStateFlow("Voz directa")
    val currentSource: StateFlow<String> = _currentSource.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _lastDetectedPhrase = MutableStateFlow("")
    val lastDetectedPhrase: StateFlow<String> = _lastDetectedPhrase.asStateFlow()

    // Diagnóstico para la UI
    private val _diagnostic = MutableStateFlow(VoiceDiagnostic())
    val diagnostic: StateFlow<VoiceDiagnostic> = _diagnostic.asStateFlow()

    private var shouldKeepListening = false
    private var isCallModeActive = false
    private var callModeAlwaysIncrement = false

    // Estrategia de fallback para el intent: probamos varias combinaciones
    // (idioma + offline/online) hasta encontrar una que funcione.
    private data class RecognizerStrategy(
        val useOffline: Boolean,
        val language: String,
        val label: String
    )

    private val strategies = listOf(
        RecognizerStrategy(useOffline = true,  language = "es-ES", label = "offline-es-ES"),
        RecognizerStrategy(useOffline = true,  language = "es-419", label = "offline-es-419"),
        RecognizerStrategy(useOffline = true,  language = "es-MX", label = "offline-es-MX"),
        RecognizerStrategy(useOffline = true,  language = "es", label = "offline-es"),
        RecognizerStrategy(useOffline = false, language = "es-ES", label = "online-es-ES"),
        RecognizerStrategy(useOffline = false, language = "es-419", label = "online-es-419"),
        RecognizerStrategy(useOffline = false, language = "es", label = "online-es"),
        RecognizerStrategy(useOffline = false,
            language = Locale.getDefault().toLanguageTag(), label = "online-default"),
        RecognizerStrategy(useOffline = false, language = "en-US", label = "online-en-US")
    )

    private var currentStrategyIndex = 0

    fun setCallModeSettings(alwaysIncrement: Boolean) {
        this.callModeAlwaysIncrement = alwaysIncrement
    }

    fun detectCurrentAudioSource(): String {
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val isCall = try {
            @Suppress("DEPRECATION")
            telephonyManager?.callState == TelephonyManager.CALL_STATE_OFFHOOK ||
                    audioMode == AudioManager.MODE_IN_CALL
        } catch (_: Exception) {
            audioMode == AudioManager.MODE_IN_CALL
        }

        return when {
            isCall -> "Llamada Normal"
            audioMode == AudioManager.MODE_IN_COMMUNICATION -> "Llamada WhatsApp / VoIP"
            isCallModeActive -> "Llamada WhatsApp / VoIP"
            else -> "Voz directa"
        }
    }

    fun setCallModeActive(active: Boolean) {
        isCallModeActive = active
        _currentSource.value = detectCurrentAudioSource()
    }

    fun startListening() {
        shouldKeepListening = true
        // Reiniciamos la estrategia de fallback en cada inicio.
        currentStrategyIndex = 0
        mainHandler.post {
            _currentSource.value = detectCurrentAudioSource()
            // Comprobación previa: ¿está instalado el servicio de reconocimiento?
            // ¿Tenemos permiso de micrófono?
            val recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            val hasMicPermission = context.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!recognizerAvailable) {
                _diagnostic.value = _diagnostic.value.copy(
                    recognizerAvailable = false,
                    errorMessage = "No hay motor de reconocimiento de voz instalado. " +
                        "Instala la app de Google desde Play Store.",
                    mode = "no-recognizer"
                )
                Log.e("VoiceManager", "SpeechRecognizer.isRecognitionAvailable = false")
                _isListening.value = false
                return@post
            }
            if (!hasMicPermission) {
                _diagnostic.value = _diagnostic.value.copy(
                    permissionGranted = false,
                    errorMessage = "Falta permiso de micrófono (RECORD_AUDIO).",
                    mode = "no-permission"
                )
                Log.e("VoiceManager", "RECORD_AUDIO permission not granted")
                _isListening.value = false
                return@post
            }
            initRecognizerAndStart()
        }
    }

    fun stopListening() {
        shouldKeepListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e("VoiceManager", "Error stopping recognizer", e)
            } finally {
                speechRecognizer = null
                _isListening.value = false
                _audioLevel.value = 0f
            }
        }
    }

    /**
     * Construye el Intent según la estrategia actual.
     */
    private fun buildIntent(strategy: RecognizerStrategy): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, strategy.language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, strategy.language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            if (strategy.useOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun initRecognizerAndStart() {
        if (!shouldKeepListening) return

        val recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!recognizerAvailable) {
            _isListening.value = false
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        // Estrategia actual (con fallback entre strategies)
        if (currentStrategyIndex >= strategies.size) {
            currentStrategyIndex = 0
        }
        val strategy = strategies[currentStrategyIndex]

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            speechRecognizer?.startListening(buildIntent(strategy))

            // Actualizar diagnóstico con la estrategia actual.
            _diagnostic.value = _diagnostic.value.copy(
                mode = strategy.label,
                recognizerAvailable = true
            )

            _isListening.value = true
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start listening", e)
            _isListening.value = false
            scheduleRestart()
        }
    }

    /**
     * Avanza a la siguiente estrategia de fallback cuando la actual falla
     * con un error que indica que el idioma/modo no está disponible.
     */
    private fun tryNextStrategy(reason: String) {
        val old = currentStrategyIndex
        currentStrategyIndex = (currentStrategyIndex + 1) % strategies.size
        Log.w("VoiceManager",
            "Cambiando estrategia de reconocimiento: ${strategies[old].label} → " +
            "${strategies[currentStrategyIndex].label}  ($reason)")
    }

    private fun scheduleRestart() {
        if (shouldKeepListening) {
            mainHandler.postDelayed({
                if (shouldKeepListening) {
                    initRecognizerAndStart()
                }
            }, 600)
        }
    }

    private fun scheduleRestartShort() {
        if (shouldKeepListening) {
            mainHandler.postDelayed({
                if (shouldKeepListening) {
                    initRecognizerAndStart()
                }
            }, 1500)
        }
    }

    // ============================================================
    // Traducción de códigos de error de SpeechRecognizer a
    // mensajes accionables para el usuario.
    // ============================================================
    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
        SpeechRecognizer.ERROR_NETWORK -> "Sin conexión de red"
        SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
        SpeechRecognizer.ERROR_SERVER -> "Error del servidor de reconocimiento"
        SpeechRecognizer.ERROR_CLIENT -> "Error interno del cliente"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz (timeout)"
        SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ninguna palabra"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono"
        11 -> "Servidor desconectado (sin internet o servicio no disponible)"
        12 -> "Demasiadas peticiones, espera..."
        13 -> "Idioma español no disponible offline"
        14 -> "Servicio ocupado, reintentando..."
        else -> "Error desconocido: $error"
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _currentSource.value = detectCurrentAudioSource()
            // Resetear contador de errores consecutivos.
            _diagnostic.value = _diagnostic.value.copy(
                consecutiveErrors = 0,
                errorCode = 0,
                errorMessage = ""
            )
        }

        override fun onBeginningOfSpeech() {
            _isListening.value = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _audioLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _audioLevel.value = 0f
        }

        override fun onError(error: Int) {
            val msg = describeError(error)
            Log.d("VoiceManager", "SpeechRecognizer error: $error  ($msg)  " +
                "estrategia=${strategies[currentStrategyIndex].label}")

            val consecutive = _diagnostic.value.consecutiveErrors + 1

            // Detectar específicamente error 13 (idioma offline no disponible).
            val needsLangPack = error == 13 || (consecutive >= 3 && error == 11)

            _diagnostic.value = _diagnostic.value.copy(
                errorCode = error,
                errorMessage = msg,
                consecutiveErrors = consecutive,
                needsLanguagePack = needsLangPack,
                mode = strategies[currentStrategyIndex].label
            )

            _audioLevel.value = 0f

            // Estrategia de fallback:
            //  - Error 13 (idioma offline no disponible) → cambiar estrategia.
            //  - Error 11 (servidor desconectado) → cambiar estrategia.
            //  - Error 9 (permisos) → no reintentar, el usuario debe otorgar permiso.
            //  - Otros → solo reintentar.
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shouldKeepListening = false
                    _isListening.value = false
                    return
                }
                13, 11 -> {
                    tryNextStrategy("error=$error")
                    scheduleRestartShort()
                }
                else -> {
                    // Si ya llevamos varios errores seguidos, también probamos
                    // otra estrategia antes de seguir reintentando ciegamente.
                    if (consecutive >= 5) {
                        tryNextStrategy("consecutive=$consecutive")
                    }
                    scheduleRestart()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                var firstPhrase = matches[0]
                var matched = false

                for (phrase in matches) {
                    val cmd = SpanishNumberParser.parse(
                        phrase,
                        callModeAlwaysIncrement = callModeAlwaysIncrement
                    )
                    if (cmd !is VoiceCommand.None) {
                        processRecognizedSpeech(phrase)
                        matched = true
                        break
                    }
                }

                if (!matched) {
                    _lastDetectedPhrase.value = firstPhrase
                    _partialText.value = firstPhrase
                    Log.d("VoiceManager", "Frase sin comando reconocida: $firstPhrase")
                }

                // Resetear errores consecutivos porque sí se obtuvo un resultado.
                _diagnostic.value = _diagnostic.value.copy(
                    consecutiveErrors = 0,
                    errorCode = 0,
                    errorMessage = "",
                    needsLanguagePack = false
                )
            }
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val phrase = matches[0]
                _partialText.value = phrase
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun processRecognizedSpeech(phrase: String) {
        _lastDetectedPhrase.value = phrase
        _partialText.value = phrase
        val source = detectCurrentAudioSource()
        _currentSource.value = source

        val command = SpanishNumberParser.parse(phrase, callModeAlwaysIncrement = callModeAlwaysIncrement)
        if (command !is VoiceCommand.None) {
            onCommandDetected(command, phrase, source)
        }
    }

    /**
     * Resetea el contador de errores consecutivos (cuando el usuario
     * resuelve manualmente el problema y quiere reintentar).
     */
    fun resetDiagnostics() {
        _diagnostic.value = VoiceDiagnostic()
        currentStrategyIndex = 0
    }
}
