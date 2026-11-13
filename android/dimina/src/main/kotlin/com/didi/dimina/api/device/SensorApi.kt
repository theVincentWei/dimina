package com.didi.dimina.api.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.didi.dimina.api.APIResult
import com.didi.dimina.api.AsyncResult
import com.didi.dimina.api.BaseApiHandler
import com.didi.dimina.api.NoneResult
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.LogUtils
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Device - Sensor API（加速度计/罗盘/陀螺仪/设备方向，四通道 16 个 wire API）。
 *
 * wire 契约（与 service 层、web 容器三方一致）：
 *   start/stop    → params.success/fail/complete 是一次性回执 id，处理完点 success
 *   onXxxChange   → params.success 是常驻 keep 回调 id，数据到达时按该 id 回点火
 *                   payload（同 ApiRegistry.handleExtOnBridge 的持续订阅回点火先例），
 *                   首个 keep id 登记激活采样源（微信语义：on 隐式 start）
 *   offXxxChange  → params.success 有值摘单个 id、undefined 摘全部；全部摘除后停源
 *   start({interval}) → 'game'=20ms / 'ui'=60ms / 'normal'=200ms 节流
 *
 * 单位与微信文档一致：加速度 m/s²（SensorEvent.values 直报）、陀螺仪 rad/s（直报）、
 * 罗盘 direction 度（rotation vector 算方位角，0=正北）、deviceMotion alpha/beta/gamma 度。
 *
 * owner 隔离：ApiRegistry 跨小程序栈共享，采样源与订阅按 (appId, channel) 隔离；
 * 小程序销毁/退栈时 MiniApp.clear(appId) → [clearApp] 停源清订阅。
 *
 * Author: Doslin
 */
class SensorApi : BaseApiHandler() {

    private companion object {
        const val TAG = "SensorApi"

        // interval wire 值 → 节流周期 ms（契约钉死：game=20 ui=60 normal=200，未知值落 normal）
        const val INTERVAL_GAME_MS = 20L
        const val INTERVAL_UI_MS = 60L
        const val INTERVAL_NORMAL_MS = 200L
        const val DEFAULT_INTERVAL_MS = INTERVAL_NORMAL_MS

        fun intervalMsFor(wire: String?): Long = when (wire) {
            "game" -> INTERVAL_GAME_MS
            "ui" -> INTERVAL_UI_MS
            "normal" -> INTERVAL_NORMAL_MS
            else -> DEFAULT_INTERVAL_MS
        }

        fun sourceKey(appId: String, channel: Channel): String = "$appId/${channel.name}"
    }

    /** 四个采样通道。罗盘与设备方向共用 TYPE_ROTATION_VECTOR，各自独立监听。 */
    private enum class Channel(
        val startApi: String,
        val stopApi: String,
        val onApi: String,
        val offApi: String,
    ) {
        ACCELEROMETER(
            "startAccelerometer", "stopAccelerometer", "onAccelerometerChange", "offAccelerometerChange",
        ),
        COMPASS(
            "startCompass", "stopCompass", "onCompassChange", "offCompassChange",
        ),
        GYROSCOPE(
            "startGyroscope", "stopGyroscope", "onGyroscopeChange", "offGyroscopeChange",
        ),
        DEVICE_MOTION(
            "startDeviceMotionListening", "stopDeviceMotionListening", "onDeviceMotionChange", "offDeviceMotionChange",
        ),
    }

    override val apiNames: Set<String> = Channel.entries
        .flatMap { listOf(it.startApi, it.stopApi, it.onApi, it.offApi) }
        .toSet()

    // handleAction 跑在 JsCore 线程，传感器状态/注册统一挪主线程（SensorManager 要求 Looper 线程）
    private val mainHandler = Handler(Looper.getMainLooper())

    // key = appId/channel，见 [sourceKey]
    private val sources = ConcurrentHashMap<String, ChannelSource>()

