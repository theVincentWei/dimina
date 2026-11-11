package com.didi.dimina.api.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import com.didi.dimina.api.APIResult
import com.didi.dimina.api.AsyncResult
import com.didi.dimina.api.BaseApiHandler
import com.didi.dimina.api.SyncResult
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.PathUtils
import com.didi.dimina.engine.qjs.JSValue
import com.didi.dimina.ui.container.DiminaActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 微信 errCode 约定（契约 v1）：10001 系统 / 10002 网络 / 10003 文件 / 10004 格式不支持 / 10005 解码。
 */
internal object InnerAudioRules {
    const val ERR_SYSTEM = 10001
    const val ERR_NETWORK = 10002
    const val ERR_FILE = 10003
    const val ERR_FORMAT = 10004
    const val ERR_DECODE = 10005

    /** 事件名既可能带 on 前缀（微信监听器名）也可能不带；归一成 camelCase 规范名。 */
    val CANONICAL_EVENTS = setOf(
        "canplay", "play", "pause", "stop", "ended", "timeUpdate", "error",
        "waiting", "seeking", "seeked", "loadedmetadata",
    )

    /** 未知事件返回 null，由调用方决定 fail errMsg。on 前缀大小写不敏感容错。 */
    fun normalizeEvent(event: String): String? {
        val trimmed = event.trim()
        val stripped = if (trimmed.length > 2 && trimmed.startsWith("on", ignoreCase = true)) {
            trimmed.substring(2)
        } else {
            trimmed
        }
        if (stripped.isEmpty()) return null
        val canonical = stripped.replaceFirstChar { it.lowercase() }
        return if (canonical in CANONICAL_EVENTS) canonical else null
    }

    /** MediaPlayer OnErrorListener 的 (what, extra) → 微信 errCode。 */
    fun mapPlayerError(what: Int, extra: Int): Int = when {
        extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> ERR_FORMAT
        extra == MediaPlayer.MEDIA_ERROR_MALFORMED -> ERR_DECODE
        extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT -> ERR_NETWORK
        what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> ERR_SYSTEM
        else -> ERR_SYSTEM
    }

    /** setDataSource / 本地路径解析阶段的失败 → 微信 errCode（路径类错误优先归 10003 文件）。 */
    fun mapDataSourceFailure(error: Throwable): Int = when (error) {
        is java.io.IOException, is IllegalArgumentException, is IllegalStateException -> ERR_FILE
        else -> ERR_SYSTEM
    }

    /** volume 允许 0~1，越界收敛到边界；非法值回落默认 1。 */
    fun clampVolume(volume: Double): Double = when {
        volume.isNaN() || volume.isInfinite() -> 1.0
        else -> volume.coerceIn(0.0, 1.0)
    }

    /** playbackRate 范围 0.5~2.0（微信语义），越界收敛；非法值回落默认 1.0。 */
    fun clampPlaybackRate(rate: Double): Double = when {
        rate.isNaN() || rate.isInfinite() -> 1.0
        else -> rate.coerceIn(0.5, 2.0)
    }
}

/**
 * Media - InnerAudioContext 原生实现（契约 v1）。
 *
 * 实例表键 = `appId to instanceId`（镜像 VideoDecoderApi 的 sessions）；
 * `InnerAudio.getState` 走 SyncResult(JSValue.createNative) 同步返回；
 * 事件推送走 NetworkApi 的 Listener(callbackId, responseCallback) + createCallbackResponse 范式；
 * onTimeUpdate 由主线程 Handler 每 200ms 节流推送。
 */
class AudioApi : BaseApiHandler() {
    private companion object {
        const val TIME_UPDATE_INTERVAL_MS = 200L
    }

    private class Listener(
        val callbackId: String,
        val responseCallback: (String) -> Unit,
    )

