import { decodeOpsBinary } from '@dimina/common'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import router from '../src/core/router.js'
import { createOffscreenCanvas, resetMiniGameCanvas } from '../src/api/core/ui/canvas/canvas-node.js'

// [rperf] GL 指令流二进制传输 A/B 开关：storage 'rperf_binary_on' 控制是否把
// canvasNodeFlush 的 instructions 走 __diminaCanvasBuffers 纯二进制旁路，
// 关闭（默认）时维持 operationsCompact 紧凑字符串。

const nodes = []
let serial = 0
let bridgeId
let clock
let now = 1_700_000_000_000
let storage
const oldBridge = {}

function advance(ms) {
	now += ms
}

function publishMessages(name) {
	return globalThis.DiminaServiceBridge.publish.mock.calls
		.map(call => call[1].body)
		.filter(body => body.name === name)
}

function lastFlushParams() {
	return publishMessages('canvasNodeFlush').at(-1).params
}

async function flushWith(glCalls) {
	const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
	nodes.push(canvas)
	const gl = canvas.getContext('webgl')
	glCalls(gl)
	await Promise.resolve()
	await Promise.resolve()
	return canvas
}

beforeEach(() => {
	bridgeId = `perf-binary-${serial++}`
	router.setInitId(bridgeId)
	resetMiniGameCanvas()
	oldBridge.invoke = globalThis.DiminaServiceBridge.invoke
	oldBridge.publish = globalThis.DiminaServiceBridge.publish
	oldBridge.encodeCanvasArrayBuffer = globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer
	oldBridge.canvasBinarySupported = globalThis.DiminaServiceBridge.canvasBinarySupported
	globalThis.DiminaServiceBridge.publish = vi.fn()
	globalThis.DiminaServiceBridge.canvasBinarySupported = true
	globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer = vi.fn(buffer => ({
		packed: buffer,
		// 原生在 rle32 划算时会附 encoding/byteLength；此处只用未压缩形态。
	}))
	storage = ''
	// getStorageSync 走 invokeAPI → DiminaServiceBridge.invoke 同步返回。
	globalThis.DiminaServiceBridge.invoke = vi.fn((message) => {
		if (message?.body?.name === 'getStorageSync') return storage
		return { contexts: {}, typedArrays: [] }
	})
	clock = vi.spyOn(Date, 'now')
	// 单调递增，避免跨用例复用时开关 5s 缓存把上一用例的时间戳当成"刚刚读过"。
	now += 1_000_000
	clock.mockImplementation(() => now)
})

afterEach(() => {
	for (const node of nodes.splice(0)) {
		try { node.dispose() }
		catch { /* 测试清理容错 */ }
	}
	clock.mockRestore()
	globalThis.DiminaServiceBridge.invoke = oldBridge.invoke
	globalThis.DiminaServiceBridge.publish = oldBridge.publish
	globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer = oldBridge.encodeCanvasArrayBuffer
	if (oldBridge.canvasBinarySupported === undefined) delete globalThis.DiminaServiceBridge.canvasBinarySupported
	else globalThis.DiminaServiceBridge.canvasBinarySupported = oldBridge.canvasBinarySupported
	resetMiniGameCanvas()
})

