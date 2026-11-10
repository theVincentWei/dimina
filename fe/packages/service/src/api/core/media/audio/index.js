import { callback, uuid } from '@dimina/common'
import { invokeAPI } from '@/api/common'

/**
 * 创建并返回 InnerAudioContext 实例
 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/wx.createInnerAudioContext.html
 *
 * @param {boolean} [useWebAudioImplement] 仅保留微信签名；本实现统一走原生播放器
 * @returns {InnerAudioContext} 音频播放上下文实例
 */
export function createInnerAudioContext(useWebAudioImplement = false) {
	return new InnerAudioContext(useWebAudioImplement)
}

/**
 * 获取全局唯一的录音管理器 RecorderManager
 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/wx.getRecorderManager.html
 *
 * @returns {RecorderManager} 全局唯一的录音管理器
 */
let recorderManager
export function getRecorderManager() {
	if (!recorderManager) {
		recorderManager = new RecorderManager()
	}
	return recorderManager
}

// InnerAudioContext 的 11 个事件，wire 上的 event 字段取微信事件名（去掉 on 前缀的驼峰）。
const INNER_AUDIO_EVENTS = {
	onCanplay: 'canplay',
	onPlay: 'play',
	onPause: 'pause',
	onStop: 'stop',
	onEnded: 'ended',
	onTimeUpdate: 'timeUpdate',
	onError: 'error',
	onWaiting: 'waiting',
	onSeeking: 'seeking',
	onSeeked: 'seeked',
	onLoadedmetadata: 'loadedmetadata',
}

// RecorderManager 支持的事件，wire 上每个事件是独立的 Recorder.onXxx / Recorder.offXxx。
const RECORDER_EVENTS = [
	'onStart',
	'onPause',
	'onStop',
	'onError',
	'onInterruptionBegin',
	'onInterruptionEnd',
]

const DEFAULT_RECORD_DURATION = 60000
const MAX_RECORD_DURATION = 600000

/**
 * 常驻事件 id 的引用计数。callback.store(cb, true) 对同一个回调函数全局只发一个 id，
 * 同一个函数可能同时挂在多个事件、甚至多个 InnerAudioContext 实例上，所以摘表要
 * 等所有引用都注销（计数归零）才能做，否则会误删别的订阅。
 */
const sharedIdRefs = new Map()

function acquireId(id) {
	sharedIdRefs.set(id, (sharedIdRefs.get(id) || 0) + 1)
}

function releaseId(id) {
	const refs = (sharedIdRefs.get(id) || 0) - 1
	if (refs > 0) {
		sharedIdRefs.set(id, refs)
	}
	else {
		sharedIdRefs.delete(id)
		callback.remove(id)
	}
}

/**
 * 事件登记的公共机制：每个事件维护「callbackId -> 回调」表。登记用
 * callback.store(cb, true)（keep）拿常驻 id——同一函数重复登记会拿到同一个
 * id，所以同事件重复登记天然去重；注销按上面的引用计数回收 id。
 */
function addEventListener(ctx, event, cb, register) {
	if (ctx.destroyed || typeof cb !== 'function') return
	let listeners = ctx.events.get(event)
	if (!listeners) ctx.events.set(event, listeners = new Map())
	const id = callback.store(cb, true)
	const isNew = !listeners.has(id)
	listeners.set(id, cb)
	// 引用计数与事件表条目一一对应：条目建一次计数加一，删一次减一。
	if (isNew) {
		acquireId(id)
		register(id)
	}
}

function removeEventListener(ctx, event, cb, unregister) {
	if (ctx.destroyed) return
	const listeners = ctx.events.get(event)
	if (!listeners) {
		// 本地没登记过也允许无参注销到达原生，清掉原生侧可能残留的事件表。
		if (cb === undefined) unregister(undefined)
		return
	}
	if (cb === undefined) {
		ctx.events.delete(event)
		for (const id of listeners.keys()) releaseId(id)
		unregister(undefined)
		return
	}
	for (const [id, listener] of listeners) {
		if (listener === cb) {
			listeners.delete(id)
			releaseId(id)
			unregister(id)
			return
		}
	}
}

function emitEvent(ctx, event, payload) {
	const listeners = ctx.events.get(event)
	if (!listeners) return
	for (const listener of [...listeners.values()]) listener(payload)
}

/**
 * 微信音频实例的通用事件 wiring：控制/属性调用带一次性的成对回执 id（本模块显式
 * callback.store 成对登记，任一回执先到即两条同清），事件订阅走常驻 id。
 */
