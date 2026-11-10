package com.didi.dimina.api.media

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.didi.dimina.api.APIResult
import com.didi.dimina.api.AsyncResult
import com.didi.dimina.api.BaseApiHandler
import com.didi.dimina.api.NoneResult
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.PathUtils
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 录音会话状态机。 */
internal enum class RecorderState { IDLE, RECORDING, PAUSED }

/**
 * Recorder 的纯校验/映射规则（不依赖 Android runtime，供单测覆盖）。
 */
internal object RecorderRules {
    /** 微信 RecorderManager 支持的采样率。 */
    val VALID_SAMPLE_RATES = setOf(8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000)
    const val MIN_ENCODE_BIT_RATE = 16000
    const val MAX_ENCODE_BIT_RATE = 320000
    const val DEFAULT_DURATION_MS = 60000L
    const val MAX_DURATION_MS = 600000L

    /** 契约 v1:仅 'aac' | 'wav'，其余 fail。 */
    val VALID_FORMATS = setOf("aac", "wav")

    /** Recorder.onError 的 errCode（契约未钉死分档，统一系统错误档）。 */
    const val ERR_SYSTEM = 10001

    /** 全部合法返回 null，否则返回 errMsg detail（`Recorder.start:fail <detail>`）。 */
    fun validateStart(format: String, sampleRate: Int, channels: Int, encodeBitRate: Int): String? = when {
        format !in VALID_FORMATS -> "invalid format"
        sampleRate !in VALID_SAMPLE_RATES -> "invalid sampleRate"
        channels !in 1..2 -> "invalid numberOfChannels"
        encodeBitRate !in MIN_ENCODE_BIT_RATE..MAX_ENCODE_BIT_RATE -> "invalid encodeBitRate"
        else -> null
    }

    /** duration ≤ 0 用默认 60000，超过上限 600000 收敛到上限（微信语义）。 */
    fun clampDurationMs(durationMs: Long): Long = when {
        durationMs <= 0 -> DEFAULT_DURATION_MS
        durationMs > MAX_DURATION_MS -> MAX_DURATION_MS
        else -> durationMs
    }

    /** wire 名（Recorder.onXxx / Recorder.offXxx 的 Xxx 部分）→ 规范事件名；未知返回 null。 */
    fun eventForWire(op: String): String? = when (op) {
        "onStart", "offStart" -> "start"
        "onPause", "offPause" -> "pause"
        "onStop", "offStop" -> "stop"
        "onError", "offError" -> "error"
        "onInterruptionBegin", "offInterruptionBegin" -> "interruptionBegin"
        "onInterruptionEnd", "offInterruptionEnd" -> "interruptionEnd"
        else -> null
    }

    /** pause/resume/stop 的状态机校验：非法返回 errMsg detail。 */
    fun validateOp(state: RecorderState, op: String): String? = when (op) {
        "pause" -> if (state != RecorderState.RECORDING) "no recording" else null
        "resume" -> if (state != RecorderState.PAUSED) "no paused recording" else null
        "stop" -> if (state == RecorderState.IDLE) "no recording" else null
        else -> null
    }
}

/**
 * 录音活跃时长累计（pause 不计时）。now 注入便于单测，运行时用 SystemClock。
 */
internal class ElapsedTimer(private val now: () -> Long) {
    private var anchor = -1L
    private var accumulated = 0L

    fun start() {
        anchor = now()
    }

    fun pause() {
        if (anchor >= 0) {
            accumulated += now() - anchor
            anchor = -1
        }
    }

    fun resume() {
        if (anchor < 0) anchor = now()
    }

    fun elapsedMs(): Long = accumulated + if (anchor >= 0) now() - anchor else 0

    fun stop(): Long {
        pause()
        return accumulated
    }
}

/** 44 字节 PCM16 WAV 头（RIFF/WAVE，契约 wav 格式产物）。 */
internal fun buildWavHeader(sampleRate: Int, channels: Int, dataSize: Int): ByteArray {
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(36 + dataSize)
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1) // PCM
        .putShort(channels.toShort())
        .putInt(sampleRate)
        .putInt(byteRate)
        .putShort(blockAlign.toShort())
        .putShort(16) // bits per sample
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(dataSize)
        .array()
}

/**
 * wav 引擎：AudioRecord 直写 PCM，落盘时流式写 WAV（44 字节占位头，stop 时回填长度）。
 * 读取线程始终消费缓冲（pause 时只丢不写），避免系统音频缓冲溢出；帧数即真实时长。
 */