    override fun handleAction(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val channel = Channel.entries.firstOrNull {
            apiName == it.startApi || apiName == it.stopApi || apiName == it.onApi || apiName == it.offApi
        } ?: return super.handleAction(activity, appId, apiName, params, responseCallback)

        return when (apiName) {
            channel.startApi -> {
                val intervalMs = intervalMsFor(params.optString("interval"))
                post(appId, channel, activity.applicationContext) {
                    it.intervalMs = intervalMs
                    it.listening = true
                    it.sync()
                }
                AsyncResult(okResult(apiName))
            }
            channel.stopApi -> {
                // stop 只停采样源；keep 订阅保留（静默），下次 start / onXxxChange 重启
                post(appId, channel, activity.applicationContext) {
                    it.listening = false
                    it.sync()
                }
                AsyncResult(okResult(apiName))
            }
            channel.onApi -> {
                val keepId = params.optString("success", "")
                if (keepId.isEmpty()) {
                    LogUtils.e(TAG, "$apiName:fail missing persistent success callback id")
                    return AsyncResult(JSONObject().apply {
                        put("errMsg", "$apiName:fail missing persistent success callback id")
                    })
                }
                post(appId, channel, activity.applicationContext) {
                    // keep id 幂等登记：同一函数重复 on 拿同一 id（service 层契约）
                    it.addKeepId(keepId, responseCallback)
                    it.listening = true
                    it.sync()
                }
                // 常驻订阅无一次性回执，参照 extOnBridge 返回 NoneResult 跳过默认回调
                NoneResult()
            }
            channel.offApi -> {
                val keepId = params.optString("success", "")
                post(appId, channel, activity.applicationContext) {
                    if (keepId.isEmpty()) {
                        // undefined：摘全部
                        it.clearKeepIds()
                    } else {
                        it.removeKeepId(keepId)
                    }
                    // 全部摘除后停源（有残留订阅则继续）
                    if (it.keepIdsEmpty()) {
                        it.listening = false
                        it.sync()
                    }
                }
                AsyncResult(okResult(apiName))
            }
            else -> super.handleAction(activity, appId, apiName, params, responseCallback)
        }
    }

    /** 小程序销毁/退栈：停它的全部采样源并清订阅（MiniApp.clear(appId) 调用，主线程执行）。 */
    fun clearApp(appId: String) {
        mainHandler.post {
            sources.entries
                .filter { it.value.appId == appId }
                .forEach { entry ->
                    entry.value.teardown()
                    sources.remove(entry.key, entry.value)
                }
        }
    }

    /** 停全部采样源并清订阅（MiniApp.destroy 调用）。 */
    fun clearAll() {
        mainHandler.post {
            sources.entries.forEach { entry ->
                entry.value.teardown()
                sources.remove(entry.key, entry.value)
            }
        }
    }

    private fun okResult(apiName: String): JSONObject = JSONObject().apply {
        put("errMsg", "$apiName:ok")
    }

    /**
     * 在主线程上对 (appId, channel) 的采样源执行 [action]。
     * handleAction 来自 JsCore 线程，状态变更统一入队保证单线程；
     * MiniApp.clear 的清理同样走该队列，FIFO 保证清理晚于本次变更生效。
     */
    private fun post(
        appId: String,
        channel: Channel,
        appContext: Context,
        action: (ChannelSource) -> Unit,
    ) {
        val source = sources.getOrPut(sourceKey(appId, channel)) { ChannelSource(appId, channel) }
        mainHandler.post {
            source.attachContext(appContext)
            action(source)
        }
    }