describe('canvasNodeFlush binary A/B switch', () => {
	it('uses the binary path by default (生产默认开启) and never falls back to compact', async () => {
		await flushWith(gl => gl.viewport(0, 0, 100, 50))
		advance(6000) // 越过开关缓存窗口，读一次 storage（默认空 → 保留构建期默认 = 二进制）
		await flushWith(gl => gl.viewport(0, 0, 120, 60))
		const params = lastFlushParams()
		expect(params.operationsBinary?.__canvasTypedArray).toBe('Uint8Array')
		expect(params.operationsCompact).toBeUndefined()
	})

	it('does not let mini-program storage disable the build-time default', async () => {
		// 小程序侧误写 '0' 不得覆盖构建期默认（避免 A/B 被小程序行为污染）
		const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
		nodes.push(canvas)
		const gl = canvas.getContext('webgl')
		storage = '0'
		advance(6000)
		gl.viewport(0, 0, 130, 70)
		await Promise.resolve()
		await Promise.resolve()
		const params = lastFlushParams()
		expect(params.operationsBinary?.__canvasTypedArray).toBe('Uint8Array')
		expect(params.operationsCompact).toBeUndefined()
	})

	it('sends a packed binary envelope carrying every instruction when storage is 1', async () => {
		const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
		nodes.push(canvas)
		const gl = canvas.getContext('webgl')
		storage = '1'
		advance(6000)
		gl.viewport(0, 0, 111, 222)
		gl.uniform3f({ __canvasResourceId: 'loc' }, 1.5, -2, 0)
		await Promise.resolve()
		await Promise.resolve()
		const params = lastFlushParams()
		expect(params.operationsCompact).toBeUndefined()
		expect(params.operationsBinary.__canvasTypedArray).toBe('Uint8Array')
		expect(params.operationsBinary.packed).toBeInstanceOf(ArrayBuffer)
		// 原生收到的正是编码后的指令流；渲染层解码应还原完整批次。
		const [nativeBuffer, nativeBridgeId] = globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer.mock.calls[0]
		expect(nativeBridgeId).toBe(bridgeId)
		const operations = decodeOpsBinary(nativeBuffer)
		expect(operations).toEqual(params.operationsBinary.packed ? decodeOpsBinary(params.operationsBinary.packed) : [])
		expect(operations.map(operation => operation.method).filter(Boolean)).toContain('viewport')
		expect(operations.find(operation => operation.method === 'uniform3f').args).toEqual([
			{ __canvasResourceId: 'loc' },
			1.5,
			-2,
			0,
		])
	})

	it('falls back to the base64 envelope when the native binary session reports unavailable', async () => {
		globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer = vi.fn(buffer => ({
			base64: Buffer.from(new Uint8Array(buffer)).toString('base64'),
		}))
		const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
		nodes.push(canvas)
		const gl = canvas.getContext('webgl')
		storage = '1'
		advance(6000)
		gl.viewport(0, 0, 64, 32)
		await Promise.resolve()
		await Promise.resolve()
		const params = lastFlushParams()
		expect(params.operationsCompact).toBeUndefined()
		expect(typeof params.operationsBinary.base64).toBe('string')
		expect(params.operationsBinary.packed).toBeUndefined()
	})

	it('falls back to operationsCompact for the whole batch when an unknown op cannot be encoded', async () => {
		const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
		nodes.push(canvas)
		storage = '1'
		advance(6000)
		canvas.enqueueOperation({ op: 'brandNewOperation', foo: 1 })
		canvas.flushOperations()
		const params = lastFlushParams()
		expect(typeof params.operationsCompact).toBe('string')
		expect(params.operationsBinary).toBeUndefined()
		expect(globalThis.DiminaServiceBridge.encodeCanvasArrayBuffer).not.toHaveBeenCalled()
	})

	it('retries the same batch as operationsCompact when the binary publish is rejected', async () => {
		let flushAttempts = 0
		// DiminaServiceBridge.publish(bridgeId, message) 的第二个参数才是消息体。
		globalThis.DiminaServiceBridge.publish = vi.fn((_bridgeId, msg) => {
			// 只让第一次 canvasNodeFlush 投递失败（二进制通道拒收），后续紧凑重试要成功。
			if (msg?.body?.name === 'canvasNodeFlush' && ++flushAttempts === 1) {
				throw new Error('Canvas binary page is unavailable')
			}
		})
		const canvas = createOffscreenCanvas({ type: 'webgl', width: 1, height: 1 })
		nodes.push(canvas)
		const gl = canvas.getContext('webgl')
		storage = '1'
		advance(6000)
		gl.viewport(0, 0, 32, 16)
		await Promise.resolve()
		await Promise.resolve()
		const flushes = publishMessages('canvasNodeFlush')
		// 二进制投递失败后同一批改走紧凑字符串，批次不丢。
		const last = flushes.at(-1).params
		expect(typeof last.operationsCompact).toBe('string')
		expect(last.operationsBinary).toBeUndefined()
	})
})
