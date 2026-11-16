// canvas-ops-binary.js — GL 指令批次紧凑二进制编码（性能优化二期主靶）。
//
// 背景：每帧 canvasNodeFlush 携带数百条 operation 对象。历史路径把同一批数据
// 用 JSON 遍历 4 遍（JS stringify → JNI parse(JSONObject) → 原生 toString →
// WebView parse），真机上 send 段 p50≈22ms/帧。本模块把整批指令编码成一份
// 自描述二进制 buffer，搭容器已有的 __diminaCanvasBuffers 纯二进制旁路
// （ArrayBuffer 经 WebMessagePort 直达渲染层，全程不过 JSON）。
//
// 线格式 v1（全部小端）：
//   [0..3]  魔数 'D' 'O' 'P' 'S'
//   [4]     version = 1
//   [5]     flags = 0（保留；RLE 压缩由容器 encodeCanvasArrayBuffer 在外层完成）
//   [6..7]  reserved u16 = 0
//   [8..11] opCount u32
//   字符串表：count u16，随后每项 u32 byteLength + UTF-8 字节
//   记录 × opCount：fieldCount u16，随后每项：keyIndex u16 + 值
//
// 值 tag（1 字节）：
//   0x00 null / 0x01 true / 0x02 false
//   0x03 int32（zigzag LEB128 varint）
//   0x04 float64（8 字节）
//   0x05 NaN / 0x06 +Infinity / 0x07 -Infinity
//        （仅批次字段顶层保留；嵌套值按既有 JSON 语义落 null）
//   0x08 string（u16 字符串表索引）
//   0x09 object（u16 字段数；每项 keyIndex u16 + 值）
//   0x0A array（u32 长度 + 值序列）
//   0x0B float64 run（u32 长度 + 连续 8 字节 double，用于 16 元矩阵等）
//
// 字符串表：op 名 / 字段名 / method / contextId / resultId 高度重复，去重后
// 用 uint16 索引引用是体积收益的主要来源。单批唯一字符串超过 65535 时整批回退。
//
// 语义对齐：
// - 顶层字段 undefined 跳过（与 encodeOpsCompact / JSON.stringify 一致）；
// - 顶层字段的 NaN/±Infinity 用专用 tag 保留（与紧凑字符串路径一致）；
// - 嵌套对象/数组内数值按 JSON 语义：NaN/Infinity → null，-0 → 0；
// - 只接受 JSON 安全形状（普通对象/数组/字符串/数字/布尔/null）。遇到函数、
//   symbol、bigint、Date 等非普通对象、toJSON、孤立代理项字符时抛
//   OpsBinaryUnsupportedError，由调用方整批回退到紧凑字符串路径，永不破坏渲染。

import { base64ToArrayBuffer } from './binary'
import { decodeCanvasRle32 } from './canvas-compression'

const MAGIC = [0x44, 0x4F, 0x50, 0x53] // 'DOPS'
const VERSION = 1
const HEADER_BYTES = 12

const TAG_NULL = 0x00
const TAG_TRUE = 0x01
const TAG_FALSE = 0x02
const TAG_INT32 = 0x03
const TAG_FLOAT64 = 0x04
const TAG_NAN = 0x05
const TAG_INFINITY = 0x06
const TAG_NEG_INFINITY = 0x07
const TAG_STRING = 0x08
const TAG_OBJECT = 0x09
const TAG_ARRAY = 0x0A
const TAG_FLOAT64_RUN = 0x0B

const MAX_STRING_TABLE = 0xFFFF
const MAX_OBJECT_FIELDS = 0xFFFF
const MAX_DEPTH = 32
const INT32_MIN = -2147483648
const INT32_MAX = 2147483647