internal class PcmWavRecorder(
    private val requestedSampleRate: Int,
    private val channels: Int,
    maxDurationMs: Long,
    private val mainHandler: Handler,
    private val onWorkerError: (String) -> Unit,
    private val onLimit: () -> Unit,
) {
    private var record: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var thread: Thread? = null
    private var actualRate = requestedSampleRate
    private val paused = AtomicBoolean(false)

    @Volatile private var running = false
    private val lock = Any()
    private var dataSize = 0L
    private val maxFrames = maxDurationMs * requestedSampleRate / 1000

    val durationMs: Long
        get() = synchronized(lock) { if (actualRate <= 0) 0L else dataSize / (actualRate * channels * 2) * 1000 }
    val fileSize: Long
        get() = 44 + synchronized(lock) { dataSize }

    /** 打开麦克风并开始落盘；失败抛异常由调用方收口。 */
    fun start(outputFile: File) {
        val channelIn = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        var opened: AudioRecord? = null
        for (rate in listOf(requestedSampleRate, 48000, 44100).distinct()) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, channelIn, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                channelIn,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, rate / 5 * 2 * channels), // 至少 200ms
            )
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                continue
            }
            opened = candidate
            actualRate = rate
            break
        }
        val recorder = opened ?: throw IllegalStateException("audio record init failed")
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            throw IllegalStateException("audio record start failed")
        }
        record = recorder
        val out = RandomAccessFile(outputFile, "rws")
        out.write(buildWavHeader(actualRate, channels, 0))
        output = out
        running = true
        thread = Thread({ captureLoop(recorder, out) }, "DiminaWavRecorder").also { it.start() }
    }

    private fun captureLoop(recorder: AudioRecord, out: RandomAccessFile) {
        val chunk = ShortArray(actualRate * 200 / 1000 * channels) // ~200ms
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0L
        while (running) {
            val n = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (failure: RuntimeException) {
                postError("could not read microphone: ${failure.message}")
                break
            }
            if (n < 0) {
                postError("could not read microphone ($n)")
                break
            }
            if (n == 0) continue
            if (paused.get()) continue // 丢弃，不计时
            val reachedLimit = frames + n >= maxFrames
            val take = if (reachedLimit) (maxFrames - frames).toInt() else n
            bytes.clear()
            for (i in 0 until take) bytes.putShort(chunk[i])
            synchronized(lock) {
                out.write(bytes.array(), 0, take * 2)
                dataSize += take * 2L
            }
            frames += take
            if (reachedLimit) {
                running = false
                mainHandler.post { onLimit() }
                break
            }
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    /** 停止采集、回填 WAV 长度；返回 false 表示没有任何有效数据。 */
    fun stop(): Boolean {
        running = false
        paused.set(false)
        val t = thread
        thread = null
        runCatching { t?.join(1000) }
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        val out = output
        output = null
        val size = synchronized(lock) { dataSize }
        if (size <= 0) {
            runCatching { out?.close() }
            return false
        }
        runCatching {
            out?.seek(0)
            out?.write(buildWavHeader(actualRate, channels, size.toInt()))
            out?.close()
        }
        return true
    }

    private fun postError(detail: String) {
        running = false
        mainHandler.post { onWorkerError(detail) }
    }
}

/**
 * Media - RecorderManager 原生实现（契约 v1）。
 *
 * 单例 per appId（sessions 键 = appId）；aac = MediaRecorder(AAC_ADTS/AAC)，
 * wav = AudioRecord 直写 PCM+WAV 头；RECORD_AUDIO 运行时权限拒绝 →
 * invokeFail `Recorder.start:fail auth deny`；焦点丢失 → interruption 事件。
 */
class RecorderApi : BaseApiHandler() {
    private class Listener(
        val callbackId: String,
        val responseCallback: (String) -> Unit,
    )

    /** 一次 start 的在途请求：权限授权回调落地时使用。 */
    private class PendingStart(
        val activity: DiminaActivity,
        val format: String,
        val sampleRate: Int,
        val channels: Int,
        val encodeBitRate: Int,
        val durationMs: Long,
        val params: JSONObject,
        val responseCallback: (String) -> Unit,
    )

    /** 单个 appId 的录音会话；所有可变状态只在主线程收敛。 */
    private inner class Session(val appId: String) {
        val listeners = ConcurrentHashMap<String, MutableMap<String, Listener>>()
        var state = RecorderState.IDLE
        var pendingStart: PendingStart? = null
        var mediaRecorder: MediaRecorder? = null
        var wavRecorder: PcmWavRecorder? = null
        var outputFile: File? = null
        var format = "aac"
        val elapsed = ElapsedTimer { SystemClock.elapsedRealtime() }
        var audioManager: AudioManager? = null
        var audioFocusRequest: AudioFocusRequest? = null
        /** 因系统打断（焦点丢失）进入的暂停，焦点恢复时自动续录并发 interruptionEnd。 */
        var pausedByInterruption = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override val apiNames = setOf(
        "Recorder.start", "Recorder.pause", "Recorder.resume", "Recorder.stop",
        "Recorder.onStart", "Recorder.onPause", "Recorder.onStop", "Recorder.onError",
        "Recorder.onInterruptionBegin", "Recorder.onInterruptionEnd",
        "Recorder.offStart", "Recorder.offPause", "Recorder.offStop", "Recorder.offError",
        "Recorder.offInterruptionBegin", "Recorder.offInterruptionEnd",
    )

    fun clearApp(appId: String) {
        sessions.remove(appId)?.let { session ->
            mainHandler.post { session.teardown() }
        }
    }

    fun clearAll() {
        sessions.keys.toList().forEach(::clearApp)
    }

    override fun handleAction(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val op = apiName.substringAfter('.')
        return when {
            op == "start" -> handleStart(activity, appId, params, responseCallback)
            op == "pause" || op == "resume" -> handlePauseResume(appId, apiName, op)
            op == "stop" -> handleStop(appId, params, responseCallback)
            op.startsWith("on") || op.startsWith("off") ->
                handleListen(appId, apiName, op, params, responseCallback)
            else -> super.handleAction(activity, appId, apiName, params, responseCallback)
        }
    }

    // ---- start：校验 → 权限 → 起引擎 ----

    private fun handleStart(
        activity: DiminaActivity,
        appId: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val format = params.optString("format", "aac").ifBlank { "aac" }
        val sampleRate = params.optInt("sampleRate", 8000)
        val channels = params.optInt("numberOfChannels", 1)
        val encodeBitRate = params.optInt("encodeBitRate", 48000)
        val durationMs = RecorderRules.clampDurationMs(params.optLong("duration", RecorderRules.DEFAULT_DURATION_MS))
        RecorderRules.validateStart(format, sampleRate, channels, encodeBitRate)
            ?.let { detail -> return asyncFail("Recorder.start", detail) }

        val session = sessions.getOrPut(appId) { Session(appId) }
        if (session.pendingStart != null || session.state != RecorderState.IDLE) {
            return asyncFail("Recorder.start", "busy")
        }
        session.pendingStart = PendingStart(
            activity, format, sampleRate, channels, encodeBitRate, durationMs, params, responseCallback,
        )
        // 权限流照 ScanApi/ContactApi 范式：launcher 需要主线程，统一 post 回主线程收口。
        runCatching {
            activity.handleAuthorization(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                mainHandler.post { onPermissionResult(appId, granted) }
            }
        }.onFailure {
            session.pendingStart = null
            return asyncFail("Recorder.start", "activity unavailable")
        }
        return NoneResult()
    }

    private fun onPermissionResult(appId: String, granted: Boolean) {
        val session = sessions[appId] ?: return
        val pending = session.pendingStart ?: return
        session.pendingStart = null
        if (!granted) {
            // 契约钉死文案。
            deliverFail(pending.params, pending.responseCallback, "Recorder.start:fail auth deny")
            return
        }
        startEngine(session, pending)
    }

    private fun startEngine(session: Session, pending: PendingStart) {
        val file = try {
            File.createTempFile(
                "REC_${System.currentTimeMillis()}",
                ".${pending.format}",
                PathUtils.appTempRoot(pending.activity, session.appId),
            )
        } catch (failure: Throwable) {
            deliverFail(pending.params, pending.responseCallback, "Recorder.start:fail temp file failed")
            return
        }
        session.outputFile = file
        session.format = pending.format
        session.audioManager = pending.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val started = runCatching {
            if (pending.format == "aac") startAac(session, file, pending) else startWav(session, file, pending)
        }
        if (started.isFailure) {
            session.teardownEngine()
            deliverFail(pending.params, pending.responseCallback,
                "Recorder.start:fail ${started.exceptionOrNull()?.message ?: "start failed"}")
            emit(session, "error", JSONObject()
                .put("errCode", RecorderRules.ERR_SYSTEM)
                .put("errMsg", "Recorder.start:fail ${started.exceptionOrNull()?.message ?: "start failed"}"))
            return
        }
        session.state = RecorderState.RECORDING
        session.elapsed.start()
        requestFocus(session)
        deliverSuccess(pending.params, pending.responseCallback, JSONObject().put("errMsg", "Recorder.start:ok"))
        emit(session, "start", JSONObject())
    }

    @Suppress("DEPRECATION") // minSdk 26，无 Context 版 MediaRecorder 构造可用
    private fun startAac(session: Session, file: File, pending: PendingStart) {
        val recorder = MediaRecorder()
        session.mediaRecorder = recorder
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioSamplingRate(pending.sampleRate)
        recorder.setAudioChannels(pending.channels)
        recorder.setAudioEncodingBitRate(pending.encodeBitRate)
        recorder.setMaxDuration(pending.durationMs.toInt())
        recorder.setOutputFile(file.absolutePath)
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                mainHandler.post { autoStop(session) }
            }
        }
        recorder.setOnErrorListener { _, what, extra ->
            mainHandler.post { onEngineError(session, "recorder error (what=$what, extra=$extra)") }
        }
        recorder.prepare()
        recorder.start()
    }

    private fun startWav(session: Session, file: File, pending: PendingStart) {
        val recorder = PcmWavRecorder(
            requestedSampleRate = pending.sampleRate,
            channels = pending.channels,
            maxDurationMs = pending.durationMs,
            mainHandler = mainHandler,
            onWorkerError = { detail -> onEngineError(session, detail) },
            onLimit = { autoStop(session) },
        )
        session.wavRecorder = recorder
        recorder.start(file)
    }

    // ---- pause / resume / stop ----

    private fun handlePauseResume(appId: String, apiName: String, op: String): APIResult {
        val session = sessions[appId] ?: return asyncFail(apiName, "no recording")
        RecorderRules.validateOp(session.state, op)?.let { detail -> return asyncFail(apiName, detail) }
        mainHandler.post {
            when (op) {
                "pause" -> if (session.state != RecorderState.RECORDING) return@post
                else -> if (session.state != RecorderState.PAUSED) return@post
            }
            if (op == "pause") {
                runCatching { session.mediaRecorder?.pause() }
                session.wavRecorder?.pause()
                session.elapsed.pause()
                session.state = RecorderState.PAUSED
                emit(session, "pause", JSONObject())
            } else {
                runCatching { session.mediaRecorder?.resume() }
                session.wavRecorder?.resume()
                session.elapsed.resume()
                session.state = RecorderState.RECORDING
                // 微信没有 onResume 事件，仅恢复计时。
            }
        }
        return asyncOk(apiName)
    }

    private fun handleStop(appId: String, params: JSONObject, responseCallback: (String) -> Unit): APIResult {
        val session = sessions[appId] ?: return asyncFail("Recorder.stop", "no recording")
        RecorderRules.validateOp(session.state, "stop")?.let { detail ->
            return asyncFail("Recorder.stop", detail)
        }
        mainHandler.post {
            if (session.state == RecorderState.IDLE) {
                deliverFail(params, responseCallback, "Recorder.stop:fail no recording")
                return@post
            }
            val recorded = finishRecording(session)
            if (recorded == null) {
                deliverFail(params, responseCallback, "Recorder.stop:fail no recorded data")
                emit(session, "error", JSONObject()
                    .put("errCode", RecorderRules.ERR_SYSTEM)
                    .put("errMsg", "Recorder.stop:fail no recorded data"))
            } else {
                deliverSuccess(params, responseCallback, JSONObject()
                    .put("tempFilePath", PathUtils.pathToVirtual(recorded.file))
                    .put("duration", recorded.durationMs)
                    .put("fileSize", recorded.fileSize)
                    .put("errMsg", "Recorder.stop:ok"))
                // onStop 事件 payload 不带 errMsg（契约线协议表）。
                emit(session, "stop", JSONObject()
                    .put("tempFilePath", PathUtils.pathToVirtual(recorded.file))
                    .put("duration", recorded.durationMs)
                    .put("fileSize", recorded.fileSize))
            }
        }
        return NoneResult()
    }

    /** duration 到限自动停：只发 onStop 事件（start 的回调早已结清）。 */
    private fun autoStop(session: Session) {
        if (session.state == RecorderState.IDLE) return
        val recorded = finishRecording(session) ?: return
        emit(session, "stop", JSONObject()
            .put("tempFilePath", PathUtils.pathToVirtual(recorded.file))
            .put("duration", recorded.durationMs)
            .put("fileSize", recorded.fileSize))
    }

    /** 收口引擎并产出结果；无数据返回 null（会话已复位）。 */
    private class Recorded(val file: File, val durationMs: Long, val fileSize: Long)

    private fun finishRecording(session: Session): Recorded? {
        val file = session.outputFile
        if (file == null) {
            session.teardownEngine()
            return null
        }
        if (session.format == "aac") {
            val stopped = runCatching { session.mediaRecorder?.stop() }
            val durationMs = session.elapsed.stop()
            if (stopped.isFailure) {
                session.teardownEngine()
                return null
            }
            return Recorded(file, durationMs, file.length())
        }
        val wav = session.wavRecorder
        val hasData = wav?.stop() == true
        val durationMs = wav?.durationMs ?: session.elapsed.stop()
        if (!hasData) {
            session.teardownEngine()
            return null
        }
        return Recorded(file, durationMs, file.length())
    }

    private fun onEngineError(session: Session, detail: String) {
        if (session.state == RecorderState.IDLE) return
        session.teardownEngine()
        emit(session, "error", JSONObject()
            .put("errCode", RecorderRules.ERR_SYSTEM)
            .put("errMsg", "Recorder.record:fail $detail"))
    }

    // ---- 事件订阅 ----

    private fun handleListen(
        appId: String,
        apiName: String,
        op: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val event = RecorderRules.eventForWire(op)
            ?: return asyncFail(apiName, "unsupported event $op")
        val session = sessions.getOrPut(appId) { Session(appId) }
        if (op.startsWith("on")) {
            val callbackId = params.optString("success")
            if (callbackId.isEmpty()) return asyncFail(apiName, "invalid callback")
            session.listeners.getOrPut(event) { ConcurrentHashMap() }[callbackId] =
                Listener(callbackId, responseCallback)
        } else {
            val callbackId = params.optString("success")
            session.listeners[event]?.let { listeners ->
                if (callbackId.isEmpty()) listeners.clear() else listeners.remove(callbackId)
            }
        }
        return asyncOk(apiName)
    }

    private fun emit(session: Session, event: String, payload: JSONObject) {
        mainHandler.post {
            session.listeners[event]?.values?.toList()?.forEach { listener ->
                listener.responseCallback(ApiUtils.createCallbackResponse(listener.callbackId, payload))
            }
        }
    }

    // ---- 音频焦点 ----

    private fun requestFocus(session: Session) {
        val manager = session.audioManager ?: return
        if (session.audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                mainHandler.post { onFocusChange(session, change) }
            }
            .build()
        session.audioFocusRequest = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun abandonFocus(session: Session) {
        val request = session.audioFocusRequest ?: return
        session.audioFocusRequest = null
        val manager = session.audioManager ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    /** 焦点丢失 → 暂停并 interruptionBegin；恢复 → 续录并 interruptionEnd（契约）。 */
    private fun onFocusChange(session: Session, change: Int) {
        if (session.state == RecorderState.IDLE) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (session.state != RecorderState.RECORDING) return
                runCatching { session.mediaRecorder?.pause() }
                session.wavRecorder?.pause()
                session.elapsed.pause()
                session.state = RecorderState.PAUSED
                session.pausedByInterruption = true
                emit(session, "interruptionBegin", JSONObject())
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!session.pausedByInterruption) return
                session.pausedByInterruption = false
                runCatching { session.mediaRecorder?.resume() }
                session.wavRecorder?.resume()
                session.elapsed.resume()
                session.state = RecorderState.RECORDING
                emit(session, "interruptionEnd", JSONObject())
            }
        }
    }

    // ---- 清理 ----

    private fun Session.teardownEngine() {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { wavRecorder?.stop() }
        wavRecorder = null
        outputFile = null
        state = RecorderState.IDLE
        pausedByInterruption = false
        abandonFocus(this)
    }

    private fun Session.teardown() {
        pendingStart = null
        teardownEngine()
        listeners.clear()
    }

    // ---- 回调投递 ----

    private fun deliverSuccess(
        params: JSONObject,
        responseCallback: (String) -> Unit,
        payload: JSONObject,
    ) {
        ApiUtils.invokeSuccess(params, payload, responseCallback)
        ApiUtils.invokeComplete(params, responseCallback, payload)
    }

    private fun deliverFail(
        params: JSONObject,
        responseCallback: (String) -> Unit,
        errMsg: String,
    ) {
        val payload = JSONObject().put("errMsg", errMsg)
        ApiUtils.invokeFail(params, payload, responseCallback)
        ApiUtils.invokeComplete(params, responseCallback, payload)
    }

    private fun asyncOk(apiName: String): APIResult =
        AsyncResult(JSONObject().put("errMsg", "$apiName:ok"))

    private fun asyncFail(apiName: String, detail: String): APIResult =
        AsyncResult(JSONObject().put("errMsg", "$apiName:fail $detail"))
}
