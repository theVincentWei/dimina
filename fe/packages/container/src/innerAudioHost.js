/**
 * Web 容器的 InnerAudioContext 宿主实现(HTMLAudioElement)。
 *
 * wire 协议对照 fe/packages/service/src/api/core/media/audio/index.js:
 * - 控制/属性命令(setSrc/setProperty/play/pause/stop/seek/destroy)的 params 带
 *   instanceId 与成对的一次性 success/fail 回执 id,任一回执先到即双清,所以宿主
 *   成功时只点 success、失败时只点 fail;
 * - InnerAudio.on 的 params.success 是常驻事件回调 id(服务侧 keep 登记),宿主保存
 *   它、事件发生时用 createCallbackFunction(id)(payload) 回点火;InnerAudio.off 摘除;
 * - getState 在 Web 桥上是异步、服务侧读不到返回值时回落本地缓存,注册处理器只为
 *   不落 "api is not supported" 警告,返回值仅对同步桥有意义;
 * - Recorder.* 未实现(录音需 getUserMedia,另案),继续落 unsupported 警告。
 *
 * src 解析对照 webFileSystem.ts 的 `new URL(path, resourceBaseUrl)` 约定:
 * data:/blob:/http(s): 原样用;`/` 开头相对 resourceBaseUrl;纯相对路径补
 * `<appId>/main/` 前缀(与 dmcc 产物静态布局一致,子包根暂不支持)。
 */

const instances = new Map()

const DOM_EVENT_MAP = {
	canplay: 'canplay',
	play: 'play',
	pause: 'pause',
	ended: 'ended',
	timeupdate: 'timeupdate',
	waiting: 'waiting',
	seeking: 'seeking',
	seeked: 'seeked',
	loadedmetadata: 'loadedmetadata',
	error: 'error',
}

function resolveAudioSrc(app, src) {
	if (!src) {
		return ''
	}
	if (/^(data:|blob:|https?:)/i.test(src)) {
		return src
	}
	const base = new URL(app.getResourceBaseUrl(), window.location.origin)
	if (src.startsWith('/')) {
		return new URL(src.slice(1), base).toString()
	}
	return new URL(`${app.appInfo.appId}/main/${src}`, base).toString()
}

function ensureDomHandlers(app, rec) {
	if (rec.attached) {
		return
	}
	rec.attached = true
	const audio = rec.audio
	const emit = (event, payload) => {
		const ids = rec.handlers.get(event)
		if (!ids) {
			return
		}
		for (const id of [...ids]) {
			if (id !== undefined) {
				app.createCallbackFunction(id)?.(payload)
			}
		}
	}
	rec.emit = emit
	for (const [domEvent, wireEvent] of Object.entries(DOM_EVENT_MAP)) {
		audio.addEventListener(domEvent, () => {
			if (domEvent === 'timeupdate') {
				emit(wireEvent, { duration: audio.duration || 0, currentTime: audio.currentTime || 0 })
			}
			else if (domEvent === 'loadedmetadata') {
				emit(wireEvent, { duration: audio.duration || 0 })
			}
			else if (domEvent === 'error') {
				emit(wireEvent, { errMsg: `audio error (code ${audio.error?.code ?? 'unknown'})`, errno: audio.error?.code })
			}
			else if (!(domEvent === 'pause' && rec.stopping)) {
				// stop() 会先 pause 再归零,DOM 的 pause 事件这次不算,stop 由 stop() 自己发。
				emit(wireEvent)
			}
		})
	}
}

function instanceFor(instanceId) {
	// 服务侧 createInnerAudioContext 只在本地生成 instanceId,没有「创建」wire 命令,
	// 所以宿主在首条命令到达时懒创建。
	let rec = instances.get(instanceId)
	if (!rec) {
		rec = {
			audio: new Audio(),
			handlers: new Map(),
			startTime: 0,
			autoplay: false,
			obeyMuteSwitch: true,
			stopping: false,
			attached: false,
			emit: null,
		}
		instances.set(instanceId, rec)
	}
	return rec
}

function ackOf(app, id, payload) {
	if (id !== undefined) {
		app.createCallbackFunction(id)?.(payload)
	}
}

