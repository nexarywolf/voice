package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.CounterViewModel
import com.example.ui.components.HistoryBottomSheet
import com.example.ui.components.HistoryItemCard
import com.example.ui.components.SetDirectDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterScreen(
    viewModel: CounterViewModel,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit
) {
    val context = LocalContext.current
    val count by viewModel.count.collectAsStateWithLifecycle()
    val isListening by viewModel.isVoiceActive.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isBackgroundServiceRunning.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val lastPhrase by viewModel.lastSpokenPhrase.collectAsStateWithLifecycle()
    val lastCommandAction by viewModel.lastCommandAction.collectAsStateWithLifecycle()
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val callAlwaysIncrement by viewModel.callAlwaysIncrement.collectAsStateWithLifecycle()
    val recentHistory by viewModel.recentHistory.collectAsStateWithLifecycle()
    val allHistory by viewModel.allHistory.collectAsStateWithLifecycle()
    val stats by viewModel.statistics.collectAsStateWithLifecycle()

    var showHistorySheet by remember { mutableStateOf(false) }
    var showSetDirectDialog by remember { mutableStateOf(false) }

    // Permission launcher for Microphone and Notification
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val recordGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] == true
        if (recordGranted) {
            viewModel.toggleVoiceListening()
        }
    }

    fun requestVoicePermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.READ_PHONE_STATE)

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.toggleVoiceListening()
        } else {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Contador por Voz",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        // Offline Badge
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.WifiOff,
                                    contentDescription = "Sin conexión",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Export CSV quick button
                    IconButton(
                        onClick = { viewModel.shareCsv(context) },
                        modifier = Modifier.testTag("top_bar_csv_btn")
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = "Exportar CSV"
                        )
                    }

                    // Toggle Dark Theme
                    IconButton(
                        onClick = onToggleDarkTheme,
                        modifier = Modifier.testTag("toggle_dark_theme_btn")
                    ) {
                        Icon(
                            if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Cambiar tema"
                        )
                    }

                    // Open History BottomSheet
                    BadgedBox(
                        badge = {
                            if (allHistory.isNotEmpty()) {
                                Badge { Text("${allHistory.size}") }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        IconButton(
                            onClick = { showHistorySheet = true },
                            modifier = Modifier.testTag("open_history_btn")
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Ver historial completo"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Call & WhatsApp Background Service Card
            CallModeCard(
                isServiceRunning = isServiceRunning,
                callAlwaysIncrement = callAlwaysIncrement,
                currentSource = currentSource,
                onToggleService = { viewModel.toggleBackgroundService(context) },
                onToggleIncrementSetting = { viewModel.setCallAlwaysIncrement(!callAlwaysIncrement) }
            )

            // Main Counter Display Section
            HeroCounterDisplay(
                count = count,
                isListening = isListening || isServiceRunning,
                audioLevel = audioLevel,
                currentSource = currentSource,
                lastPhrase = lastPhrase,
                lastCommandAction = lastCommandAction,
                onSetDirectClicked = { showSetDirectDialog = true }
            )

            // Primary Voice & Reset Controls
            ActionButtonsSection(
                isListening = isListening,
                onToggleListening = { requestVoicePermissionsAndStart() },
                onReset = { viewModel.manualReset() },
                onIncrement = { viewModel.manualIncrement(1) },
                onDecrement = { viewModel.manualIncrement(-1) },
                onSetDirect = { showSetDirectDialog = true }
            )

            // Quick Voice Command Test Simulator (Satisfies prompt testability: 'uno', '150', 'reset')
            QuickVoiceTestChips(
                onSimulatePhrase = { phrase, source ->
                    viewModel.simulatePhrase(phrase, source)
                }
            )

            // Recent History Section
            RecentHistorySection(
                recentList = recentHistory,
                totalCount = allHistory.size,
                onViewAll = { showHistorySheet = true },
                onExportCsv = { viewModel.shareCsv(context) }
            )
        }
    }

    if (showHistorySheet) {
        HistoryBottomSheet(
            viewModel = viewModel,
            historyList = allHistory,
            stats = stats,
            onDismiss = { showHistorySheet = false }
        )
    }

    if (showSetDirectDialog) {
        SetDirectDialog(
            currentValue = count,
            onDismiss = { showSetDirectDialog = false },
            onConfirm = { target ->
                viewModel.setDirectValue(target)
                showSetDirectDialog = false
            }
        )
    }
}

@Composable
fun CallModeCard(
    isServiceRunning: Boolean,
    callAlwaysIncrement: Boolean,
    currentSource: String,
    onToggleService: () -> Unit,
    onToggleIncrementSetting: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isServiceRunning)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("call_mode_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceRunning) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PhoneInTalk,
                            contentDescription = "Llamadas y WhatsApp",
                            tint = if (isServiceRunning) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Llamadas normales y WhatsApp",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isServiceRunning) "Activo en segundo plano ($currentSource)"
                            else "Toca para contar durante llamadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isServiceRunning,
                    onCheckedChange = { onToggleService() },
                    modifier = Modifier.testTag("background_service_switch")
                )
            }

            if (isServiceRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable { onToggleIncrementSetting() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "En llamada: Aumentar siempre al oír números (+1)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Checkbox(
                        checked = callAlwaysIncrement,
                        onCheckedChange = { onToggleIncrementSetting() }
                    )
                }
            }
        }
    }
}

