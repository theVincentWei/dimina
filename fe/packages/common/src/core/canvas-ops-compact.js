// canvas-ops-compact.js — GL 指令批次紧凑流编码（性能优化二期主靶）。
//
// 背景：每帧 canvasNodeFlush 携带数百条 operation 对象，历史上以 JSON 对象树
// 过 QuickJS→容器→WebView 三层（每条重复字段名 + 两端 stringify/parse），
// 真机实测 send 段 p50≈18ms/帧。本模块把批次压成「字段流」字符串：
//   - 条间 \u001e，字段名与值之间 \u001f，字段名裸放（均为安全标识符）
//   - 值带类型前缀：数字裸放（NaN/Infinity 用 _N/_I/_J），布尔 !t/!f，
//     null !z，字符串 !"…（JSON 转义形式），对象/数组 !j…（JSON 子串）
//   - undefined 字段跳过（与 JSON 语义一致）
// JSON.stringify 对控制字符的转义保证字符串值内不会出现裸分隔符。

const SEP_RECORD = '\u001e'
const SEP_FIELD = '\u001f'

function encodeValue(v) {
	const t = typeof v
	if (t === 'number') {
		if (Number.isNaN(v)) return '_N'
		if (v === Infinity) return '_I'
		if (v === -Infinity) return '_J'
		return String(v)
	}
	if (t === 'boolean') return v ? '!t' : '!f'
	if (v === null) return '!z'
	if (t === 'string') return '!"' + JSON.stringify(v)
	return '!j' + JSON.stringify(v)
}

function decodeValue(s) {
	const c = s.charAt(0)
	if (c === '!') {
		const tag = s.charAt(1)
		if (tag === 't') return true
		if (tag === 'f') return false
		if (tag === 'z') return null
		if (tag === '"') return JSON.parse(s.slice(2))
		return JSON.parse(s.slice(2))
	}
	if (c === '_') {
		if (s === '_N') return NaN
		if (s === '_I') return Infinity
		if (s === '_J') return -Infinity
	}
	const n = +s
	return String(n) === s ? n : s
}

export function encodeOpsCompact(operations) {
	if (!Array.isArray(operations) || operations.length === 0) return ''
	const parts = []
	for (let i = 0; i < operations.length; i++) {
		const op = operations[i]
		if (i > 0) parts.push(SEP_RECORD)
		let first = true
		for (const key in op) {
			const v = op[key]
			if (v === undefined) continue
			if (!first) parts.push(SEP_FIELD)
			first = false
			parts.push(key)
			parts.push(SEP_FIELD)
			parts.push(encodeValue(v))
		}
	}
	return parts.join('')
}

export function decodeOpsCompact(text) {
	if (!text) return []
	const records = text.split(SEP_RECORD)
	const out = new Array(records.length)
	for (let i = 0; i < records.length; i++) {
		const fields = records[i].split(SEP_FIELD)
		const o = {}
		for (let k = 0; k + 1 < fields.length; k += 2) {
			o[fields[k]] = decodeValue(fields[k + 1])
		}
		out[i] = o
	}
	return out
}