export function registerInnerAudioApi(container) {
	container.registerApi('InnerAudio.setSrc', function (params = {}) {
		const { instanceId, src, success, fail } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			ackOf(this, fail)
			return
		}
		rec.audio.src = resolveAudioSrc(this, src)
		rec.audio.load()
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.setProperty', function (params = {}) {
		const { instanceId, startTime, autoplay, loop, obeyMuteSwitch, volume, playbackRate, success } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			return
		}
		const audio = rec.audio
		if (typeof loop === 'boolean') {
			audio.loop = loop
		}
		if (typeof volume === 'number') {
			audio.volume = Math.min(1, Math.max(0, volume))
		}
		if (typeof playbackRate === 'number' && playbackRate > 0) {
			audio.playbackRate = playbackRate
		}
		if (typeof startTime === 'number' && startTime >= 0) {
			rec.startTime = startTime
		}
		if (typeof obeyMuteSwitch === 'boolean') {
			rec.obeyMuteSwitch = obeyMuteSwitch // Web 无硬件静音开关,仅记录不生效
		}
		if (typeof autoplay === 'boolean') {
			rec.autoplay = autoplay
			audio.autoplay = autoplay
		}
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.play', function (params = {}) {
		const { instanceId, success, fail } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			ackOf(this, fail)
			return
		}
		const audio = rec.audio
		rec.stopping = false
		if (rec.startTime > 0 && !audio.currentTime && !audio.loop) {
			try {
				audio.currentTime = rec.startTime
			}
			catch { /* 个别浏览器在未加载元数据前设置会抛,交给浏览器默认从头播 */ }
		}
		const playing = audio.play()
		if (playing && typeof playing.then === 'function') {
			playing
				.then(() => ackOf(this, success))
				.catch((error) => {
					ackOf(this, fail)
					rec.emit?.('error', { errMsg: `play:fail ${error.name || error.message}` })
				})
		}
		else {
			ackOf(this, success)
		}
	})

	container.registerApi('InnerAudio.pause', function (params = {}) {
		const { instanceId, success } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			return
		}
		rec.audio.pause()
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.stop', function (params = {}) {
		const { instanceId, success } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			return
		}
		const audio = rec.audio
		rec.stopping = true
		audio.pause()
		try {
			audio.currentTime = 0
		}
		catch { /* 未加载元数据时置 0 可能抛,忽略 */ }
		rec.stopping = false
		rec.emit?.('stop') // DOM 没有 stop 事件,按微信语义自己发
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.seek', function (params = {}) {
		const { instanceId, position, success } = params
		const rec = instanceFor(instanceId)
		if (!rec || typeof position !== 'number' || !Number.isFinite(position) || position < 0) {
			return
		}
		try {
			rec.audio.currentTime = position
		}
		catch { /* 元数据未就绪,浏览器加载后按请求位置不可恢复时忽略 */ }
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.destroy', function (params = {}) {
		const { instanceId, success } = params
		const rec = instanceFor(instanceId)
		if (!rec) {
			return
		}
		rec.audio.pause()
		rec.audio.removeAttribute('src')
		rec.audio.load() // 释放底层资源
		rec.handlers.clear()
		instances.delete(instanceId)
		ackOf(this, success)
	})

	container.registerApi('InnerAudio.getState', function (params = {}) {
		const rec = instanceFor(params?.instanceId)
		if (!rec) {
			return { duration: 0, currentTime: 0, paused: true }
		}
		const { audio } = rec
		return {
			duration: Number.isFinite(audio.duration) ? audio.duration : 0,
			currentTime: audio.currentTime || 0,
			paused: audio.paused !== false,
		}
	})

	container.registerApi('InnerAudio.on', function (params = {}) {
		const { instanceId, event, success } = params
		const rec = instanceFor(instanceId)
		if (!rec || !event || success === undefined) {
			return
		}
		ensureDomHandlers(this, rec)
		let ids = rec.handlers.get(event)
		if (!ids) {
			rec.handlers.set(event, ids = new Set())
		}
		ids.add(success)
	})

	container.registerApi('InnerAudio.off', function (params = {}) {
		const { instanceId, event, success } = params
		const rec = instanceFor(instanceId)
		if (!rec || !event) {
			return
		}
		if (success === undefined) {
			rec.handlers.delete(event)
		}
		else {
			rec.handlers.get(event)?.delete(success)
		}
	})
}

/**
 * 测试辅助:按 instanceId 取宿主实例(断言 audio 元素状态用)。
 */
export function hostAudioInstanceForTest(instanceId) {
	return instances.get(instanceId)
}

export function resetHostAudioInstancesForTest() {
	instances.clear()
}