// 已知 op 白名单：service 侧 canvas-node 批次会出现的全部操作。新增 op 未同步到
// 这里时 encodeOpsBinary 会整批回退到紧凑字符串路径（安全默认：宁慢不错）。
export const OPS_BINARY_KNOWN_OPS = new Set([
	'setCanvasProperty',
	'getContext',
	'contextSetProperty',
	'contextCall',
	'contextQuery',
	'contextFeedback',
	'contextStateSnapshot',
	'resourceCall',
	'createImage',
	'imageSetSrc',
	'extensionCall',
	'getExtension',
	'getImageData',
	'toDataURL',
])

export const OPS_BINARY_VERSION = VERSION

export class OpsBinaryUnsupportedError extends Error {
	constructor(message) {
		super(message)
		this.name = 'OpsBinaryUnsupportedError'
	}
}

class ByteWriter {
	constructor() {
		this.bytes = new Uint8Array(1024)
		this.length = 0
		this.view = new DataView(this.bytes.buffer)
	}

	grow(required) {
		if (this.length + required <= this.bytes.length) return
		let capacity = this.bytes.length
		while (capacity < this.length + required) capacity *= 2
		const next = new Uint8Array(capacity)
		next.set(this.bytes.subarray(0, this.length))
		this.bytes = next
		this.view = new DataView(next.buffer)
	}

	u8(value) {
		this.grow(1)
		this.bytes[this.length++] = value & 0xFF
	}

	u16(value) {
		this.grow(2)
		this.view.setUint16(this.length, value & 0xFFFF, true)
		this.length += 2
	}

	u32(value) {
		this.grow(4)
		this.view.setUint32(this.length, value >>> 0, true)
		this.length += 4
	}

	f64(value) {
		this.grow(8)
		this.view.setFloat64(this.length, value, true)
		this.length += 8
	}

	raw(bytes) {
		this.grow(bytes.length)
		this.bytes.set(bytes, this.length)
		this.length += bytes.length
	}

	// zigzag + LEB128：int32 值域内的高频小整数只占 1-2 字节。
	varint(value) {
		let v = ((value << 1) ^ (value >> 31)) >>> 0
		while (v >= 0x80) {
			this.u8((v & 0x7F) | 0x80)
			v >>>= 7
		}
		this.u8(v)
	}

	finish() {
		return this.bytes.slice(0, this.length).buffer
	}
}

// 不依赖 TextEncoder：service 侧运行在 QuickJS 里，不能假设 Web 编解码器存在。
function utf8Size(text) {
	let size = 0
	for (let i = 0; i < text.length; i++) {
		const code = text.charCodeAt(i)
		if (code < 0x80) size += 1
		else if (code < 0x800) size += 2
		else if (code >= 0xD800 && code <= 0xDBFF) {
			const next = i + 1 < text.length ? text.charCodeAt(i + 1) : 0
			if (next < 0xDC00 || next > 0xDFFF) throw new OpsBinaryUnsupportedError('lone surrogate in string')
			size += 4
			i++
		}
		else if (code >= 0xDC00 && code <= 0xDFFF) {
			throw new OpsBinaryUnsupportedError('lone surrogate in string')
		}
		else size += 3
	}
	return size
}

function writeUtf8(writer, text) {
	const size = utf8Size(text)
	writer.u32(size)
	writer.grow(size)
	const out = writer.bytes
	let offset = writer.length
	for (let i = 0; i < text.length; i++) {
		let code = text.charCodeAt(i)
		if (code >= 0xD800 && code <= 0xDBFF) {
			const next = text.charCodeAt(++i)
			code = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00)
		}
		if (code < 0x80) {
			out[offset++] = code
		}
		else if (code < 0x800) {
			out[offset++] = 0xC0 | (code >> 6)
			out[offset++] = 0x80 | (code & 0x3F)
		}
		else if (code < 0x10000) {
			out[offset++] = 0xE0 | (code >> 12)
			out[offset++] = 0x80 | ((code >> 6) & 0x3F)
			out[offset++] = 0x80 | (code & 0x3F)
		}
		else {
			out[offset++] = 0xF0 | (code >> 18)
			out[offset++] = 0x80 | ((code >> 12) & 0x3F)
			out[offset++] = 0x80 | ((code >> 6) & 0x3F)
			out[offset++] = 0x80 | (code & 0x3F)
		}
	}
	writer.length = offset
}

