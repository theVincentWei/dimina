import { encodeOpsCompact } from '@dimina/common'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import runtime from '../src/core/runtime.js'

const NODE_ID = 'compact_flush_node'
const CONTEXT_ID = 'ctx_compact'

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
		getUniformLocation: vi.fn(() => ({ kind: 'location' })),
	}
	canvas = document.createElement('canvas')
	canvas.getContext = vi.fn(() => gl)
	runtime.canvasNodes.set(NODE_ID, { canvas, contexts: new Map() })
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

describe('canvasNodeFlush operationsCompact dual state', () => {
	it('decodes a compact string batch and replays it against the real context', () => {
		const operations = [
			{ op: 'setCanvasProperty', prop: 'width', value: 600 },
			{ op: 'getContext', contextId: CONTEXT_ID, contextType: 'webgl' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'viewport', args: [0, 0, 300, 150] },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'createShader', args: [gl.VERTEX_SHADER], resultId: 'shader_c' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'shaderSource', args: [{ __canvasResourceId: 'shader_c' }, 'void main() {}'] },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'compileShader', args: [{ __canvasResourceId: 'shader_c' }] },
		]
		const params = { nodeId: NODE_ID, operationsCompact: encodeOpsCompact(operations) }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_compact', params })
		expect(typeof params.operationsCompact).toBe('string')
		expect(Array.isArray(params.operations)).toBe(true)
		expect(params.operations).toEqual(operations)
		expect(Number.isFinite(params.__rperfDecodeMs)).toBe(true)
		expect(canvas.width).toBe(600)
		expect(canvas.getContext).toHaveBeenCalledWith('webgl', undefined)
		const shader = gl.createShader.mock.results[0].value
		expect(gl.viewport).toHaveBeenCalledTimes(1)
		expect(gl.viewport).toHaveBeenCalledWith(0, 0, 300, 150)
		expect(gl.shaderSource).toHaveBeenCalledTimes(1)
		expect(gl.shaderSource).toHaveBeenCalledWith(shader, 'void main() {}')
		expect(gl.compileShader).toHaveBeenCalledTimes(1)
		expect(gl.compileShader).toHaveBeenCalledWith(shader)
	})

	it('carries non-finite numbers and hostile strings through the compact bridge intact', () => {
		const payload = '中文 "引号" \\backslash\\ \u001e\u001f!raw'
		const operations = [
			{ op: 'getContext', contextId: CONTEXT_ID, contextType: 'webgl' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'createShader', args: [gl.VERTEX_SHADER], resultId: 'shader_c' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'getUniformLocation', args: [{ __canvasResourceId: 'shader_c' }, 'u_color'], resultId: 'loc_c', feedback: 'resource' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'uniform3f', args: [{ __canvasResourceId: 'loc_c' }, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY] },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'shaderSource', args: [{ __canvasResourceId: 'shader_c' }, payload] },
		]
		const params = { nodeId: NODE_ID, operationsCompact: encodeOpsCompact(operations) }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_compact', params })
		const shader = gl.createShader.mock.results[0].value
		const location = gl.getUniformLocation.mock.results[0].value
		// args 数组内的 NaN/Infinity 按 JSON 语义送达为 null——与旧 JSON 对象树路径一致（非紧凑流回归）；
		// 字段级非有限数的真无损由 common 包单测覆盖。
		expect(gl.uniform3f).toHaveBeenCalledTimes(1)
		const [boundLocation, x, y, z] = gl.uniform3f.mock.calls[0]
		expect(boundLocation).toBe(location)
		expect(x).toBeNull()
		expect(y).toBeNull()
		expect(z).toBeNull()
		expect(gl.shaderSource).toHaveBeenCalledTimes(1)
		expect(gl.shaderSource).toHaveBeenCalledWith(shader, payload)
	})

	it('keeps the legacy operations array path working without decoding', () => {
		const operations = [
			{ op: 'getContext', contextId: CONTEXT_ID, contextType: 'webgl' },
			{ op: 'contextCall', contextId: CONTEXT_ID, method: 'viewport', args: [10, 20, 30, 40] },
		]
		const params = { nodeId: NODE_ID, operations }
		runtime.canvasNodeFlush({ bridgeId: 'bridge_legacy', params })
		expect(params.operations).toBe(operations)
		expect('__rperfDecodeMs' in params).toBe(false)
		expect(gl.viewport).toHaveBeenCalledTimes(1)
		expect(gl.viewport).toHaveBeenCalledWith(10, 20, 30, 40)
		expect(canvas.width).toBe(300)
	})
})
