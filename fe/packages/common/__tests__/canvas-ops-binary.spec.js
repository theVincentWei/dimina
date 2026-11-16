import { describe, expect, it } from 'vitest'
import {
	decodeOpsBinary,
	decodeOpsBinaryEnvelope,
	encodeOpsBinary,
	OpsBinaryUnsupportedError,
} from '../src/core/canvas-ops-binary'
import { decodeOpsCompact as decodeOpsCompactLocal, encodeOpsCompact as encodeOpsCompactLocal } from '../src/core/canvas-ops-compact'

function roundTrip(operations) {
	return decodeOpsBinary(encodeOpsBinary(operations).buffer)
}

function equivalentOperations(operations) {
	// A/B 等价性：二进制解码结果必须与紧凑字符串路径的解码结果 deep-equal。
	return {
		binary: roundTrip(operations),
		compact: decodeOpsCompactLocal(encodeOpsCompactLocal(operations)),
	}
}

function uniformBatch(count = 280) {
	const operations = []
	for (let i = 0; i < count; i++) {
		operations.push({
			op: 'contextCall',
			contextId: 'ctx1',
			method: 'uniform3f',
			args: [1.5, -2, 0],
			resultId: `r${i}`,
		})
	}
	return operations
}

describe('canvas-ops-binary wire format', () => {
	it('writes the documented header: magic, version, flags, reserved and op count', () => {
		const { buffer, count } = encodeOpsBinary(uniformBatch(3))
		expect(count).toBe(3)
		const view = new DataView(buffer)
		const magic = String.fromCharCode(...new Uint8Array(buffer, 0, 4))
		expect(magic).toBe('DOPS')
		expect(view.getUint8(4)).toBe(1)
		expect(view.getUint8(5)).toBe(0)
		expect(view.getUint16(6, true)).toBe(0)
		expect(view.getUint32(8, true)).toBe(3)
	})

	it('round-trips an empty batch', () => {
		const { buffer, count } = encodeOpsBinary([])
		expect(count).toBe(0)
		expect(decodeOpsBinary(buffer)).toEqual([])
	})

	it('round-trips real batches across every known operation shape', () => {
		const operations = [
			{ op: 'setCanvasProperty', prop: 'width', value: 600 },
			{ op: 'getContext', contextId: 'ctx_gl', contextType: 'webgl', attributes: { alpha: true, depth: false } },
			{ op: 'contextSetProperty', contextId: 'ctx_gl', prop: 'lineWidth', value: 2 },
			{
				op: 'contextCall',
				contextId: 'ctx_gl',
				method: 'uniformMatrix4fv',
				args: [
					{ __canvasResourceId: 'loc_m' },
					false,
					[1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5, 0.25, -0.125, 1],
				],
			},
			{
				op: 'contextCall',
				contextId: 'ctx_gl',
				method: 'vertexAttribPointer',
				args: [0, 3, 5126, false, 12, { __canvasResourceId: 'buf_1' }],
			},
			{ op: 'contextQuery', contextId: 'ctx_gl', queries: [{ kind: 'state', prop: 'lineWidth', sequence: 1 }] },
			{ op: 'contextFeedback', contextId: 'ctx_gl' },
			{ op: 'contextStateSnapshot', contextId: 'ctx_gl', stateSequences: { lineWidth: 1, blend: 2 } },
			{ op: 'resourceCall', resourceId: 'res_1', method: 'update', args: ['x', { nested: [1, 2, 3] }] },
			{ op: 'createImage', imageId: 'img_1' },
			{ op: 'imageSetSrc', imageId: 'img_1', src: 'https://example.test/a.png', callback: 'cb_1' },
			{ op: 'extensionCall', contextId: 'ctx_gl', extension: 'EXT_test', method: 'foo', args: [1] },
			{ op: 'getExtension', contextId: 'ctx_gl', extension: 'EXT_test', resultId: 'ext_1' },
			{ op: 'getImageData', contextId: 'ctx_2d', x: 0, y: 0, width: 4, height: 4, resultId: 'img_2' },
			{ op: 'toDataURL', contextId: 'ctx_2d', type: 'image/png', encoderOptions: 0.8, resultId: 'url_1' },
		]
		expect(roundTrip(operations)).toEqual(operations)
	})

	it('is A/B equivalent with the compact wire format for real batches', () => {
		const operations = [
			{ op: 'getContext', contextId: 'ctx1', contextType: 'webgl' },
			...uniformBatch(40),
			{ op: 'contextCall', contextId: 'ctx1', method: 'viewport', args: [0, 0, 300, 150] },
			{ op: 'contextCall', contextId: 'ctx1', method: 'blendFunc', args: [1, 771], resultId: 'r_blend' },
		]
		const { binary, compact } = equivalentOperations(operations)
		expect(binary).toEqual(compact)
		expect(decodeOpsCompactLocal(encodeOpsCompactLocal(operations))).toEqual(binary)
	})

	it('matches compact number semantics at the top level: NaN/±Infinity kept, -0 becomes 0, 1e21 exact', () => {
		const values = [0, -1, -273.15, 0.1, 1e21, 1e-7, Number.MAX_SAFE_INTEGER, -0]
		for (const value of values) {
			const { binary, compact } = equivalentOperations([{ op: 'setCanvasProperty', prop: 'x', value }])
			expect(binary[0].value).toBe(compact[0].value)
		}
		const special = equivalentOperations([{ op: 'setCanvasProperty', prop: 'x', value: Number.NaN }])
		expect(Number.isNaN(special.binary[0].value)).toBe(true)
		expect(Number.isNaN(special.compact[0].value)).toBe(true)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: Infinity }])[0].value).toBe(Infinity)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: -Infinity }])[0].value).toBe(-Infinity)
		expect(Object.is(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: -0 }])[0].value, 0)).toBe(true)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: 1e21 }])[0].value).toBe(1e21)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: 2147483647 }])[0].value).toBe(2147483647)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: -2147483648 }])[0].value).toBe(-2147483648)
		expect(roundTrip([{ op: 'setCanvasProperty', prop: 'x', value: 2147483648 }])[0].value).toBe(2147483648)
	})

	it('round-trips strings losslessly, including Chinese, quotes, control characters and separator lookalikes', () => {
		const samples = [
			'',
			'中文上下文与 emoji 🎨',
			'a\u001Eb\u001Fc',
			'"quotes" and \\backslash\\',
			'!t looks boolean',
			'_N _I _J',
			'line\nbreak\ttab',
			'  spaced  ',
		]
		for (const src of samples) {
			expect(roundTrip([{ op: 'imageSetSrc', imageId: 'img', src }])[0].src).toBe(src)
		}
		// 孤立代理项无法用 UTF-8 无损表达 → 抛不支持，交给整批回退。
		expect(() => encodeOpsBinary([{ op: 'imageSetSrc', imageId: 'img', src: '\uD800' }]))
			.toThrow(OpsBinaryUnsupportedError)
	})

	it('drops undefined operation fields like JSON and the compact path', () => {
		const decoded = roundTrip([{ op: 'setCanvasProperty', prop: 'width', value: 1, extra: undefined }])
		expect(decoded[0]).toEqual({ op: 'setCanvasProperty', prop: 'width', value: 1 })
		expect('extra' in decoded[0]).toBe(false)
	})

	it('keeps nested JSON semantics: NaN/Infinity become null, -0 becomes 0, undefined array items become null', () => {
		const operations = [{
			op: 'contextCall',
			contextId: 'ctx',
			method: 'uniform1fv',
			args: [Number.NaN, Infinity, -Infinity, -0, [Number.NaN, undefined, 1.5]],
		}]
		const { binary, compact } = equivalentOperations(operations)
		expect(binary).toEqual(compact)
		expect(binary[0].args).toEqual([null, null, null, 0, [null, null, 1.5]])
	})

	it('round-trips nested objects/arrays and GL typed-array envelopes', () => {
		const operations = [
			{
				op: 'contextCall',
				contextId: 'ctx_tex',
				method: 'texImage2D',
				args: [
					3553, 0, 6408, 2, 2, 0, 6408, 5121,
					{ __canvasTypedArray: 'Uint8Array', data: [1, 2, 3, 4] },
				],
			},
			{
				op: 'contextCall',
				contextId: 'ctx_tex',
				method: 'texImage2D',
				args: [3553, 0, 6408, 4, 4, 0, 6408, 5121, { __canvasTypedArray: 'Uint8Array', encoding: 'rle32-v1', byteLength: 64, packed: { __canvasBinaryIndex: 0 } }],
			},
		]
		expect(roundTrip(operations)).toEqual(operations)
	})

	it('deduplicates the string table so repeated op names/methods cost one entry', () => {
		const { buffer } = encodeOpsBinary(uniformBatch(280))
		const view = new Uint8Array(buffer)
		const text = String.fromCharCode(...view.subarray(0, Math.min(view.length, 4096)))
		// 高频字符串只应出现一次（其余场景靠 uint16 索引引用）。
		expect(text.split('uniform3f').length - 1).toBe(1)
		expect(text.split('contextCall').length - 1).toBe(1)
	})

	it('is significantly smaller than the compact string path for real 280-op batches', () => {
		const operations = uniformBatch(280)
		const binary = encodeOpsBinary(operations).buffer.byteLength
		const compact = encodeOpsCompactLocal(operations).length
		// 记录实测比值，供格式化说明引用（见交付报告）。
		console.log(`[rperf] ops 280x uniform3f: binary=${binary}B compact=${compact}B ratio=${(binary / compact).toFixed(3)}`)
		expect(binary).toBeLessThan(compact * 0.6)
	})

	it('rejects unknown operations so the caller can fall back for the whole batch', () => {
		expect(() => encodeOpsBinary([{ op: 'brandNewOperation', foo: 1 }])).toThrow(OpsBinaryUnsupportedError)
		expect(() => encodeOpsBinary([{ op: 'contextCall', contextId: 'c', method: 'uniform3f', args: [1], fn: () => {} }]))
			.toThrow(OpsBinaryUnsupportedError)
		expect(() => encodeOpsBinary([{ op: 'contextCall', contextId: 'c', method: 'uniform3f', args: [() => {}] }]))
			.toThrow(OpsBinaryUnsupportedError)
		expect(() => encodeOpsBinary([{ op: 'contextCall', contextId: 'c', method: 'x', args: [new Date()] }]))
			.toThrow(OpsBinaryUnsupportedError)
		expect(() => encodeOpsBinary('not-an-array')).toThrow(OpsBinaryUnsupportedError)
	})

	it('decodes from ArrayBuffer views and rejects corrupted input', () => {
		const { buffer } = encodeOpsBinary(uniformBatch(2))
		const view = new Uint8Array(buffer)
		expect(decodeOpsBinary(view)).toHaveLength(2)
		expect(() => decodeOpsBinary('nope')).toThrow(TypeError)
		expect(() => decodeOpsBinary(new Uint8Array(4))).toThrow('truncated canvas ops binary header')
		const badMagic = new Uint8Array(buffer.slice(0))
		badMagic[0] = 0x00
		expect(() => decodeOpsBinary(badMagic)).toThrow('invalid canvas ops binary magic')
		const badVersion = new Uint8Array(buffer.slice(0))
		badVersion[4] = 9
		expect(() => decodeOpsBinary(badVersion)).toThrow('unsupported canvas ops binary version')
		const truncated = new Uint8Array(buffer.slice(0, buffer.byteLength - 4))
		expect(() => decodeOpsBinary(truncated)).toThrow()
	})

	it('decodes the native transport envelopes: packed view, base64 fallback and rle32', () => {
		const operations = uniformBatch(9)
		const { buffer } = encodeOpsBinary(operations)
		// 原生二进制就绪：packed 为 WebMessagePort 还原出来的 Uint8Array。
		expect(decodeOpsBinaryEnvelope({ __canvasTypedArray: 'Uint8Array', packed: new Uint8Array(buffer) }))
			.toEqual(operations)
		// 原生二进制不可用：base64 兼容降级。
		const bytes = new Uint8Array(buffer)
		let binary = ''
		for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
		const base64 = btoa(binary)
		expect(decodeOpsBinaryEnvelope({ __canvasTypedArray: 'Uint8Array', base64 })).toEqual(operations)
		// rle32 压缩信封：构造一批编码长度 %4 === 0 的批次，再用字面量 token 手工压缩。
		let payload = null
		for (let i = 0; i < 64 && !payload; i++) {
			const candidate = encodeOpsBinary(uniformBatch(9 + i)).buffer
			if (candidate.byteLength % 4 === 0) payload = candidate
		}
		expect(payload).toBeTruthy()
		const words = payload.byteLength / 4
		const packed = new Uint8Array(4 + payload.byteLength)
		new DataView(packed.buffer).setUint32(0, words, true) // 高位 0 = 字面量 token
		packed.set(new Uint8Array(payload), 4)
		expect(decodeOpsBinaryEnvelope({
			__canvasTypedArray: 'Uint8Array',
			encoding: 'rle32-v1',
			byteLength: payload.byteLength,
			packed,
		})).toEqual(decodeOpsBinary(payload))
		expect(() => decodeOpsBinaryEnvelope(null)).toThrow(TypeError)
		expect(() => decodeOpsBinaryEnvelope({ __canvasTypedArray: 'Uint8Array' })).toThrow(TypeError)
	})
})