function stringIndex(state, text) {
	const cached = state.strings.get(text)
	if (cached !== undefined) return cached
	if (state.table.length >= MAX_STRING_TABLE) throw new OpsBinaryUnsupportedError('string table overflow')
	const index = state.table.length
	state.strings.set(text, index)
	state.table.push(text)
	return index
}

function writeString(state, text) {
	state.writer.u8(TAG_STRING)
	state.writer.u16(stringIndex(state, text))
}

function isInt32(value) {
	return Number.isInteger(value) && value >= INT32_MIN && value <= INT32_MAX
}

function isPlainObject(value) {
	return Object.prototype.toString.call(value) === '[object Object]'
}

// 顶层字段数值：保留 NaN/±Infinity（与紧凑字符串路径一致），-0 归一成 0（JSON 语义）。
function writeTopLevelNumber(state, value) {
	const writer = state.writer
	if (Number.isNaN(value)) {
		writer.u8(TAG_NAN)
		return
	}
	if (value === Infinity) {
		writer.u8(TAG_INFINITY)
		return
	}
	if (value === -Infinity) {
		writer.u8(TAG_NEG_INFINITY)
		return
	}
	if (value === 0) {
		writer.u8(TAG_INT32)
		writer.varint(0)
		return
	}
	if (isInt32(value)) {
		writer.u8(TAG_INT32)
		writer.varint(value)
		return
	}
	writer.u8(TAG_FLOAT64)
	writer.f64(value)
}

// 嵌套数值：JSON.stringify 语义（非有限数 → null，-0 → 0）。
function writeNestedNumber(state, value) {
	const writer = state.writer
	if (!Number.isFinite(value)) {
		writer.u8(TAG_NULL)
		return
	}
	if (value === 0) {
		writer.u8(TAG_INT32)
		writer.varint(0)
		return
	}
	if (isInt32(value)) {
		writer.u8(TAG_INT32)
		writer.varint(value)
		return
	}
	writer.u8(TAG_FLOAT64)
	writer.f64(value)
}

function writeArray(state, value, depth) {
	const writer = state.writer
	const length = value.length
	if (length >= 8) {
		let allNumbers = true
		let allInt32 = true
		for (let i = 0; i < length; i++) {
			const item = value[i]
			if (typeof item !== 'number' || !Number.isFinite(item)) {
				allNumbers = false
				break
			}
			if (!isInt32(item)) allInt32 = false
		}
		// 矩阵类连续 double（如 uniformMatrix4fv 的 16 元）跳过逐元素 tag。
		if (allNumbers && !allInt32) {
			writer.u8(TAG_FLOAT64_RUN)
			writer.u32(length)
			for (let i = 0; i < length; i++) writer.f64(value[i] === 0 ? 0 : value[i])
			return
		}
	}
	writer.u8(TAG_ARRAY)
	writer.u32(length)
	for (let i = 0; i < length; i++) {
		writeNestedValue(state, value[i] === undefined ? null : value[i], depth + 1)
	}
}

function writeObject(state, value, depth) {
	const writer = state.writer
	if (!isPlainObject(value)) throw new OpsBinaryUnsupportedError('non-plain object in operation value')
	if (typeof value.toJSON === 'function') throw new OpsBinaryUnsupportedError('toJSON is not supported')
	const keys = Object.keys(value)
	let count = 0
	const pending = []
	for (let i = 0; i < keys.length; i++) {
		const key = keys[i]
		const item = value[key]
		if (item === undefined) continue
		const type = typeof item
		if (type === 'function' || type === 'symbol' || type === 'bigint') {
			throw new OpsBinaryUnsupportedError(`unsupported ${type} value in object field`)
		}
		pending.push(key, item)
		count++
	}
	if (count > MAX_OBJECT_FIELDS) throw new OpsBinaryUnsupportedError('too many object fields')
	writer.u8(TAG_OBJECT)
	writer.u16(count)
	for (let i = 0; i < pending.length; i += 2) {
		writer.u16(stringIndex(state, pending[i]))
		writeNestedValue(state, pending[i + 1], depth + 1)
	}
}

