package com.autoprocedure.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * sherpa-onnx VadAsr: Silero VAD + 非流式 OfflineRecognizer。
 * 点开始录音，点停止后打印识别文本。
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VoiceVadAsr"
        private const val REQUEST_RECORD_AUDIO = 200
        private const val SAMPLE_RATE = 16000
        private const val VAD_TYPE = 0
        private const val ASR_MODEL_TYPE = 0
    }

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var recordButton: Button

    private lateinit var vad: Vad
    private lateinit var recognizer: OfflineRecognizer

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val recognizedParts = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        resultText = findViewById(R.id.result_text)
        recordButton = findViewById(R.id.record_button)
        resultText.movementMethod = ScrollingMovementMethod()

        recordButton.setOnClickListener { onRecordClicked() }
        requestMicIfNeeded()
        loadModels()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.setText(R.string.status_mic_denied)
        }
    }

    override fun onDestroy() {
        isRecording = false
        audioRecord?.release()
        audioRecord = null
        if (::vad.isInitialized) {
            vad.release()
        }
        if (::recognizer.isInitialized) {
            recognizer.release()
        }
        super.onDestroy()
    }

    private fun loadModels() {
        recordButton.isEnabled = false
        statusText.setText(R.string.hint_loading)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                vad = Vad(
                    assetManager = assets,
                    config = getVadModelConfig(VAD_TYPE)!!,
                )
                recognizer = OfflineRecognizer(
                    assetManager = assets,
                    config = OfflineRecognizerConfig(
                        featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = getOfflineModelConfig(type = ASR_MODEL_TYPE)!!,
                    ),
                )
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) {
                    recordButton.isEnabled = true
                    statusText.setText(R.string.hint_ready)
                } else {
                    statusText.setText(R.string.status_load_failed)
                }
            }
        }
    }

    private fun onRecordClicked() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (!hasMicPermission()) {
            requestMicIfNeeded()
            statusText.setText(R.string.status_mic_denied)
            return
        }
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            statusText.setText(R.string.status_mic_denied)
            return
        }

        synchronized(recognizedParts) { recognizedParts.clear() }
        vad.reset()
        audioRecord = recorder
        isRecording = true
        recorder.startRecording()
        recordingThread = thread(name = "vad-asr-record") { processSamples() }

        statusText.setText(R.string.hint_recording)
        recordButton.setText(R.string.action_stop)
        Log.i(TAG, "开始语音输入")
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.isEnabled = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        lifecycleScope.launch(Dispatchers.IO) {
            recordingThread?.join()
            recordingThread = null
            val text = synchronized(recognizedParts) {
                recognizedParts.joinToString("").trim()
            }
            withContext(Dispatchers.Main) {
                printUserInput(text)
                recordButton.setText(R.string.action_start)
                recordButton.isEnabled = true
            }
        }
    }

    private fun processSamples() {
        val buffer = ShortArray(512)
        while (isRecording) {
            val n = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (n > 0) {
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)
                drainVad()
            }
        }
        vad.flush()
        drainVad()
    }

    private fun drainVad() {
        while (!vad.empty()) {
            val segment = vad.front()
            val text = runAsr(segment.samples)
            if (text.isNotBlank()) {
                synchronized(recognizedParts) { recognizedParts.add(text) }
            }
            vad.pop()
        }
    }

    private fun runAsr(samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        return text
    }

    private fun printUserInput(text: String) {
        statusText.setText(R.string.hint_ready)
        if (text.isBlank()) {
            if (existingUserInput().isEmpty()) {
                resultText.setText(R.string.hint_empty)
            }
            Log.i(TAG, "用户输入: (空)")
            return
        }
        val existing = existingUserInput()
        resultText.text = if (existing.isEmpty()) text else "$existing\n$text"
        Log.i(TAG, "用户输入: $text")
    }

    private fun existingUserInput(): String {
        val current = resultText.text?.toString().orEmpty().trim()
        return if (current.isEmpty() || current == getString(R.string.hint_empty)) {
            ""
        } else {
            current
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicIfNeeded() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
        }
    }
}
