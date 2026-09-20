package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.repository.CounterRepository
import com.example.voice.SpanishNumberParser
import com.example.voice.VoiceCommand
import com.example.voice.VoiceRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceCounterService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: CounterRepository
    private var voiceManager: VoiceRecognitionManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCounterService = this@VoiceCounterService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        repository = CounterRepository.getInstance(applicationContext)
        createNotificationChannel()

        voiceManager = VoiceRecognitionManager(this) { command, phrase, source ->
            handleCommand(command, phrase, source)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialCount = intent?.getIntExtra(EXTRA_CURRENT_COUNT, currentCount.value) ?: currentCount.value
        _currentCount.value = initialCount

        val notification = buildNotification(initialCount)
        startForeground(NOTIFICATION_ID, notification)

        val alwaysIncrement = intent?.getBooleanExtra(EXTRA_CALL_ALWAYS_INCREMENT, false) ?: false
        voiceManager?.setCallModeSettings(alwaysIncrement)
        voiceManager?.setCallModeActive(true)
        voiceManager?.startListening()

        _isRunning.value = true
        return START_STICKY
    }

    private fun handleCommand(command: VoiceCommand, phrase: String, source: String) {
        serviceScope.launch {
            val prev = _currentCount.value
            val next: Int
            val actionType: String

            when (command) {
                is VoiceCommand.Reset -> {
                    next = 0
                    actionType = "REINICIO"
                }
                is VoiceCommand.Increment -> {
                    next = prev + command.amount
                    actionType = "INCREMENTO"
                }
                is VoiceCommand.Decrement -> {
                    next = (prev - command.amount).coerceAtLeast(0)
                    actionType = "DECREMENTO"
                }
                is VoiceCommand.SetDirect -> {
                    next = command.targetValue
                    actionType = "VALOR_DIRECTO"
                }
                VoiceCommand.None -> return@launch
            }

            _currentCount.value = next
            _lastSpokenPhrase.value = phrase
            _lastAudioSource.value = source

            repository.recordInteraction(
                spokenText = phrase,
                actionType = actionType,
                previousValue = prev,
                newValue = next,
                source = source
            )

            updateNotification(next)
        }
    }

    fun applyManualAdjustment(delta: Int) {
        serviceScope.launch {
            val prev = _currentCount.value
            val next = (prev + delta).coerceAtLeast(0)
            _currentCount.value = next
            repository.recordInteraction(
                spokenText = if (delta > 0) "+$delta" else "$delta",
                actionType = "MANUAL",
                previousValue = prev,
                newValue = next,
                source = "Control Manual"
            )
            updateNotification(next)
        }
    }

    fun resetCount() {
        serviceScope.launch {
            val prev = _currentCount.value
            _currentCount.value = 0
            repository.recordInteraction(
                spokenText = "reset",
                actionType = "REINICIO",
                previousValue = prev,
                newValue = 0,
                source = "Control Manual"
            )
            updateNotification(0)
        }
    }

    fun setCountDirectly(value: Int) {
        serviceScope.launch {
            val prev = _currentCount.value
            _currentCount.value = value
            repository.recordInteraction(
                spokenText = "$value",
                actionType = "VALOR_DIRECTO",
                previousValue = prev,
                newValue = value,
                source = "Control Manual"
            )
            updateNotification(value)
        }
    }

    fun simulateVoicePhrase(phrase: String) {
        voiceManager?.processRecognizedSpeech(phrase)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Contador por Voz Activo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitorea números y comandos de voz durante llamadas y fondo"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(count: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Contador por Voz: $count")
            .setContentText("Escuchando comandos (Llamadas, WhatsApp y voz directa)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(count: Int) {
        val notification = buildNotification(count)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager?.stopListening()
        serviceScope.cancel()
        _isRunning.value = false
    }

    companion object {
        const val CHANNEL_ID = "voice_counter_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_CURRENT_COUNT = "extra_count"
        const val EXTRA_CALL_ALWAYS_INCREMENT = "extra_call_always_increment"

        val _currentCount = MutableStateFlow(0)
        val currentCount: StateFlow<Int> = _currentCount.asStateFlow()

        val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        val _lastSpokenPhrase = MutableStateFlow("")
        val lastSpokenPhrase: StateFlow<String> = _lastSpokenPhrase.asStateFlow()

        val _lastAudioSource = MutableStateFlow("Voz directa")
        val lastAudioSource: StateFlow<String> = _lastAudioSource.asStateFlow()

        fun startService(context: Context, initialCount: Int, callModeAlwaysIncrement: Boolean) {
            val intent = Intent(context, VoiceCounterService::class.java).apply {
                putExtra(EXTRA_CURRENT_COUNT, initialCount)
                putExtra(EXTRA_CALL_ALWAYS_INCREMENT, callModeAlwaysIncrement)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VoiceCounterService::class.java)
            context.stopService(intent)
        }
    }
}