function writeNestedValue(state, value, depth) {
	if (depth > MAX_DEPTH) throw new OpsBinaryUnsupportedError('operation value is too deep')
	if (value === null) {
		state.writer.u8(TAG_NULL)
		return
	}
	const type = typeof value
	if (type === 'boolean') {
		state.writer.u8(value ? TAG_TRUE : TAG_FALSE)
		return
	}
	if (type === 'number') {
		writeNestedNumber(state, value)
		return
	}
	if (type === 'string') {
		writeString(state, value)
		return
	}
	if (type === 'function' || type === 'symbol' || type === 'bigint') {
		throw new OpsBinaryUnsupportedError(`unsupported ${type} value in operation`)
	}
	if (Array.isArray(value)) {
		writeArray(state, value, depth)
		return
	}
	if (type === 'object') {
		writeObject(state, value, depth)
		return
	}
	throw new OpsBinaryUnsupportedError(`unsupported ${type} value in operation`)
}

function writeOperation(state, operation) {
	const writer = state.writer
	if (!isPlainObject(operation)) throw new OpsBinaryUnsupportedError('operation is not a plain object')
	if (typeof operation.op !== 'string' || !OPS_BINARY_KNOWN_OPS.has(operation.op)) {
		throw new OpsBinaryUnsupportedError('unknown canvas operation')
	}
	const keys = Object.keys(operation)
	let count = 0
	const pending = []
	for (let i = 0; i < keys.length; i++) {
		const key = keys[i]
		const value = operation[key]
		if (value === undefined) continue // 与紧凑字符串路径一致：undefined 字段不落线
		const type = typeof value
		if (type === 'function' || type === 'symbol' || type === 'bigint') {
			throw new OpsBinaryUnsupportedError(`unsupported ${type} operation field`)
		}
		pending.push(key, value)
		count++
	}
	if (count > MAX_OBJECT_FIELDS) throw new OpsBinaryUnsupportedError('too many operation fields')
	writer.u16(count)
	for (let i = 0; i < pending.length; i += 2) {
		writer.u16(stringIndex(state, pending[i]))
		const value = pending[i + 1]
		if (typeof value === 'number') writeTopLevelNumber(state, value)
		else if (typeof value === 'string') writeString(state, value)
		else if (typeof value === 'boolean') writer.u8(value ? TAG_TRUE : TAG_FALSE)
		else if (value === null) writer.u8(TAG_NULL)
		else writeNestedValue(state, value, 1)
	}
}

/**
 * 把一批 canvas operation 编码成自描述二进制。
 * @param {Array<object>} operations
 * @returns {{ buffer: ArrayBuffer, count: number }} buffer 为精确大小的 ArrayBuffer
 * @throws {OpsBinaryUnsupportedError} 批次内含无法无损编码的形状（调用方整批回退）
 */
export function encodeOpsBinary(operations) {
	if (!Array.isArray(operations)) throw new OpsBinaryUnsupportedError('operations must be an array')
	const state = { writer: new ByteWriter(), strings: new Map(), table: [] }
	for (let i = 0; i < operations.length; i++) writeOperation(state, operations[i])
	const tableWriter = new ByteWriter()
	tableWriter.u16(state.table.length)
	for (let i = 0; i < state.table.length; i++) writeUtf8(tableWriter, state.table[i])
	const header = new ByteWriter()
	header.raw(Uint8Array.from(MAGIC))
	header.u8(VERSION)
	header.u8(0)
	header.u16(0)
	header.u32(operations.length)
	const total = header.length + tableWriter.length + state.writer.length
	const buffer = new Uint8Array(total)
	buffer.set(header.bytes.subarray(0, header.length), 0)
	buffer.set(tableWriter.bytes.subarray(0, tableWriter.length), header.length)
	buffer.set(state.writer.bytes.subarray(0, state.writer.length), header.length + tableWriter.length)
	return { buffer: buffer.buffer, count: operations.length }
}

