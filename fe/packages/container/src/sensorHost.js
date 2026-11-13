/**
 * Web 容器的传感器宿主实现(DeviceMotion/DeviceOrientation + 调试面板注入)。
 *
 * wire 协议是 Lead 拍板的三方契约(与 service 层、Android 任务书一致):
 * - onAccelerometerChange/onCompassChange/onGyroscopeChange/onDeviceMotionChange 的
 *   params.success 是常驻回调 id(service 侧 keep 登记):宿主保存 id,数据到达时用
 *   createCallbackFunction(id)(payload) 回点火;同一 keep id 重复 on,宿主登记幂等(Set);
 * - offXxxChange 的 params.success 有值摘单个、undefined 摘全部;「全部摘除且未 start」
 *   才停源——off 不停已显式 start 的源(微信语义,仍可 stop/start);
 * - startAccelerometer/startCompass/startGyroscope/startDeviceMotionListening 带
 *   interval('game'=20ms/'ui'=60ms/'normal'=200ms),宿主负责按 interval 节流;缺省/空值
 *   按 normal 档,无法识别的 label 点 fail(微信对非法 interval 不静默吞掉);
 * - onXxxChange 隐式激活采样(微信语义:无需先 start),按 normal 档节流;
 *   start/stop 是一次性回执:成功点 success({errMsg:'xxx:ok'}),service 侧把 payload
 *   原样透给业务回调(api/common 的 storeSettlerPair),所以回执带微信形状的 errMsg。
 *
 * 数据源:
 * - DeviceMotionEvent:accelerationIncludingGravity 直报 m/s² 喂加速度计;
 *   rotationRate 浏览器直报 deg/s,×π/180 换算成 rad/s 喂陀螺仪(微信单位);
 * - DeviceOrientationEvent:webkitCompassHeading 优先,fallback 用 (360-alpha)%360
 *   换算顺时针方位(0-360,正北 0)喂罗盘;alpha/beta/gamma(角度)直报设备方向。
 * 桌面浏览器两类事件都不存在时静默降级:API 面照常回执、不报错,数据等调试面板
 * (?sensorPanel=1)的 mock 源喂入。面板开启时 DOM 源让位(注入优先),且面板注入走
 * 与真实源完全同一条 broadcast→节流→回点火链路(mock 源就是源的一个实现)。
 *
 * 记账模型:audio 宿主按 instanceId 记账,传感器是 app 级能力,这里按 MiniApp 实例
 * (wire handler 的 this)记账。appRecords 与 innerAudioHost 的 instances 一样是模块级
 * Map,app 退出未显式 off 时记录会留存(数量级小,与先例一致)。
 */

const INTERVAL_MS = { game: 20, ui: 60, normal: 200 }
const DEFAULT_INTERVAL_LABEL = 'normal'
const DEG_TO_RAD = Math.PI / 180

/**
 * 四个通道的 wire 名与通道名。通道名是宿主内部/面板注入用的稳定 key,
 * wire 名对齐微信 API 面(service 层任务书的 16 个公开函数)。
 */
const CHANNEL_DEFS = {
	accelerometer: {
		on: 'onAccelerometerChange',
		off: 'offAccelerometerChange',
		start: 'startAccelerometer',
		stop: 'stopAccelerometer',
	},
	compass: {
		on: 'onCompassChange',
		off: 'offCompassChange',
		start: 'startCompass',
		stop: 'stopCompass',
	},
	gyroscope: {
		on: 'onGyroscopeChange',
		off: 'offGyroscopeChange',
		start: 'startGyroscope',
		stop: 'stopGyroscope',
	},
	deviceMotion: {
		on: 'onDeviceMotionChange',
		off: 'offDeviceMotionChange',
		start: 'startDeviceMotionListening',
		stop: 'stopDeviceMotionListening',
	},
}

const appRecords = new Map()
const dom = { attached: false, motionPermissionAsked: false }
let panelActive = false

function channelState() {
	return {
		ids: new Set(),
		started: false,
		intervalLabel: DEFAULT_INTERVAL_LABEL,
		active: false,
		latest: null,
		lastEmit: 0,
		timer: null,
	}
}

function recordFor(app) {
	let rec = appRecords.get(app)
	if (!rec) {
		rec = { channels: {} }
		for (const name of Object.keys(CHANNEL_DEFS)) {
			rec.channels[name] = channelState()
		}
		appRecords.set(app, rec)
	}
	return rec
}

function motionSupported() {
	return typeof window !== 'undefined' && typeof window.DeviceMotionEvent !== 'undefined'
}

function orientationSupported() {
	return typeof window !== 'undefined' && typeof window.DeviceOrientationEvent !== 'undefined'
}

function hasAnyActiveChannel() {
	for (const rec of appRecords.values()) {
		for (const ch of Object.values(rec.channels)) {
			if (ch.active) {
				return true
			}
		}
	}
	return false
}

