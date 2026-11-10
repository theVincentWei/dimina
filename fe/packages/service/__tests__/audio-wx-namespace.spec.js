/**
 * 音频接口的 wx 命名空间契约（src/api/index.js）：
 *   - native 注册表会出现 InnerAudio.* / Recorder.* 的 dotted wire 名，但微信公开
 *     API 只有 createInnerAudioContext / getRecorderManager 两个入口，wire 名必须
 *     被 blockedPublicApiNames 拦下，不能以非微信 API 形态挂到 wx 上。
 *   - 两个入口本身是 core 目录各 media 模块的真实实现，经 import.meta.glob 自动
 *     挂载，必须保持可枚举（供 Object.keys(wx) 建表的框架识别）。
 */
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/common', () => ({
	invokeAPI: vi.fn(() => 'mocked-result'),
	invokeAPIWithoutPromise: vi.fn(() => 'mocked-result'),
}))

vi.mock('@dimina/common', () => ({
	callback: { store: vi.fn(fn => fn) },
	isFunction: vi.fn(v => typeof v === 'function'),
	isWebWorker: false,
	parsePath: vi.fn(p => p),
	suffixPixel: vi.fn(v => v),
	uuid: vi.fn(() => 'test-uuid'),
	modDefine: vi.fn(),
	modRequire: vi.fn(),
}))

import globalApi, { registerEnumerableApiNames } from '../src/api/index.js'

// 与契约「线协议速查」及 native AudioApi/RecorderApi 的 apiNames 一一对应。
const AUDIO_WIRE_NAMES = [
	'InnerAudio.setSrc',
	'InnerAudio.play',
	'InnerAudio.pause',
	'InnerAudio.stop',
	'InnerAudio.seek',
	'InnerAudio.setProperty',
	'InnerAudio.getState',
	'InnerAudio.on',
	'InnerAudio.off',
	'InnerAudio.destroy',
	'Recorder.start',
	'Recorder.pause',
	'Recorder.resume',
	'Recorder.stop',
	'Recorder.onStart',
	'Recorder.onPause',
	'Recorder.onStop',
	'Recorder.onError',
	'Recorder.onInterruptionBegin',
	'Recorder.onInterruptionEnd',
	'Recorder.offStart',
	'Recorder.offPause',
	'Recorder.offStop',
	'Recorder.offError',
	'Recorder.offInterruptionBegin',
	'Recorder.offInterruptionEnd',
]

beforeAll(() => {
	// 模拟 native 注册表把 wire 名原样送进登记入口。
	registerEnumerableApiNames([...AUDIO_WIRE_NAMES])
})

afterAll(() => {
	vi.restoreAllMocks()
})

describe('音频 wire 名的 wx 命名空间契约', () => {
	it('native 注册表里的 InnerAudio.*/Recorder.* wire 名不能挂到 wx 上', () => {
		for (const name of AUDIO_WIRE_NAMES) {
			expect(globalApi[name]).toBeUndefined()
			expect(name in globalApi).toBe(false)
			expect(Object.keys(globalApi)).not.toContain(name)
		}
	})

	it('createInnerAudioContext/getRecorderManager 作为 core 实现保持可枚举', () => {
		expect(typeof globalApi.createInnerAudioContext).toBe('function')
		expect(typeof globalApi.getRecorderManager).toBe('function')
		expect(Object.keys(globalApi)).toContain('createInnerAudioContext')
		expect(Object.keys(globalApi)).toContain('getRecorderManager')
	})
})