class ByteReader {
	constructor(bytes) {
		this.bytes = bytes
		this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
		this.offset = 0
	}

	need(size) {
		if (size < 0 || this.offset + size > this.bytes.byteLength) throw new Error('truncated canvas ops binary')
	}

	u8() {
		this.need(1)
		return this.bytes[this.offset++]
	}

	u16() {
		this.need(2)
		const value = this.view.getUint16(this.offset, true)
		this.offset += 2
		return value
	}

	u32() {
		this.need(4)
		const value = this.view.getUint32(this.offset, true)
		this.offset += 4
		return value
	}

	f64() {
		this.need(8)
		const value = this.view.getFloat64(this.offset, true)
		this.offset += 8
		return value
	}

	varint() {
		let value = 0
		let shift = 0
		for (;;) {
			const byte = this.u8()
			value |= (byte & 0x7F) << shift
			if ((byte & 0x80) === 0) break
			shift += 7
			if (shift > 28) throw new Error('invalid canvas ops varint')
		}
		return (value >>> 1) ^ -(value & 1)
	}

	utf8(size) {
		if (size < 0 || this.offset + size > this.bytes.byteLength) throw new Error('truncated canvas ops string')
		const bytes = this.bytes
		const end = this.offset + size
		let out = ''
		while (this.offset < end) {
			const first = bytes[this.offset++]
			let code
			if (first < 0x80) {
				code = first
			}
			else if ((first & 0xE0) === 0xC0) {
				if (this.offset >= end) throw new Error('truncated canvas ops utf8')
				const b = bytes[this.offset++]
				if ((b & 0xC0) !== 0x80) throw new Error('invalid canvas ops utf8')
				code = ((first & 0x1F) << 6) | (b & 0x3F)
			}
			else if ((first & 0xF0) === 0xE0) {
				if (this.offset + 1 >= end) throw new Error('truncated canvas ops utf8')
				const b = bytes[this.offset++]
				const c = bytes[this.offset++]
				if ((b & 0xC0) !== 0x80 || (c & 0xC0) !== 0x80) throw new Error('invalid canvas ops utf8')
				code = ((first & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F)
			}
			else if ((first & 0xF8) === 0xF0) {
				if (this.offset + 2 >= end) throw new Error('truncated canvas ops utf8')
				const b = bytes[this.offset++]
				const c = bytes[this.offset++]
				const d = bytes[this.offset++]
				if ((b & 0xC0) !== 0x80 || (c & 0xC0) !== 0x80 || (d & 0xC0) !== 0x80) {
					throw new Error('invalid canvas ops utf8')
				}
				code = ((first & 0x07) << 18) | ((b & 0x3F) << 12) | ((c & 0x3F) << 6) | (d & 0x3F)
			}
			else {
				throw new Error('invalid canvas ops utf8')
			}
			if (code < 0x10000) {
				out += String.fromCharCode(code)
			}
			else {
				code -= 0x10000
				out += String.fromCharCode(0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF))
			}
		}
		return out
	}
}