function ensureDomListeners() {
	// 面板开启时 DOM 源整体让位,不挂监听(已挂的会被 setSensorPanelActive 摘掉)。
	if (dom.attached || panelActive) {
		return
	}
	if (!motionSupported() && !orientationSupported()) {
		return // 桌面浏览器:静默降级,不报错,等面板 mock 源
	}
	dom.attached = true
	window.addEventListener('devicemotion', handleDeviceMotion)
	window.addEventListener('deviceorientation', handleDeviceOrientation)
}

function detachDomListeners() {
	if (!dom.attached) {
		return
	}
	dom.attached = false
	window.removeEventListener('devicemotion', handleDeviceMotion)
	window.removeEventListener('deviceorientation', handleDeviceOrientation)
}

/**
 * iOS 12+ 的 DeviceMotionEvent 需要用户手势授权;不在手势上下文或被拒绝时静默,
 * 不阻塞激活流程(授权失败只是拿不到真实数据,API 面与面板注入不受影响)。
 */
function requestMotionPermissionOnce() {
	if (dom.motionPermissionAsked || !motionSupported()) {
		return
	}
	dom.motionPermissionAsked = true
	try {
		const maybe = window.DeviceMotionEvent.requestPermission?.()
		if (maybe && typeof maybe.then === 'function') {
			maybe.catch(() => {})
		}
	}
	catch {
		// 老浏览器无此 API,忽略
	}
}

function activateChannel(app, channel) {
	const ch = recordFor(app).channels[channel]
	if (ch.active) {
		return
	}
	ch.active = true
	ch.lastEmit = -Infinity // 重新激活按「首帧立即回点火」算,别继承上一次会话的节流相位
	ensureDomListeners()
	requestMotionPermissionOnce()
}

function deactivateChannel(app, channel) {
	const ch = recordFor(app).channels[channel]
	if (!ch.active) {
		return
	}
	ch.active = false
	ch.latest = null
	if (ch.timer !== null) {
		clearTimeout(ch.timer)
		ch.timer = null
	}
	if (!hasAnyActiveChannel()) {
		detachDomListeners()
	}
}

function emitChannel(app, ch, payload) {
	for (const id of [...ch.ids]) {
		app.createCallbackFunction(id)?.(payload)
	}
}

/**
 * 源事件(≥50Hz)按通道 interval 节流后再回点火:窗口前缘立即发一帧,窗口内的后续
 * 样本合并成 timer 尾帧(取最新值)。timer 触发时再校验 active,摘除后迟到的帧不点。
 */
function deliver(app, ch, payload) {
	ch.latest = payload
	if (!ch.active) {
		return
	}
	const interval = INTERVAL_MS[ch.intervalLabel] ?? INTERVAL_MS[DEFAULT_INTERVAL_LABEL]
	const now = Date.now()
	const elapsed = now - ch.lastEmit
	if (elapsed >= interval) {
		ch.lastEmit = now
		emitChannel(app, ch, payload)
		return
	}
	if (ch.timer === null) {
		ch.timer = setTimeout(() => {
			ch.timer = null
			if (!ch.active) {
				return
			}
			ch.lastEmit = Date.now()
			emitChannel(app, ch, ch.latest)
		}, interval - elapsed)
	}
}

function broadcast(channelName, payload) {
	if (!payload) {
		return
	}
	for (const [app, rec] of appRecords) {
		const ch = rec.channels[channelName]
		if (ch.active) {
			deliver(app, ch, payload)
		}
	}
}

/* ---------------- DOM 源 → payload 映射(导出供单测直接断言换算) ---------------- */

function finiteTriad(value) {
	if (!value) {
		return null
	}
	const x = Number(value.x)
	const y = Number(value.y)
	const z = Number(value.z)
	return Number.isFinite(x) && Number.isFinite(y) && Number.isFinite(z) ? { x, y, z } : null
}

/** accelerationIncludingGravity 直报 m/s²;字段缺失(部分浏览器)时跳过该帧。 */
export function accelerometerPayloadFromEvent(event) {
	return finiteTriad(event?.accelerationIncludingGravity)
}

/**
 * rotationRate 浏览器直报 deg/s 且字段名是 alpha/beta/gamma(DeviceMotionEvent 规范),
 * 微信陀螺仪单位是 rad/s、轴名 x/y/z:统一换算并映射。
 */
export function gyroscopePayloadFromEvent(event) {
	const rate = event?.rotationRate
	if (!rate) {
		return null
	}
	const alpha = Number(rate.alpha)
	const beta = Number(rate.beta)
	const gamma = Number(rate.gamma)
	if (![alpha, beta, gamma].every(Number.isFinite)) {
		return null
	}
	return { x: alpha * DEG_TO_RAD, y: beta * DEG_TO_RAD, z: gamma * DEG_TO_RAD }
}

/**
 * webkitCompassHeading(顺时针北偏角)优先;非 WebKit 用 alpha(逆时针北偏角)换算,
 * 统一归一化到 0-360。accuracy 用 webkitCompassAccuracy(角度误差,越小越准),
 * 拿不到给 0 表示未知。
 */