    /** 单个 InnerAudioContext 实例：一个 MediaPlayer + 一张事件表 + 一个节流 ticker。 */
    private inner class InnerAudioInstance(
        val appId: String,
        val instanceId: String,
        val audioManager: AudioManager,
    ) {
        val listeners = ConcurrentHashMap<String, MutableMap<String, Listener>>()
        val mainHandler = Handler(Looper.getMainLooper())
        var player: MediaPlayer? = null
        var src = ""
        var resolvedSrc: String? = null
        var autoplay = false
        var loop = false
        var volume = 1.0
        var playbackRate = 1.0
        var startTime = 0.0
        /** Android 无硬件静音开关，obeyMuteSwitch 仅透传保存（契约注释说明）。 */
        var obeyMuteSwitch = true
        var pendingPlay = false
        var pendingSeekMs = -1
        /** onPrepared 里的 startTime/pendingSeek 自动落位不给 JS 发 seeked（微信语义）。 */
        var suppressNextSeeked = false
        var prepared = false
        var stopped = false
        /** 播放器进入 error 态后必须 reset+re-prepare 才能再播。 */
        var errored = false
        var destroyed = false
        /** 下一次 prepare 链的代际号；setSrc/destroy 使旧链作废。 */
        var prepareGeneration = 0L
        var lastOp = "setSrc"
        var audioFocusRequest: AudioFocusRequest? = null

        // getState 可能从 JS 线程同步读取，单独 volatile。
        @Volatile var paused = true
        @Volatile var cachedDuration = 0.0
        @Volatile var cachedCurrentTime = 0.0

        val ticker = object : Runnable {
            override fun run() {
                if (destroyed || paused || !prepared) return
                val current = player ?: return
                val position = runCatching { current.currentPosition }.getOrNull() ?: return
                cachedCurrentTime = position / 1000.0
                emit("timeUpdate", JSONObject()
                    .put("currentTime", cachedCurrentTime)
                    .put("duration", cachedDuration))
                mainHandler.postDelayed(this, TIME_UPDATE_INTERVAL_MS)
            }
        }

        fun snapshot(): JSONObject = JSONObject()
            .put("duration", cachedDuration)
            .put("currentTime", cachedCurrentTime)
            .put("paused", paused)

        /** 有效音量 = 实例自身音量 × 容器静音位(静音=0);不打断播放状态与事件。 */
        fun applyEffectiveVolume(target: MediaPlayer) {
            val effective = if (mutedApps[appId] == true) 0.0 else volume
            runCatching { target.setVolume(effective.toFloat(), effective.toFloat()) }
        }
    }

    private val sessions = ConcurrentHashMap<Pair<String, String>, InnerAudioInstance>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 创建过音频实例的 app(本容器会话):宿主 UI 据此决定是否显示胶囊左侧静音钮。 */
    private val audioApps = ConcurrentHashMap.newKeySet<String>()

    /** 容器级静音位(appId → muted):静音=有效音量置 0,不打断播放状态与事件。 */
    private val mutedApps = ConcurrentHashMap<String, Boolean>()

    /** 该小程序本会话是否用过音频(建过 InnerAudio 实例)。 */
    fun isAudioActive(appId: String): Boolean = audioApps.contains(appId)

    /** 该小程序当前是否被容器静音(胶囊左侧静音钮状态)。 */
    fun isMuted(appId: String): Boolean = mutedApps[appId] == true

    /**
     * 静音开关(胶囊左侧静音钮调用):置位后对该 app 全部活实例即时生效
     * (有效音量 0 ↔ 实例自身音量),之后新建的实例同样按位生效。
     */
    fun setMuted(appId: String, muted: Boolean) {
        mutedApps[appId] = muted
        mainHandler.post {
            sessions.keys.filter { it.first == appId }.forEach { key ->
                sessions[key]?.let { instance ->
                    instance.player?.let { target -> instance.applyEffectiveVolume(target) }
                }
            }
        }
    }

    override val apiNames = setOf(
        "InnerAudio.setSrc", "InnerAudio.play", "InnerAudio.pause", "InnerAudio.stop",
        "InnerAudio.seek", "InnerAudio.setProperty", "InnerAudio.getState",
        "InnerAudio.on", "InnerAudio.off", "InnerAudio.destroy",
    )

    fun clearApp(appId: String) {
        sessions.keys.filter { it.first == appId }.forEach { sessions.remove(it)?.destroy() }
        // app 会话结束:音频「用过」打点与静音位一并复位(下次打开重新计)。
        audioApps.remove(appId)
        mutedApps.remove(appId)
    }

    fun clearAll() {
        sessions.keys.toList().forEach { sessions.remove(it)?.destroy() }
        audioApps.clear()
        mutedApps.clear()
    }

