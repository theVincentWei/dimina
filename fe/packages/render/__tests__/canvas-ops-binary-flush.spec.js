import { encodeOpsBinary, encodeOpsCompact } from '@dimina/common'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import runtime from '../src/core/runtime.js'

const NODE_ID = 'binary_flush_node'
const CONTEXT_ID = 'ctx_binary'

let canvas
let gl

function installWebglNode() {
	gl = {
		VERTEX_SHADER: 0x8B31,
		createShader: vi.fn(() => ({ kind: 'shader' })),
		shaderSource: vi.fn(),
		compileShader: vi.fn(),
		viewport: vi.fn(),
		uniform3f: vi.fn(),
	}
	canvas = document.createElement('canvas')
	canvas.getContext = vi.fn(() => gl)
	runtime.canvasNodes.set(NODE_ID, { canvas, contexts: new Map() })
}

function batch() {
	return [
		{ op: 'setCanvasProperty', prop: 'width', value: 640 },
		{ op: 'getContext', contextId: CONTEXT_ID, contextType: 'webgl' },
		{ op: 'contextCall', contextId: CONTEXT_ID, method: 'viewport', args: [0, 0, 320, 160] },
		{ op: 'contextCall', contextId: CONTEXT_ID, method: 'createShader', args: [gl.VERTEX_SHADER], resultId: 'shader_b' },
		{ op: 'contextCall', contextId: CONTEXT_ID, method: 'shaderSource', args: [{ __canvasResourceId: 'shader_b' }, 'void main() {}'] },
		{ op: 'contextCall', contextId: CONTEXT_ID, method: 'compileShader', args: [{ __canvasResourceId: 'shader_b' }] },
	]
}

function packedEnvelope(buffer) {
	// 容器 WebMessagePort 还原后的形态：packed 是 WebView 侧的 Uint8Array 视图。
	return { __canvasTypedArray: 'Uint8Array', packed: new Uint8Array(buffer) }
}

function base64Envelope(buffer) {
	const bytes = new Uint8Array(buffer)
	let binary = ''
	for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
	return { __canvasTypedArray: 'Uint8Array', base64: btoa(binary) }
}

beforeEach(() => {
	runtime.canvasNodes.clear()
	runtime.canvasResources.clear()
	installWebglNode()
})

afterEach(() => {
	runtime.canvasNodes.delete(NODE_ID)
	runtime.canvasResources.delete(CONTEXT_ID)
	vi.restoreAllMocks()
})

describe('canvasNodeFlush operationsBinary tri-state decode', () => {
	it('decodes a packed binary envelope and replays the batch against the real context', () => {
		const operations = batch()
		const { buffer } = encodeOpsBinary(operations)
		const params = { nodeId: NODE_ID, operationsBinary: packedEnvelope(buffer) }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_binary', params })
		expect(Array.isArray(params.operations)).toBe(true)
		expect(params.operations).toEqual(operations)
		expect(Number.isFinite(params.__rperfDecodeMs)).toBe(true)
		expect(canvas.width).toBe(640)
		expect(canvas.getContext).toHaveBeenCalledWith('webgl', undefined)
		const shader = gl.createShader.mock.results[0].value
		expect(gl.viewport).toHaveBeenCalledWith(0, 0, 320, 160)
		expect(gl.shaderSource).toHaveBeenCalledWith(shader, 'void main() {}')
		expect(gl.compileShader).toHaveBeenCalledWith(shader)
	})

	it('decodes the base64 fallback envelope used when the native binary session is unavailable', () => {
		const operations = batch()
		const params = { nodeId: NODE_ID, operationsBinary: base64Envelope(encodeOpsBinary(operations).buffer) }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_binary_b64', params })
		expect(params.operations).toEqual(operations)
		expect(gl.viewport).toHaveBeenCalledWith(0, 0, 320, 160)
	})

	it('keeps the compact string path working and prefers the binary branch when both are present', () => {
		const operations = batch()
		const legacy = { nodeId: NODE_ID, operationsCompact: encodeOpsCompact(operations) }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_compact', params: legacy })
		expect(legacy.operations).toEqual(operations)

		const both = {
			nodeId: NODE_ID,
			operationsCompact: encodeOpsCompact([{ op: 'setCanvasProperty', prop: 'width', value: 1 }]),
			operationsBinary: packedEnvelope(encodeOpsBinary(operations).buffer),
		}
		runtime.canvasNodeFlush({ bridgeId: 'bridge_both', params: both })
		expect(both.operations).toEqual(operations)
	})

	it('keeps the legacy operations array path working without decoding', () => {
		const operations = [{ op: 'getContext', contextId: CONTEXT_ID, contextType: 'webgl' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'viewport', args: [10, 20, 30, 40] }]
		const params = { nodeId: NODE_ID, operations }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_legacy', params })
		expect(params.operations).toBe(operations)
		expect('__rperfDecodeMs' in params).toBe(false)
		expect(gl.viewport).toHaveBeenCalledWith(10, 20, 30, 40)
	})

	it('never throws on a corrupt binary envelope: the frame is warned and rendered empty', () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
		const params = { nodeId: NODE_ID, operationsBinary: { __canvasTypedArray: 'Uint8Array', packed: new Uint8Array([1, 2, 3]) } }
		expect(() => runtime.canvasNodeFlush({ bridgeId: 'bridge_corrupt', params })).not.toThrow()
		expect(params.operations).toEqual([])
		expect(warn).toHaveBeenCalled()
	})
})