function readNestedValue(reader, strings, depth) {
	if (depth > MAX_DEPTH) throw new Error('canvas ops value is too deep')
	const tag = reader.u8()
	switch (tag) {
		case TAG_NULL:
			return null
		case TAG_TRUE:
			return true
		case TAG_FALSE:
			return false
		case TAG_INT32:
			return reader.varint()
		case TAG_FLOAT64:
			return reader.f64()
		case TAG_NAN:
			return Number.NaN
		case TAG_INFINITY:
			return Infinity
		case TAG_NEG_INFINITY:
			return -Infinity
		case TAG_STRING: {
			const index = reader.u16()
			if (index >= strings.length) throw new Error('invalid canvas ops string index')
			return strings[index]
		}
		case TAG_ARRAY: {
			const length = reader.u32()
			if (length > reader.bytes.byteLength) throw new Error('invalid canvas ops array length')
			const out = []
			for (let i = 0; i < length; i++) out[i] = readNestedValue(reader, strings, depth + 1)
			return out
		}
		case TAG_FLOAT64_RUN: {
			const length = reader.u32()
			if (length > reader.bytes.byteLength / 8) throw new Error('invalid canvas ops float run length')
			const out = []
			for (let i = 0; i < length; i++) out[i] = reader.f64()
			return out
		}
		case TAG_OBJECT: {
			const count = reader.u16()
			const out = {}
			for (let i = 0; i < count; i++) {
				const keyIndex = reader.u16()
				if (keyIndex >= strings.length) throw new Error('invalid canvas ops key index')
				out[strings[keyIndex]] = readNestedValue(reader, strings, depth + 1)
			}
			return out
		}
		default:
			throw new Error(`unknown canvas ops value tag ${tag}`)
	}
}

/**
 * 解码二进制批次。
 * @param {ArrayBuffer|Uint8Array} buffer
 * @returns {Array<object>} 还原出的 canvas operation 数组
 */
export function decodeOpsBinary(buffer) {
	let bytes
	if (ArrayBuffer.isView(buffer)) {
		bytes = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
	}
	else if (Object.prototype.toString.call(buffer) === '[object ArrayBuffer]') {
		bytes = new Uint8Array(buffer)
	}
	else {
		throw new TypeError('canvas ops binary input must be an ArrayBuffer or view')
	}
	if (bytes.byteLength < HEADER_BYTES) throw new Error('truncated canvas ops binary header')
	for (let i = 0; i < MAGIC.length; i++) {
		if (bytes[i] !== MAGIC[i]) throw new Error('invalid canvas ops binary magic')
	}
	if (bytes[4] !== VERSION) throw new Error(`unsupported canvas ops binary version ${bytes[4]}`)
	const reader = new ByteReader(bytes)
	reader.offset = 8
	const count = reader.u32()
	const tableLength = reader.u16()
	const strings = []
	for (let i = 0; i < tableLength; i++) {
		const size = reader.u32()
		strings[i] = reader.utf8(size)
	}
	const operations = []
	for (let i = 0; i < count; i++) {
		const fieldCount = reader.u16()
		const operation = {}
		for (let k = 0; k < fieldCount; k++) {
			const keyIndex = reader.u16()
			if (keyIndex >= strings.length) throw new Error('invalid canvas ops field key index')
			operation[strings[keyIndex]] = readNestedValue(reader, strings, 1)
		}
		operations[i] = operation
	}
	if (reader.offset !== bytes.byteLength) throw new Error('trailing canvas ops binary bytes')
	return operations
}

/**
 * 解码渲染层收到的二进制信封（容器 __diminaCanvasBuffers 还原后的形态）：
 * - 原生二进制就绪时：{ __canvasTypedArray: 'Uint8Array', packed: <Uint8Array> }
 *   若原生做了 rle32 压缩：{ __canvasTypedArray, encoding: 'rle32-v1', byteLength, packed }
 * - 原生二进制不可用时（兼容降级）：{ __canvasTypedArray, base64: '...' }
 * @param {object} envelope
 * @returns {Array<object>}
 */
export function decodeOpsBinaryEnvelope(envelope) {
	if (!envelope || typeof envelope !== 'object') throw new TypeError('invalid canvas ops binary envelope')
	if (envelope.encoding === 'rle32-v1') {
		if (!envelope.packed) throw new TypeError('invalid rle32 canvas ops envelope')
		return decodeOpsBinary(decodeCanvasRle32(envelope.packed, envelope.byteLength))
	}
	if (envelope.packed) return decodeOpsBinary(envelope.packed)
	if (typeof envelope.base64 === 'string') {
		return decodeOpsBinary(new Uint8Array(base64ToArrayBuffer(envelope.base64)))
	}
	throw new TypeError('invalid canvas ops binary envelope payload')
}