    override fun handleAction(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val instanceId = params.optString("instanceId")
        if (instanceId.isEmpty()) {
            return asyncFail(apiName, "missing instanceId")
        }
        val key = appId to instanceId
        return when (apiName) {
            "InnerAudio.getState" -> {
                val state = sessions[key]?.snapshot()
                    ?: JSONObject().put("duration", 0.0).put("currentTime", 0.0).put("paused", true)
                SyncResult(JSValue.createNative(state))
            }
            "InnerAudio.setSrc" -> {
                val src = params.optString("src")
                if (src.isEmpty()) return asyncFail(apiName, "invalid src")
                val instance = sessions.getOrPut(key) {
                    InnerAudioInstance(appId, instanceId, audioManagerOf(activity))
                }
                // 「用过音频」打点(2026-11-12 胶囊静音钮):首次创建实例时点亮宿主静音钮,
                // 兜底覆盖未声明 requiredBackgroundModes 的老包。
                if (audioApps.add(appId)) {
                    mainHandler.post { activity.markMiniProgramAudioActive() }
                }
                mainHandler.post { instance.setSrc(activity, src) }
                asyncOk(apiName)
            }
            "InnerAudio.play" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                mainHandler.post { instance.play() }
                asyncOk(apiName)
            }
            "InnerAudio.pause" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                mainHandler.post { instance.pause() }
                asyncOk(apiName)
            }
            "InnerAudio.stop" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                mainHandler.post { instance.stop() }
                asyncOk(apiName)
            }
            "InnerAudio.seek" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                val position = params.optDouble("position", Double.NaN)
                if (position.isNaN() || position < 0) return asyncFail(apiName, "invalid position")
                mainHandler.post { instance.seek(position) }
                asyncOk(apiName)
            }
            "InnerAudio.setProperty" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                mainHandler.post { instance.setProperty(params) }
                asyncOk(apiName)
            }
            "InnerAudio.on" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                val event = InnerAudioRules.normalizeEvent(params.optString("event"))
                    ?: return asyncFail(apiName, "unsupported event ${params.optString("event")}")
                val callbackId = params.optString("success")
                if (callbackId.isEmpty()) return asyncFail(apiName, "invalid callback")
                instance.listeners.getOrPut(event) { ConcurrentHashMap() }[callbackId] =
                    Listener(callbackId, responseCallback)
                asyncOk(apiName)
            }
            "InnerAudio.off" -> {
                val instance = sessions[key] ?: return missingInstance(apiName)
                val event = InnerAudioRules.normalizeEvent(params.optString("event"))
                    ?: return asyncFail(apiName, "unsupported event ${params.optString("event")}")
                val callbackId = params.optString("success")
                instance.listeners[event]?.let { listeners ->
                    if (callbackId.isEmpty()) listeners.clear() else listeners.remove(callbackId)
                }
                asyncOk(apiName)
            }
            "InnerAudio.destroy" -> {
                sessions.remove(key)?.destroy()
                asyncOk(apiName)
            }
            else -> super.handleAction(activity, appId, apiName, params, responseCallback)
        }
    }

    private fun audioManagerOf(activity: DiminaActivity): AudioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 未注册实例的操作（setSrc 之前 play、destroy 之后调用等）：对齐 VideoDecoderApi 的 fail 语义。 */
    private fun missingInstance(apiName: String): APIResult = asyncFail(apiName, "instance not found")

    /** AsyncResult + `:ok` errMsg：框架会自动派发 success 与 complete。 */
    private fun asyncOk(apiName: String): APIResult =
        AsyncResult(JSONObject().put("errMsg", "$apiName:ok"))

    /** AsyncResult + 非 `:ok` errMsg：框架会自动派发 fail 与 complete。 */
    private fun asyncFail(apiName: String, detail: String): APIResult =
        AsyncResult(JSONObject().put("errMsg", "$apiName:fail $detail"))

    /** 事件投递一律收敛回主线程，与 MediaPlayer 回调顺序一致。 */
    private fun InnerAudioInstance.emit(event: String, payload: JSONObject) {
        mainHandler.post {
            if (destroyed) return@post
            listeners[event]?.values?.toList()?.forEach { listener ->
                listener.responseCallback(ApiUtils.createCallbackResponse(listener.callbackId, payload))
            }
        }
    }

    private fun InnerAudioInstance.emitError(detail: String, errCode: Int) {
        emit("error", JSONObject()
            .put("errCode", errCode)
            .put("errMsg", "InnerAudio.$lastOp:fail $detail"))
    }

    private fun InnerAudioInstance.requestFocus() {
        if (audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                mainHandler.post {
                    if (change != AudioManager.AUDIOFOCUS_GAIN) pauseByFocusLoss()
                }
            }
            .build()
        audioFocusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun InnerAudioInstance.abandonFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    /** 焦点丢失：暂停并推送 pause 事件（契约「焦点丢失 → 发 pause 事件」），不自动恢复。 */
    private fun InnerAudioInstance.pauseByFocusLoss() {
        if (destroyed || paused || !prepared) return
        player?.let { runCatching { it.pause() } }
        paused = true
        mainHandler.removeCallbacks(ticker)
        emit("pause", JSONObject())
    }

    private fun InnerAudioInstance.setSrc(activity: DiminaActivity, nextSrc: String) {
        if (destroyed) return
        src = nextSrc
        lastOp = "setSrc"
        pendingPlay = false
        pendingSeekMs = -1
        prepared = false
        stopped = false
        errored = false
        paused = true
        cachedDuration = 0.0
        cachedCurrentTime = 0.0
        mainHandler.removeCallbacks(ticker)
        prepareAsync(activity)
    }

    /**
     * 解析源并 prepare。本地 difile/包内路径走 PathUtils 解析；网络 http(s) 源直接交给
     * MediaPlayer 流式 prepareAsync（setDataSource 的网络 IO 放 IO 线程，回调仍回主线程）。
     */
    private fun InnerAudioInstance.prepareAsync(activity: DiminaActivity) {
        val generation = ++prepareGeneration
        player?.let { runCatching { it.reset() } }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val resolved = runCatching {
                if (src.startsWith("http://") || src.startsWith("https://")) src
                else PathUtils.pathToAppResource(activity, src, appId)
            }
            mainHandler.post {
                if (destroyed || generation != prepareGeneration) return@post
                val target = player ?: MediaPlayer().also { player = it }
                resolved.fold(
                    { source ->
                        resolvedSrc = source
                        attachAndPrepare(target, source)
                    },
                    { failure ->
                        paused = true
                        emitError(
                            failure.message ?: "resolve src failed",
                            InnerAudioRules.mapDataSourceFailure(failure),
                        )
                    },
                )
            }
        }
    }

    /** play-after-stop 的重 prepare：直接走最后一次解析结果（无网络 IO）。
     *  平台特性：stop()/reset() 后复用同一 MediaPlayer 再 prepareAsync 会静默挂死
     *  （onPrepared/onError 均不回调，API 35 实测）——必须销毁旧实例重建。 */
    private fun InnerAudioInstance.reprepare() {
        val generation = ++prepareGeneration
        val source = resolvedSrc
        mainHandler.post {
            if (destroyed || generation != prepareGeneration) return@post
            if (source.isNullOrEmpty()) return@post
            player?.let { runCatching { it.release() } }
            player = null
            val fresh = MediaPlayer().also { player = it }
            attachAndPrepare(fresh, source)
        }
    }

    private fun InnerAudioInstance.attachAndPrepare(target: MediaPlayer, source: String) {
        if (destroyed) return
        try {
            target.reset()
            target.setDataSource(source)
            target.setOnPreparedListener { preparedTarget -> onPrepared(preparedTarget) }
            target.setOnCompletionListener {
                paused = true
                cachedCurrentTime = cachedDuration
                mainHandler.removeCallbacks(ticker)
                emit("ended", JSONObject())
            }
            target.setOnErrorListener { _, what, extra -> onPlayerError(what, extra) }
            target.setOnSeekCompleteListener {
                if (destroyed) return@setOnSeekCompleteListener
                if (suppressNextSeeked) {
                    suppressNextSeeked = false
                } else {
                    emit("seeked", JSONObject())
                }
            }
            prepared = false
            target.prepareAsync()
        } catch (failure: Throwable) {
            paused = true
            emitError(failure.message ?: "invalid src", InnerAudioRules.mapDataSourceFailure(failure))
        }
    }

    private fun InnerAudioInstance.onPlayerError(what: Int, extra: Int): Boolean {
        if (destroyed) return true
        paused = true
        prepared = false
        errored = true
        mainHandler.removeCallbacks(ticker)
        emitError("player error (what=$what, extra=$extra)", InnerAudioRules.mapPlayerError(what, extra))
        return true
    }

    private fun InnerAudioInstance.onPrepared(target: MediaPlayer) {
        if (destroyed) return
        prepared = true
        cachedDuration = runCatching { target.duration / 1000.0 }.getOrDefault(0.0)
        cachedCurrentTime = 0.0
        applyPlaybackRate(target)
        // 静音位在 prepare 完成时落一次,保证 autoplay/pending 起播即按有效音量出声。
        applyEffectiveVolume(target)
        val seekToMs = if (pendingSeekMs >= 0) pendingSeekMs.toLong() else (startTime * 1000).toLong()
        if (seekToMs > 0) {
            suppressNextSeeked = true
            runCatching { target.seekTo(seekToMs, MediaPlayer.SEEK_PREVIOUS_SYNC) }
            pendingSeekMs = -1
        }
        emit("canplay", JSONObject())
        emit("loadedmetadata", JSONObject())
        if (autoplay || pendingPlay) {
            pendingPlay = false
            startInternal(target)
        }
    }

    private fun InnerAudioInstance.applyPlaybackRate(target: MediaPlayer) {
        if (abs(playbackRate - 1.0) < 1e-6) return
        runCatching {
            target.playbackParams = PlaybackParams().setSpeed(playbackRate.toFloat())
        }
    }

    private fun InnerAudioInstance.startInternal(target: MediaPlayer) {
        requestFocus()
        try {
            applyPlaybackRate(target)
            target.start()
            paused = false
            stopped = false
            emit("play", JSONObject())
            mainHandler.removeCallbacks(ticker)
            mainHandler.postDelayed(ticker, TIME_UPDATE_INTERVAL_MS)
        } catch (failure: Throwable) {
            emitError(failure.message ?: "start failed", InnerAudioRules.mapDataSourceFailure(failure))
        }
    }

    private fun InnerAudioInstance.play() {
        if (destroyed) return
        lastOp = "play"
        if (src.isEmpty()) {
            emitError("no valid audio source", InnerAudioRules.ERR_FILE)
            return
        }
        val target = player
        when {
            target == null ->
                // setSrc 的解析链尚未落 player：记 pending-play，onPrepared 时自动起播。
                waitUntilPrepared()
            prepared -> startInternal(target)
            stopped || errored -> {
                // stop()/错误态之后 MediaPlayer 必须重新 prepare 才能播。
                pendingPlay = true
                errored = false
                stopped = false
                reprepare()
            }
            else -> waitUntilPrepared()
        }
    }

    private fun InnerAudioInstance.waitUntilPrepared() {
        pendingPlay = true
        emit("waiting", JSONObject())
    }

    private fun InnerAudioInstance.pause() {
        if (destroyed) return
        lastOp = "pause"
        pendingPlay = false
        val target = player ?: return
        if (!prepared || paused) return
        runCatching { target.pause() }
        paused = true
        mainHandler.removeCallbacks(ticker)
        emit("pause", JSONObject())
        abandonFocus()
    }

    private fun InnerAudioInstance.stop() {
        if (destroyed) return
        lastOp = "stop"
        pendingPlay = false
        val target = player ?: return
        if (stopped) return
        if (prepared) {
            try {
                target.stop()
            } catch (failure: IllegalStateException) {
                // prepare 未完成时的 stop 是合法语义（放弃加载），忽略状态机异常。
            }
        }
        prepared = false
        stopped = true
        paused = true
        cachedCurrentTime = 0.0
        mainHandler.removeCallbacks(ticker)
        emit("stop", JSONObject())
        abandonFocus()
    }

    private fun InnerAudioInstance.seek(positionSeconds: Double) {
        if (destroyed) return
        lastOp = "seek"
        val seekToMs = (positionSeconds * 1000).toLong()
        val target = player
        if (target == null || !prepared) {
            // prepare 中/尚未 prepared：先记 pending，onPrepared 时落位。
            pendingSeekMs = seekToMs.toInt()
            return
        }
        emit("seeking", JSONObject())
        runCatching { target.seekTo(seekToMs, MediaPlayer.SEEK_PREVIOUS_SYNC) }
        cachedCurrentTime = positionSeconds
    }

    private fun InnerAudioInstance.setProperty(params: JSONObject) {
        if (destroyed) return
        lastOp = "setProperty"
        if (params.has("loop")) loop = params.optBoolean("loop", loop)
        if (params.has("volume")) volume = InnerAudioRules.clampVolume(params.optDouble("volume", volume))
        if (params.has("playbackRate")) {
            playbackRate = InnerAudioRules.clampPlaybackRate(params.optDouble("playbackRate", playbackRate))
        }
        if (params.has("startTime")) {
            val value = params.optDouble("startTime", startTime)
            startTime = if (value.isNaN() || value.isInfinite() || value < 0) 0.0 else value
        }
        if (params.has("autoplay")) autoplay = params.optBoolean("autoplay", autoplay)
        if (params.has("obeyMuteSwitch")) obeyMuteSwitch = params.optBoolean("obeyMuteSwitch", obeyMuteSwitch)
        player?.let { target ->
            runCatching { target.isLooping = loop }
            applyEffectiveVolume(target)
            if (prepared) applyPlaybackRate(target)
        }
    }

    private fun InnerAudioInstance.destroy() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacks(ticker)
        abandonFocus()
        player?.let { target ->
            runCatching {
                target.setOnPreparedListener(null)
                target.setOnCompletionListener(null)
                target.setOnErrorListener(null)
                target.setOnSeekCompleteListener(null)
                target.release()
            }
        }
        player = null
        listeners.clear()
    }
}