class InnerAudioContext {
	/**
	 * InnerAudioContext
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.html
	 */
	constructor(useWebAudioImplement = false) {
		// useWebAudioImplement 只保留微信签名；原生统一走 MediaPlayer。
		this.useWebAudioImplement = useWebAudioImplement
		this.instanceId = `inner_audio_${uuid()}`
		this.events = new Map()
		this.destroyed = false
		this._src = ''
		this._startTime = 0
		this._autoplay = false
		this._loop = false
		// Android 没有硬件静音开关，obeyMuteSwitch 仅按微信默认值透传，由原生记录。
		this._obeyMuteSwitch = true
		this._volume = 1
		this._playbackRate = 1
		this._state = { duration: 0, currentTime: 0, paused: true }
	}

	get src() {
		return this._src
	}

	set src(value) {
		this._src = value
		if (this.destroyed) return
		this.control('InnerAudio.setSrc', { src: value }, () => {
			// 重新加载后回到未播放态；duration/currentTime 等 loadedmetadata 后由原生刷新。
			this._state = { duration: 0, currentTime: 0, paused: true }
		})
	}

	get startTime() {
		return this._startTime
	}

	set startTime(value) {
		this._startTime = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { startTime: value })
	}

	get autoplay() {
		return this._autoplay
	}

	set autoplay(value) {
		this._autoplay = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { autoplay: value })
	}

	get loop() {
		return this._loop
	}

	set loop(value) {
		this._loop = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { loop: value })
	}

	get obeyMuteSwitch() {
		return this._obeyMuteSwitch
	}

	set obeyMuteSwitch(value) {
		this._obeyMuteSwitch = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { obeyMuteSwitch: value })
	}

	get volume() {
		return this._volume
	}

	set volume(value) {
		this._volume = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { volume: value })
	}

	get playbackRate() {
		return this._playbackRate
	}

	set playbackRate(value) {
		this._playbackRate = value
		if (this.destroyed) return
		this.control('InnerAudio.setProperty', { playbackRate: value })
	}

	// 以下三个是原生播放器的实时值：读取时走 InnerAudio.getState 的同步回执并缓存，
	// 桥读不到结果（如 Web 调试通道异步）时回落到上次缓存值。
	get duration() {
		return this.syncState().duration
	}

	get currentTime() {
		return this.syncState().currentTime
	}

	get paused() {
		return this.syncState().paused
	}

	syncState() {
		if (!this.destroyed) {
			const result = invokeAPI('InnerAudio.getState', { instanceId: this.instanceId }, 'container', false)
			if (result && typeof result === 'object') {
				const { duration, currentTime, paused } = result
				if (typeof duration === 'number') this._state.duration = duration
				if (typeof currentTime === 'number') this._state.currentTime = currentTime
				if (typeof paused === 'boolean') this._state.paused = paused
			}
		}
		return this._state
	}

	/**
	 * 播放
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.play.html
	 */
	play() {
		if (this.destroyed) return
		this.control('InnerAudio.play', {}, () => {
			this._state.paused = false
		})
	}

	/**
	 * 暂停
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.pause.html
	 */
	pause() {
		if (this.destroyed) return
		this.control('InnerAudio.pause', {}, () => {
			this._state.paused = true
		})
	}

	/**
	 * 停止
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.stop.html
	 */
	stop() {
		if (this.destroyed) return
		this.control('InnerAudio.stop', {}, () => {
			this._state.paused = true
		})
	}

	/**
	 * 跳转到指定位置（单位秒）
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.seek.html
	 */
	seek(position) {
		if (this.destroyed) return
		if (typeof position !== 'number' || !Number.isFinite(position) || position < 0) return
		this.control('InnerAudio.seek', { position })
	}

	/**
	 * 销毁实例。销毁后所有方法均为 no-op（不再下发 wire、不抛错），事件表清空。
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/audio/InnerAudioContext.destroy.html
	 */
	destroy() {
		if (this.destroyed) return
		this.destroyed = true
		for (const listeners of this.events.values()) {
			for (const id of listeners.keys()) releaseId(id)
		}
		this.events.clear()
		this._state.paused = true
		this.control('InnerAudio.destroy', {})
	}

	control(name, params, onSuccess) {
		// 按契约直接传 callback.store() 的一次性字符串 id，不走 Promise 化；成对登记，
		// 任一条回执先到就把两条都摘掉，避免另一条永久留在回调表里。
		const payload = { ...params, instanceId: this.instanceId }
		let successId
		let failId
		const settle = () => {
			callback.remove(successId)
			callback.remove(failId)
		}
		successId = callback.store(() => {
			settle()
			if (onSuccess) onSuccess()
		})
		failId = callback.store(() => {
			// 失败详情由原生按契约另路推 error 事件，这里只消费本次调用的回执并清账，
			// 不做二次分发，避免与 onError 监听重复触发。
			settle()
		})
		payload.success = successId
		payload.fail = failId
		invokeAPI(name, payload, 'container', false)
	}

	registerEvent(event, id) {
		invokeAPI('InnerAudio.on', { instanceId: this.instanceId, event, success: id }, 'container', false)
	}

	unregisterEvent(event, id) {
		const params = { instanceId: this.instanceId, event }
		if (id !== undefined) params.success = id
		invokeAPI('InnerAudio.off', params, 'container', false)
	}
}

