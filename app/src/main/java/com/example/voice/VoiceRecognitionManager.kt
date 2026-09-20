package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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

    private var shouldKeepListening = false
    private var isCallModeActive = false
    private var callModeAlwaysIncrement = false

    fun setCallModeSettings(alwaysIncrement: Boolean) {
        this.callModeAlwaysIncrement = alwaysIncrement
    }

    fun detectCurrentAudioSource(): String {
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val isCall = try {
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
        mainHandler.post {
            _currentSource.value = detectCurrentAudioSource()
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

    private fun initRecognizerAndStart() {
        if (!shouldKeepListening) return

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceManager", "Speech recognition not available on device")
            _isListening.value = false
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                // Offline speech recognition
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start listening", e)
            _isListening.value = false
            scheduleRestart()
        }
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

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _currentSource.value = detectCurrentAudioSource()
        }

        override fun onBeginningOfSpeech() {
            _isListening.value = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize roughly from -2dB..10dB to 0..1
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _audioLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _audioLevel.value = 0f
        }

        override fun onError(error: Int) {
            Log.d("VoiceManager", "SpeechRecognizer error: $error")
            _audioLevel.value = 0f
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                // El reconocedor devuelve hasta N alternativas (EXTRA_MAX_RESULTS=3).
                // Probarlas en orden; la primera que produzca un comando válido gana.
                // Si NINGUNA produce comando, guardamos la primera como "frase
                // reconocida sin comando" para feedback del usuario.
                var firstPhrase = matches[0]
                var matched = false

                for (phrase in matches) {
                    val cmd = SpanishNumberParser.parse(
                        phrase,
                        callModeAlwaysIncrement = callModeAlwaysIncrement
                    )
                    if (cmd !is VoiceCommand.None) {
                        // ¡Encontramos un comando válido en una alternativa!
                        processRecognizedSpeech(phrase)
                        matched = true
                        break
                    }
                }

                if (!matched) {
                    // Ninguna alternativa fue un comando. Mostrar la primera
                    // para que el usuario vea qué escuchó la app y pueda
                    // corregir su pronunciación.
                    _lastDetectedPhrase.value = firstPhrase
                    _partialText.value = firstPhrase
                    Log.d("VoiceManager", "Frase sin comando reconocida: $firstPhrase")
                }
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
}
