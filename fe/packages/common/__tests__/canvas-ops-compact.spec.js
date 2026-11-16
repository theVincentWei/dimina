import { describe, expect, it } from 'vitest'
import { decodeOpsCompact, encodeOpsCompact } from '../src/core/canvas-ops-compact'

function roundTripValue(value) {
	return decodeOpsCompact(encodeOpsCompact([{ value }]))[0].value
}

describe('canvas-ops-compact wire format', () => {
	it('separates records with \\u001e, fields with \\u001f and prefixes typed values', () => {
		expect(encodeOpsCompact([])).toBe('')
		expect(encodeOpsCompact('not-an-array')).toBe('')
		expect(encodeOpsCompact([{ n: 1.5 }])).toBe('n\u001f1.5')
		expect(encodeOpsCompact([{ op: 'contextCall', args: [1.5, -2, 0] }]))
			.toBe('op\u001f!""contextCall"\u001fargs\u001f!j[1.5,-2,0]')
		expect(encodeOpsCompact([{ a: Number.NaN }, { b: true, c: null }]))
			.toBe('a\u001f_N\u001eb\u001f!t\u001fc\u001f!z')
	})

	it('round-trips numbers exactly, including zero, negatives, decimals and non-finite sentinels', () => {
		for (const n of [0, -1, -273.15, 0.1, 1e21, 1e-7, Number.MAX_SAFE_INTEGER]) {
			expect(roundTripValue(n)).toBe(n)
		}
		expect(Object.is(roundTripValue(Number.NaN), Number.NaN)).toBe(true)
		expect(Object.is(roundTripValue(Number.POSITIVE_INFINITY), Number.POSITIVE_INFINITY)).toBe(true)
		expect(Object.is(roundTripValue(Number.NEGATIVE_INFINITY), Number.NEGATIVE_INFINITY)).toBe(true)
		expect(decodeOpsCompact('n\u001f_N')[0].n).toBeNaN()
		expect(decodeOpsCompact('n\u001f_I')[0].n).toBe(Number.POSITIVE_INFINITY)
		expect(decodeOpsCompact('n\u001f_J')[0].n).toBe(Number.NEGATIVE_INFINITY)
	})

	it('round-trips strings losslessly across empty, unicode, separators, quotes and prefix lookalikes', () => {
		const samples = [
			'',
			'中文上下文与 emoji 🎨',
			'a\u001eb\u001fc',
			'"quotes" and \\backslash\\',
			'!t looks boolean',
			'!j {"looksLike": "object"}',
			'_N _I _J',
			'123',
			'!',
			'  spaced  ',
		]
		for (const s of samples) {
			expect(roundTripValue(s)).toBe(s)
		}
	})

	it('keeps booleans and null and drops undefined fields like JSON', () => {
		const decoded = decodeOpsCompact(encodeOpsCompact([{ a: true, b: false, c: null, d: undefined }]))[0]
		expect(decoded).toEqual({ a: true, b: false, c: null })
		expect('d' in decoded).toBe(false)
	})

	it('round-trips nested objects, arrays and GL typed-array serialization envelopes', () => {
		const operation = {
			op: 'contextCall',
			contextId: 'ctx_tex',
			method: 'texImage2D',
			args: [
				{ __canvasTypedArray: 'Uint8Array', base64: 'AH//AAAA//8=' },
				{ __canvasTypedArray: 'Uint8Array', rle32: 'AAAA//7//38A', byteLength: 16 },
				{ meta: { width: 4, height: 4, flags: [true, false, null], label: '中文 \u001e ok' } },
			],
			resultId: 'tex_1',
		}
		const [decoded] = decodeOpsCompact(encodeOpsCompact([operation]))
		expect(decoded).toEqual(operation)
		expect(decoded.args[2].meta.label).toBe('中文 \u001e ok')
	})

	it('preserves field order of encoded records', () => {
		const [decoded] = decodeOpsCompact(encodeOpsCompact([{ op: 'x', method: 'y', args: [] }]))
		expect(Object.keys(decoded)).toEqual(['op', 'method', 'args'])
	})
})

describe('canvas-ops-compact real batches', () => {
	it('round-trips a realistic 280-call uniform batch without loss', () => {
		const batch = Array.from({ length: 280 }, (_, i) => ({
			op: 'contextCall',
			contextId: 'ctx1',
			method: 'uniform3f',
			args: [1.5 + i, -2, 0],
			resultId: 'r1',
		}))
		const encoded = encodeOpsCompact(batch)
		expect(typeof encoded).toBe('string')
		expect(encoded.length).toBeGreaterThan(0)
		expect(encoded.length).toBeLessThan(JSON.stringify(batch).length)
		expect(decodeOpsCompact(encoded)).toEqual(batch)
	})

	it('round-trips a mixed batch of the real flush pipeline op shapes', () => {
		const batch = [
			{ op: 'setCanvasProperty', prop: 'width', value: 600 },
			{ op: 'getContext', contextId: 'ctx_1', contextType: 'webgl' },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'viewport', args: [0, 0, 300, 150] },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'createShader', args: [0x8B31], resultId: 'shader_1' },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'shaderSource', args: [{ __canvasResourceId: 'shader_1' }, 'void main() {}'] },
			{
				op: 'contextCall',
				contextId: 'ctx_1',
				method: 'uniform3f',
				args: [{ __canvasResourceId: 'loc_1' }, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY],
				resultId: 'r1',
			},
			{ op: 'contextSetProperty', contextId: 'ctx_1', prop: 'fillStyle', value: '#ff0000', feedback: 'state', sequence: 7 },
		]
		const decoded = decodeOpsCompact(encodeOpsCompact(batch))
		// 嵌套在对象/数组内的非有限数按 JSON 语义落 null——与旧 JSON 对象树路径完全一致，
		// 不是紧凑流引入的回归；字段级（顶层）NaN/Infinity 的真无损由数字用例覆盖。
		const expected = [
			{ op: 'setCanvasProperty', prop: 'width', value: 600 },
			{ op: 'getContext', contextId: 'ctx_1', contextType: 'webgl' },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'viewport', args: [0, 0, 300, 150] },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'createShader', args: [0x8B31], resultId: 'shader_1' },
			{ op: 'contextCall', contextId: 'ctx_1', method: 'shaderSource', args: [{ __canvasResourceId: 'shader_1' }, 'void main() {}'] },
			{
				op: 'contextCall',
				contextId: 'ctx_1',
				method: 'uniform3f',
				args: [{ __canvasResourceId: 'loc_1' }, null, null, null],
				resultId: 'r1',
			},
			{ op: 'contextSetProperty', contextId: 'ctx_1', prop: 'fillStyle', value: '#ff0000', feedback: 'state', sequence: 7 },
		]
		expect(decoded).toEqual(expected)
	})

	it('decodes empty payloads to an empty batch', () => {
		expect(decodeOpsCompact('')).toEqual([])
		expect(decodeOpsCompact(encodeOpsCompact([]))).toEqual([])
		expect(decodeOpsCompact(undefined)).toEqual([])
	})
})