    /**
     * 单个 (appId, channel) 的采样源：持有该 owner 的 keep 订阅与 SensorManager 监听。
     * 全部状态只在主线程读写（[post] 的队列与 SensorManager 事件同为主线程）。
     */
    private inner class ChannelSource(
        val appId: String,
        val channel: Channel,
    ) : SensorEventListener {

        // 常驻 keep 回调 id（onXxxChange 的 params.success），Set 语义保证重复登记幂等
        private val keepIds = LinkedHashSet<String>()
        private var responseCallback: ((String) -> Unit)? = null

        private var appContext: Context? = null
        private var sensorManager: SensorManager? = null

        var intervalMs = DEFAULT_INTERVAL_MS
        var listening = false

        // 回点火节流基准（elapsedRealtime）
        private var lastFireAt = 0L

        // 罗盘 accuracy：onAccuracyChanged 尽力而为（SENSOR_STATUS_UNRELIABLE..HIGH）
        private var accuracy = 0

        private var registered = false
        private var registeredDelayUs = 0

        fun attachContext(context: Context) {
            if (appContext == null) {
                // 用 applicationContext，避免绑死某一代 Activity
                appContext = context.applicationContext
                sensorManager =
                    context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            }
        }

        fun addKeepId(id: String, callback: (String) -> Unit) {
            keepIds.add(id)
            responseCallback = callback
        }

        fun removeKeepId(id: String) {
            keepIds.remove(id)
        }

        fun clearKeepIds() {
            keepIds.clear()
        }

        fun keepIdsEmpty(): Boolean = keepIds.isEmpty()

        /** 按 listening 状态同步 SensorManager 注册；interval 变化时重注册。 */
        fun sync() {
            val manager = sensorManager
            if (manager == null) {
                LogUtils.e(TAG, "SensorManager unavailable for $appId/${channel.name}")
                return
            }
            val wantDelayUs = (intervalMs * 1000).toInt()
            if (listening) {
                val sensor = sensorOf(manager)
                if (sensor == null) {
                    LogUtils.e(TAG, "sensor missing for $appId/${channel.name}, cannot listen")
                    unregister(manager)
                    return
                }
                if (!registered || registeredDelayUs != wantDelayUs) {
                    unregister(manager)
                    registered = manager.registerListener(this, sensor, wantDelayUs, mainHandler)
                    registeredDelayUs = wantDelayUs
                    if (!registered) {
                        LogUtils.e(TAG, "registerListener failed for $appId/${channel.name}")
                    }
                }
            } else {
                unregister(manager)
            }
        }

        fun teardown() {
            listening = false
            keepIds.clear()
            responseCallback = null
            sensorManager?.unregisterListener(this)
            registered = false
            registeredDelayUs = 0
        }

        private fun sensorOf(manager: SensorManager): Sensor? = when (channel) {
            Channel.ACCELEROMETER -> manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Channel.GYROSCOPE -> manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            // 罗盘与设备方向共用 rotation vector：方位角/欧拉角都从它算
            Channel.COMPASS, Channel.DEVICE_MOTION -> manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }

        private fun unregister(manager: SensorManager) {
            if (registered) {
                manager.unregisterListener(this)
                registered = false
                registeredDelayUs = 0
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!listening) return
            // SensorManager 的 delay 只是采样提示，回点火前再按 interval 节流一次兜底
            val now = SystemClock.elapsedRealtime()
            if (now - lastFireAt < intervalMs) return
            lastFireAt = now

            val values = event.values
            if (values.size < 3) return

            val payload = when (channel) {
                Channel.ACCELEROMETER -> JSONObject().apply {
                    // TYPE_ACCELEROMETER 的 values 即 m/s²，直报
                    put("x", values[0].toDouble())
                    put("y", values[1].toDouble())
                    put("z", values[2].toDouble())
                }
                Channel.GYROSCOPE -> JSONObject().apply {
                    // TYPE_GYROSCOPE 的 values 即 rad/s，直报
                    put("x", values[0].toDouble())
                    put("y", values[1].toDouble())
                    put("z", values[2].toDouble())
                }
                Channel.COMPASS -> JSONObject().apply {
                    val euler = eulerDegreesFromRotationVector(values)
                    // 方位角 0-360，0=正北
                    put("direction", euler[0])
                    put("accuracy", accuracy)
                }
                Channel.DEVICE_MOTION -> JSONObject().apply {
                    val euler = eulerDegreesFromRotationVector(values)
                    put("alpha", euler[0])
                    put("beta", euler[1])
                    put("gamma", euler[2])
                }
            }

            fireToKeepIds(payload)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            this.accuracy = accuracy
        }

        /** rotation vector → (alpha, beta, gamma) 度：alpha 0-360（0=正北），beta/gamma 带符号。 */
        private fun eulerDegreesFromRotationVector(values: FloatArray): DoubleArray {
            val quaternion = quaternionFrom(values)
            val rotation = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotation, quaternion)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble())
            val pitchDeg = Math.toDegrees(orientation[1].toDouble())
            val rollDeg = Math.toDegrees(orientation[2].toDouble())
            val alpha = (azimuthDeg + 360.0) % 360.0
            return doubleArrayOf(alpha, pitchDeg, rollDeg)
        }

        /** 四元数分量兜底：个别机型只报 3 轴，按单位四元数约束补第四轴。 */
        private fun quaternionFrom(values: FloatArray): FloatArray {
            if (values.size >= 4) {
                return floatArrayOf(values[0], values[1], values[2], values[3])
            }
            val x = values[0]
            val y = values[1]
            val z = values[2]
            val w = sqrt(max(0f, 1f - x * x - y * y - z * z))
            return floatArrayOf(x, y, z, w)
        }

        private fun fireToKeepIds(payload: JSONObject) {
            val ids = synchronized(keepIds) { keepIds.toList() }
            if (ids.isEmpty()) return
            val callback = responseCallback ?: return
            for (id in ids) {
                try {
                    // 同 ApiRegistry.handleExtOnBridge：按 keep id 点 type=triggerCallback 消息
                    callback(ApiUtils.createCallbackResponse(id, payload))
                } catch (e: Exception) {
                    LogUtils.e(TAG, "fire $appId/${channel.name} to $id failed: ${e.message}")
                }
            }
        }
    }
}