@Composable
fun HeroCounterDisplay(
    count: Int,
    isListening: Boolean,
    audioLevel: Float,
    currentSource: String,
    lastPhrase: String,
    lastCommandAction: String = "",
    onSetDirectClicked: () -> Unit
) {
    // Pulse animation for listening state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.08f + (audioLevel * 0.15f) else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_counter_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Audio source pill
            Surface(
                shape = CircleShape,
                color = if (isListening) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening) Color(0xFF10B981)
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                    Text(
                        text = if (isListening) "Escuchando: $currentSource" else "Micrófono inactivo",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Big Animated Number
            Box(
                modifier = Modifier
                    .scale(if (isListening) pulseScale else 1f)
                    .clickable { onSetDirectClicked() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { height -> height } + fadeIn())
                                .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically { height -> -height } + fadeIn())
                                .togetherWith(slideOutVertically { height -> height } + fadeOut())
                        }.using(SizeTransform(clip = false))
                    },
                    label = "counterTextAnimation"
                ) { targetNumber ->
                    Text(
                        text = "$targetNumber",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("counter_display_number")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Last Spoken Phrase + Command Action feedback
            if (lastPhrase.isNotBlank()) {
                val isRecognized = lastCommandAction.isNotEmpty() &&
                    lastCommandAction != "No reconocido"

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = "Escuché: \"$lastPhrase\"",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("last_recognized_text")
                        )
                    }

                    if (lastCommandAction.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isRecognized) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            }
                        ) {
                            Text(
                                text = if (isRecognized) "Acción: $lastCommandAction"
                                       else "No reconocido como comando",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isRecognized) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("last_command_action")
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Di \"uno\" para +1, o una cifra como \"150\", \"ciento cincuenta\", o \"reset\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ActionButtonsSection(
    isListening: Boolean,
    onToggleListening: () -> Unit,
    onReset: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSetDirect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reset Button
        OutlinedIconButton(
            onClick = onReset,
            modifier = Modifier
                .size(54.dp)
                .testTag("reset_button")
        ) {
            Icon(
                Icons.Default.RestartAlt,
                contentDescription = "Reset el contador",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        }

        // -1 Button
        FilledTonalIconButton(
            onClick = onDecrement,
            modifier = Modifier
                .size(54.dp)
                .testTag("decrement_button")
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Restar 1",
                modifier = Modifier.size(26.dp)
            )
        }

        // Huge Main Mic Button
        Button(
            onClick = onToggleListening,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(76.dp)
                .testTag("mic_toggle_button")
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (isListening) "Detener escucha" else "Iniciar escucha",
                modifier = Modifier.size(34.dp)
            )
        }

        // +1 Button
        FilledTonalIconButton(
            onClick = onIncrement,
            modifier = Modifier
                .size(54.dp)
                .testTag("increment_button")
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Sumar 1",
                modifier = Modifier.size(26.dp)
            )
        }

        // Direct target number
        OutlinedIconButton(
            onClick = onSetDirect,
            modifier = Modifier
                .size(54.dp)
                .testTag("set_direct_button")
        ) {
            Icon(
                Icons.Default.Dialpad,
                contentDescription = "Fijar cifra",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun QuickVoiceTestChips(
    onSimulatePhrase: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Probar comandos de voz instantáneos:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val samples = listOf(
                "uno" to "🗣️ \"uno\" (+1)",
                "150" to "🗣️ \"150\" (fijar)",
                "reset el contador" to "🔄 \"reset el contador\"",
                "ciento cincuenta" to "🗣️ \"ciento cincuenta\"",
                "+5" to "➕ \"suma 5\"",
                "dos" to "📞 WA: \"dos\""
            )

            items(samples) { (phrase, label) ->
                val isCallSample = label.contains("WA")
                SuggestionChip(
                    onClick = {
                        val source = if (isCallSample) "Llamada WhatsApp / VoIP" else "Voz directa"
                        onSimulatePhrase(phrase, source)
                    },
                    label = { Text(label) },
                    modifier = Modifier.testTag("test_chip_$phrase")
                )
            }
        }
    }
}

@Composable
fun RecentHistorySection(
    recentList: List<com.example.data.model.InteractionEntry>,
    totalCount: Int,
    onViewAll: () -> Unit,
    onExportCsv: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_history_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Historial reciente",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$totalCount interacciones guardadas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onExportCsv,
                        modifier = Modifier.testTag("recent_export_csv_btn")
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Exportar CSV")
                    }

                    TextButton(
                        onClick = onViewAll,
                        modifier = Modifier.testTag("see_all_history_btn")
                    ) {
                        Text("Ver todo")
                    }
                }
            }

            if (recentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aún no hay interacciones registradas.\nDi \"uno\" o pulsa el botón del micrófono.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentList.take(3).forEach { item ->
                        HistoryItemCard(
                            entry = item,
                            onDelete = {}
                        )
                    }
                }
            }
        }
    }
}