export function compassPayloadFromEvent(event) {
	if (!event) {
		return null
	}
	let heading = event.webkitCompassHeading
	if (heading === undefined || heading === null) {
		if (event.alpha === undefined || event.alpha === null) {
			return null
		}
		heading = 360 - Number(event.alpha)
	}
	if (!Number.isFinite(Number(heading))) {
		return null
	}
	heading = ((Number(heading) % 360) + 360) % 360
	const accuracy = Number(event.webkitCompassAccuracy)
	return {
		direction: heading,
		accuracy: Number.isFinite(accuracy) ? accuracy : 0,
	}
}

/** alpha/beta/gamma(角度)直报设备方向;任一字段缺失则跳过该帧。 */
export function deviceMotionPayloadFromEvent(event) {
	if (!event) {
		return null
	}
	const alpha = Number(event.alpha)
	const beta = Number(event.beta)
	const gamma = Number(event.gamma)
	if (![alpha, beta, gamma].every(Number.isFinite)) {
		return null
	}
	return { alpha, beta, gamma }
}

function handleDeviceMotion(event) {
	if (panelActive) {
		return // 面板开启,DOM 源让位
	}
	broadcast('accelerometer', accelerometerPayloadFromEvent(event))
	broadcast('gyroscope', gyroscopePayloadFromEvent(event))
}

function handleDeviceOrientation(event) {
	if (panelActive) {
		return
	}
	broadcast('compass', compassPayloadFromEvent(event))
	broadcast('deviceMotion', deviceMotionPayloadFromEvent(event))
}

/* ---------------- 调试面板接口(mock 源 = 源的一个实现) ---------------- */

/**
 * 面板开合通知:开启时摘掉 DOM 监听(注入优先),关闭时若有活跃通道则把 DOM 源接回来。
 */
export function setSensorPanelActive(active) {
	panelActive = !!active
	if (panelActive) {
		detachDomListeners()
	}
	else if (hasAnyActiveChannel()) {
		ensureDomListeners()
	}
}

/** 面板注入唯一入口:与 DOM 源走同一条 broadcast→节流→回点火链路。 */
export function injectSensorSample(channelName, payload) {
	if (!Object.prototype.hasOwnProperty.call(CHANNEL_DEFS, channelName)) {
		throw new Error(`[sensorHost] unknown channel: ${channelName}`)
	}
	broadcast(channelName, payload)
}

/* ---------------- wire 注册 ---------------- */

function ackOf(app, id, payload) {
	if (id !== undefined && id !== null) {
		app.createCallbackFunction(id)?.(payload)
	}
}

/** 'game'/'ui'/'normal' → 同名;缺省按 normal;识别不了返回 null(由 start 点 fail)。 */
export function normalizeIntervalLabel(interval) {
	if (interval === undefined || interval === null || interval === '') {
		return DEFAULT_INTERVAL_LABEL
	}
	return Object.prototype.hasOwnProperty.call(INTERVAL_MS, interval) ? interval : null
}

export function registerSensorApi(container) {
	for (const [channel, def] of Object.entries(CHANNEL_DEFS)) {
		container.registerApi(def.on, function (params = {}) {
			const { success } = params
			if (success === undefined || success === null) {
				return // service 侧 keep 登记必有 id;防御性忽略,不激活源
			}
			const ch = recordFor(this).channels[channel]
			ch.ids.add(success) // Set 幂等:同一 keep id 重复 on 无副作用
			activateChannel(this, channel)
		})

		container.registerApi(def.off, function (params = {}) {
			const { success } = params
			const ch = recordFor(this).channels[channel]
			if (success === undefined || success === null) {
				ch.ids.clear() // 无参摘全部
			}
			else {
				ch.ids.delete(success)
			}
			// 全部摘除且未显式 start 才停源;start 过的源归 start/stop 管(off 不停源)。
			if (ch.ids.size === 0 && !ch.started) {
				deactivateChannel(this, channel)
			}
		})

		container.registerApi(def.start, function (params = {}) {
			const { interval, success, fail } = params
			const label = normalizeIntervalLabel(interval)
			if (label === null) {
				ackOf(this, fail, { errMsg: `${def.start}:fail invalid interval "${interval}"` })
				return
			}
			const ch = recordFor(this).channels[channel]
			ch.started = true
			ch.intervalLabel = label
			activateChannel(this, channel)
			ackOf(this, success, { errMsg: `${def.start}:ok` })
		})

		container.registerApi(def.stop, function (params = {}) {
			const { success } = params
			const ch = recordFor(this).channels[channel]
			ch.started = false
			if (ch.ids.size === 0) {
				deactivateChannel(this, channel)
			}
			ackOf(this, success, { errMsg: `${def.stop}:ok` })
		})
	}
}

/* ---------------- 测试辅助 ---------------- */

export function resetSensorHostForTest() {
	appRecords.clear()
	detachDomListeners()
	dom.motionPermissionAsked = false
	panelActive = false
}

export function sensorChannelForTest(app, channel) {
	return appRecords.get(app)?.channels[channel]
}
