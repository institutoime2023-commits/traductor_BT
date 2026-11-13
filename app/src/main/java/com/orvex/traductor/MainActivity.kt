package com.orvex.traductor

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.orvex.traductor.audio.SpanishVoiceOption
import com.orvex.traductor.audio.TtsBluetoothManager
import com.orvex.traductor.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("whisper_jni")
    }

    private external fun transcribeAudioNative(modelPath: String, audioData: FloatArray): String
    private external fun freeWhisperNative()

    private val translationManager = TranslationManager()
    private var ttsManager: TtsBluetoothManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        ttsManager?.updateAudioRouteStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            var ttsStatus by remember { mutableStateOf("Inicializando TTS...") }
            var btStatus by remember { mutableStateOf("Comprobando salida de audio...") }
            var isSpeaking by remember { mutableStateOf(false) }
            var availableVoices by remember { mutableStateOf<List<SpanishVoiceOption>>(emptyList()) }
            var selectedVoiceId by remember { mutableStateOf("") }

            DisposableEffect(Unit) {
                val manager = TtsBluetoothManager(
                    context = this@MainActivity,
                    onTtsStatusChanged = { ttsStatus = it },
                    onSpeakingStateChanged = { isSpeaking = it },
                    onBluetoothStatusChanged = { btStatus = it },
                    onVoicesLoaded = { voices ->
                        availableVoices = voices
                        voices.firstOrNull { it.isOffline }?.let {
                            selectedVoiceId = it.id
                        }
                    }
                )
                ttsManager = manager
                onDispose {
                    manager.release()
                    ttsManager = null
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(
                    ttsStatus = ttsStatus,
                    btStatus = btStatus,
                    isSpeaking = isSpeaking,
                    availableVoices = availableVoices,
                    selectedVoiceId = selectedVoiceId,
                    onSelectVoice = { option ->
                        selectedVoiceId = option.id
                        ttsManager?.selectVoice(option)
                    },
                    onSpeechRateChange = { rate ->
                        ttsManager?.setSpeechRate(rate)
                    },
                    onMuteToggle = { muted ->
                        ttsManager?.setMuted(muted)
                    },
                    onStopSpeech = {
                        ttsManager?.stopSpeaking()
                    },
                    onTestSpeech = {
                        ttsManager?.speak("Prueba de audio en español conectada correctamente.")
                    },
                    onDownloadWhisper = { onStatus -> downloadWhisperModel(onStatus) },
                    onDownloadTranslation = { onStatus ->
                        translationManager.downloadModel { _, msg ->
                            onStatus(msg)
                        }
                    },
                    onRecordAndTranslate = { onProgress, onFinish ->
                        startAudioRecordingAndTranscribe(onProgress) { english, spanish, state, latencyMs ->
                            if (spanish.isNotBlank() && !spanish.startsWith("(") && !spanish.startsWith("Por favor")) {
                                ttsManager?.speak(spanish)
                            }
                            onFinish(english, spanish, state, latencyMs)
                        }
                    }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun downloadWhisperModel(onStatus: (String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onStatus("Descargando modelo Whisper...") }
                val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
                val file = File(filesDir, "ggml-tiny.en.bin")

                if (file.exists() && file.length() > 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        onStatus("Modelo Whisper listo (${file.length() / (1024 * 1024)} MB).")
                    }
                    return@launch
                }

                URL(modelUrl).openStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) { onStatus("Modelo Whisper descargado con éxito.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Error al descargar Whisper: ${e.message}") }
            }
        }
    }

    private fun startAudioRecordingAndTranscribe(
        onProgress: (String) -> Unit,
        onFinish: (String, String, String, Long) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val modelFile = File(filesDir, "ggml-tiny.en.bin")
            if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "Modelo Whisper no encontrado.",
                        "Por favor descarga primero el modelo Whisper.",
                        "Error: Falta modelo",
                        0L
                    )
                }
                return@launch
            }

            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "Permiso de micrófono denegado.",
                        "Concede el permiso en los ajustes de la aplicación.",
                        "Sin permiso",
                        0L
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onProgress("Grabando audio ambiental (6 segundos)...")
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                maxOf(minBuffer, sampleRate * 2)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    onFinish("Error al inicializar micrófono.", "", "Error de hardware", 0L)
                }
                return@launch
            }

            val totalSamples = sampleRate * 6
            val shortBuffer = ShortArray(minBuffer / 2)
            val audioSamples = FloatArray(totalSamples)
            var recordedCount = 0

            try {
                audioRecord.startRecording()
                while (recordedCount < totalSamples) {
                    val toRead = minOf(shortBuffer.size, totalSamples - recordedCount)
                    val read = audioRecord.read(shortBuffer, 0, toRead)
                    if (read > 0) {
                        for (i in 0 until read) {
                            audioSamples[recordedCount + i] = shortBuffer[i] / 32768.0f
                        }
                        recordedCount += read
                    } else {
                        break
                    }
                }
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: Exception) {}
                audioRecord.release()
            }

            withContext(Dispatchers.Main) {
                onProgress("Reconociendo voz con Whisper.cpp...")
            }

            val tStart = SystemClock.elapsedRealtime()
            val rawRecognized = transcribeAudioNative(modelFile.absolutePath, audioSamples).trim()
            val whisperLatencyMs = SystemClock.elapsedRealtime() - tStart

            if (rawRecognized.isBlank() || rawRecognized == "[BLANK_AUDIO]") {
                withContext(Dispatchers.Main) {
                    onFinish(
                        "(No se detectó voz clara en inglés)",
                        "(Sin texto para traducir)",
                        "Audio sin voz detectada (${whisperLatencyMs} ms)",
                        whisperLatencyMs
                    )
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onProgress("Traduciendo al español con ML Kit...")
            }

            translationManager.translateText(rawRecognized) { translated ->
                coroutineScope.launch(Dispatchers.Main) {
                    onFinish(
                        rawRecognized,
                        translated,
                        "Completado (Whisper: ${whisperLatencyMs} ms)",
                        whisperLatencyMs
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        freeWhisperNative()
        translationManager.close()
    }
}

@Composable
fun MainScreen(
    ttsStatus: String,
    btStatus: String,
    isSpeaking: Boolean,
    availableVoices: List<SpanishVoiceOption>,
    selectedVoiceId: String,
    onSelectVoice: (SpanishVoiceOption) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onStopSpeech: () -> Unit,
    onTestSpeech: () -> Unit,
    onDownloadWhisper: ((String) -> Unit) -> Unit,
    onDownloadTranslation: ((String) -> Unit) -> Unit,
    onRecordAndTranslate: ((String) -> Unit, (String, String, String, Long) -> Unit) -> Unit
) {
    var whisperStatus by remember { mutableStateOf("Comprobar estado") }
    var translationStatus by remember { mutableStateOf("Pendiente") }
    var recognizedText by remember { mutableStateOf("Aquí aparecerá el texto reconocido por Whisper.") }
    var translatedText by remember { mutableStateOf("Aquí aparecerá el texto traducido por ML Kit.") }
    var processingState by remember { mutableStateOf("Esperando...") }
    var lastLatencyMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var selectedRate by remember { mutableStateOf(1.15f) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "TRADUCTOR V0.4 · VOZ Y BLUETOOTH", style = MaterialTheme.typography.titleLarge)

        // SALIDA DE AUDIO BLUETOOTH Y VOZ TTS
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "AUDIO Y SALIDA BLUETOOTH", style = MaterialTheme.typography.titleMedium)
                Text(text = btStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(text = ttsStatus, style = MaterialTheme.typography.bodySmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Leer traducciones en voz alta", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isVoiceEnabled,
                        onCheckedChange = { enabled ->
                            isVoiceEnabled = enabled
                            onMuteToggle(!enabled)
                        }
                    )
                }

                // Selector de velocidad de habla
                Text(text = "Velocidad de voz: ${selectedRate}x", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f, 1.15f, 1.35f, 1.5f).forEach { rate ->
                        FilterChip(
                            selected = selectedRate == rate,
                            onClick = {
                                selectedRate = rate
                                onSpeechRateChange(rate)
                            },
                            label = { Text("${rate}x") }
                        )
                    }
                }

                // Selector de voz española instalada
                if (availableVoices.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { voiceMenuExpanded = true }
                        ) {
                            val currentLabel = availableVoices.find { it.id == selectedVoiceId }?.label ?: "Seleccionar voz española"
                            Text(currentLabel)
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            availableVoices.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        onSelectVoice(option)
                                        voiceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onTestSpeech
                    ) {
                        Text("PROBAR VOZ BT")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = isSpeaking,
                        onClick = onStopSpeech
                    ) {
                        Text("DETENER VOZ")
                    }
                }
            }
        }

        // MODELOS OFFLINE
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "MODELOS LOCALES (OFFLINE)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing,
                        onClick = { onDownloadWhisper { whisperStatus = it } }
                    ) {
                        Text("WHISPER")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing,
                        onClick = { onDownloadTranslation { translationStatus = it } }
                    ) {
                        Text("TRADUCCIÓN")
                    }
                }
                Text(text = "Whisper: $whisperStatus", style = MaterialTheme.typography.bodySmall)
                Text(text = "Traducción: $translationStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

        // BOTÓN PRINCIPAL
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing,
            onClick = {
                isProcessing = true
                processingState = "Iniciando captura..."
                onRecordAndTranslate(
                    { progressMsg -> processingState = progressMsg },
                    { english, spanish, finalState, latencyMs ->
                        recognizedText = english
                        translatedText = spanish
                        processingState = finalState
                        lastLatencyMs = latencyMs
                        isProcessing = false
                    }
                )
            }
        ) {
            Text(if (isProcessing) "GRABANDO Y PROCESANDO..." else "GRABAR · 6S, TRADUCIR Y HABLAR")
        }

        Text(
            text = if (lastLatencyMs > 0) "Estado: $processingState | Tiempo Whisper: ${lastLatencyMs} ms" else "Estado: $processingState",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TEXTO ORIGINAL (INGLÉS)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = recognizedText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TRADUCCIÓN (ESPAÑOL)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = translatedText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
