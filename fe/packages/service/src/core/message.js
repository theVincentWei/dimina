/* eslint-disable no-undef */
import { callback, isWebWorker } from '@dimina/common'
import mitt from 'mitt'
import { dispatchBackgroundWork } from './background-scheduler'
import { reportAppError } from './app-events'
import { decodeDataFunctions, encodeDataFunctions } from './data-function'

// Resource/lifecycle control must remain live while business work is suspended.
const controlMessages = new Set([
	'appHide', 'appShow', 'stackHide', 'stackShow', 'pageHide', 'pageShow', 'pageUnload',
	'mU', 'mT', 'flushCallbacks', 'hostEnvUpdate', 'loadResource', 'resourceLoaded',
	'pageAttached', 'pageReady', 'mC', 'mA', 'mR', 'canvasCapabilities', 'canvasContextEvent', 'canvasTransfer', 'canvasTransferredDispose', 'resourceLoadFailed',
	'fontFaceLoaded',
	// [rperf] 微维测上报须在业务工作暂停/积压时仍然实时到达，否则 5s dump 的 rnd 恒为 null
	'rperfRender',
	// 小游戏触摸事件与 rAF tick 同级：高频渲染下不得进 background 队列饿死（R2 输入延迟）
	'gameTouch',
])

class Message {
	constructor() {
		this.event = mitt()
		this.init()
	}

	init() {
		// 逻辑层监听容器层消息
		if (isWebWorker) {
			globalThis.onmessage = (e) => {
				this.handleMsg(e.data)
			}
		}
		else {
			DiminaServiceBridge.onMessage = this.handleMsg.bind(this)
		}
	}

	handleMsg(msg) {
		try {
			const decodedMsg = decodeDataFunctions(msg)
			if (import.meta.env.DEV) console.log('[service] receive msg: ', decodedMsg.type)
			const { type, body } = decodedMsg
			// [game-touch] 输入埋点（原 patch-jssdk-input.py P4 源码化）：handleMsg 入口墙钟。
			// gameTouch 走 controlMessages 直通，回调处用它算 __svcWait（service 内部排队）。
			if (type === 'gameTouch') globalThis.__gtAt = Date.now()
			const deliver = () => {
				try { this.event.emit(type, body) }
				catch (error) { reportAppError(error) }
			}
			if (controlMessages.has(type) || (type === 'triggerCallback' && callback.isAllowedInBackground(body?.id))) {
				deliver()
			}
			else dispatchBackgroundWork(deliver)
		}
		catch (error) {
			reportAppError(error)
			console.error('[service] message handler error:', error)
		}
	}

	// 向逻辑层注册消息监听
	on(type, callback) {
		this.event.on(type, callback)
	}

	off(type) {
		this.event.off(type)
	}

	// 逻辑层透过容器层中转向渲染层发送消息
	send(msg, encoded = false) {
		if (isWebWorker) {
			Message.prototype.send = function (msg, encoded = false) {
				const encodedMsg = encoded ? msg : encodeDataFunctions(msg)
				encodedMsg.method = 'publish'
				globalThis.postMessage(encodedMsg)
			}
		}
		else {
			Message.prototype.send = function (msg, encoded = false) {
				const encodedMsg = encoded ? msg : encodeDataFunctions(msg)
				return DiminaServiceBridge.publish(encodedMsg.body.bridgeId || '', encodedMsg)
			}
		}
		return this.send(msg, encoded)
	}

	// 逻辑层向容器层发送消息
	invoke(msg) {
		if (isWebWorker) {
			Message.prototype.invoke = function (msg) {
				msg.method = 'invoke'
				globalThis.postMessage(msg)
			}
		}
		else {
			Message.prototype.invoke = function (msg) {
				return DiminaServiceBridge.invoke(msg)
			}
		}

		return this.invoke(msg)
	}
}

export default new Message()