for (const [method, event] of Object.entries(INNER_AUDIO_EVENTS)) {
	InnerAudioContext.prototype[method] = function onInnerAudioEvent(cb) {
		addEventListener(this, event, cb, (id) => this.registerEvent(event, id))
	}
	InnerAudioContext.prototype[`off${method.slice(2)}`] = function offInnerAudioEvent(cb) {
		removeEventListener(this, event, cb, (id) => this.unregisterEvent(event, id))
	}
}

/**
 * 微信音频实例的录音端：单例 per 小程序，事件为独立 wire 名
 * （Recorder.onStart / Recorder.offStart ...），不带 event 字段。
 */
class RecorderManager {
	/**
	 * RecorderManager
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/RecorderManager.html
	 */
	constructor() {
		this.events = new Map()
	}

	/**
	 * 开始录音。format 仅支持 aac / wav，其余取值按微信语义走 onError 失败，不下发原生。
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/RecorderManager.start.html
	 */
	start(options = {}) {
		const {
			duration = DEFAULT_RECORD_DURATION,
			sampleRate,
			numberOfChannels,
			encodeBitRate,
			format = 'aac',
		} = options || {}
		if (format !== 'aac' && format !== 'wav') {
			// 微信语义：不支持的格式走 onError 失败，不下发原生。
			emitEvent(this, 'onError', { errMsg: 'Recorder.start:fail invalid format' })
			return
		}
		const params = { duration: normalizeRecordDuration(duration), format }
		if (sampleRate !== undefined) params.sampleRate = sampleRate
		if (numberOfChannels !== undefined) params.numberOfChannels = numberOfChannels
		if (encodeBitRate !== undefined) params.encodeBitRate = encodeBitRate
		// start 的失败回执按契约由 invokeFail 送回 fail id，转发给 onError 监听；
		// 成功侧只登记一个清理用空回调，哪条先到都能把两条回执清干净。
		let successId
		let failId
		const settle = () => {
			callback.remove(successId)
			callback.remove(failId)
		}
		successId = callback.store(() => settle())
		failId = callback.store((res) => {
			settle()
			emitEvent(this, 'onError', res)
		})
		invokeAPI('Recorder.start', {
			...params,
			success: successId,
			fail: failId,
		}, 'container', false)
	}

	/**
	 * 暂停录音
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/RecorderManager.pause.html
	 */
	pause() {
		this.control('Recorder.pause')
	}

	/**
	 * 继续录音
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/RecorderManager.resume.html
	 */
	resume() {
		this.control('Recorder.resume')
	}

	/**
	 * 停止录音，成功后 onStop 事件带回 {tempFilePath, duration, fileSize}
	 * https://developers.weixin.qq.com/miniprogram/dev/api/media/recorder/RecorderManager.stop.html
	 */
	stop() {
		this.control('Recorder.stop')
	}

	control(name) {
		// 与 InnerAudioContext.control 同款的一次性成对回执 id，回执本身无业务语义，
		// 只保证 native 的 invokeSuccess/invokeFail 都有真实 id 可送达并随手清账。
		let successId
		let failId
		const settle = () => {
			callback.remove(successId)
			callback.remove(failId)
		}
		successId = callback.store(settle)
		failId = callback.store(settle)
		invokeAPI(name, {
			success: successId,
			fail: failId,
		}, 'container', false)
	}

	registerEvent(method, id) {
		invokeAPI(`Recorder.${method}`, { success: id }, 'container', false)
	}

	unregisterEvent(method, id) {
		const params = id === undefined ? {} : { success: id }
		invokeAPI(`Recorder.off${method.slice(2)}`, params, 'container', false)
	}
}

for (const method of RECORDER_EVENTS) {
	RecorderManager.prototype[method] = function onRecorderEvent(cb) {
		addEventListener(this, method, cb, (id) => this.registerEvent(method, id))
	}
	RecorderManager.prototype[`off${method.slice(2)}`] = function offRecorderEvent(cb) {
		removeEventListener(this, method, cb, (id) => this.unregisterEvent(method, id))
	}
}

function normalizeRecordDuration(duration) {
	const value = Number(duration)
	if (!Number.isFinite(value) || value <= 0) return DEFAULT_RECORD_DURATION
	return Math.min(value, MAX_RECORD_DURATION)
}
