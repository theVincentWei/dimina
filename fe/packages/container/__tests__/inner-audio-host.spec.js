// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { registerInnerAudioApi, hostAudioInstanceForTest, resetHostAudioInstancesForTest } from '../src/innerAudioHost'

class FakeAudio {
	constructor() {
		this.handlers = new Map()
		this.src = ''
		this.currentTime = 0
		this.duration = NaN
		this.paused = true
		this.loop = false
		this.volume = 1
		this.playbackRate = 1
		this.autoplay = false
		this.preload = ''
		this.error = null
		this.loadCalls = 0
		this.playCalls = 0
	}

	addEventListener(type, fn) {
		if (!this.handlers.has(type)) this.handlers.set(type, new Set())
		this.handlers.get(type).add(fn)
	}

	removeEventListener(type, fn) {
		this.handlers.get(type)?.delete(fn)
	}

	dispatch(type) {
		for (const fn of [...(this.handlers.get(type) ?? [])]) fn({ type })
	}

	load() {
		this.loadCalls += 1
	}

	play() {
		this.playCalls += 1
		this.paused = false
		return Promise.resolve()
	}

	pause() {
		this.paused = true
	}

	removeAttribute(name) {
		if (name === 'src') this.src = ''
	}
}

function makeHarness() {
	const registered = new Map()
	const container = { registerApi: (name, handler) => registered.set(name, handler) }
	registerInnerAudioApi(container)
	const fired = []
	const app = {
		appInfo: { appId: 'wxtest', resourceBaseUrl: '/' },
		getResourceBaseUrl: () => '/',
		createCallbackFunction: id => payload => fired.push([id, payload]),
	}
	const call = (name, params = {}) => registered.get(name)?.call(app, params)
	return { call, fired, app }
}

describe('inner audio host', () => {
	beforeEach(() => {
		vi.stubGlobal('Audio', FakeAudio)
		resetHostAudioInstancesForTest()
	})

	afterEach(() => {
		vi.unstubAllGlobals()
	})

	it('setSrc 相对路径补 appId/main 前缀并回执 success', () => {
		const { call, fired } = makeHarness()
		call('InnerAudio.setSrc', { instanceId: 'a1', src: 'sfx/jump.wav', success: 'ok1', fail: 'err1' })
		const rec = hostAudioInstanceForTest('a1')
		expect(rec.audio.src).toBe('http://localhost:3000/wxtest/main/sfx/jump.wav')
		expect(rec.audio.loadCalls).toBe(1)
		expect(fired).toEqual([['ok1', undefined]])
	})

	it('setSrc 的 data:/http: 地址原样使用', () => {
		const { call } = makeHarness()
		call('InnerAudio.setSrc', { instanceId: 'a2', src: 'data:audio/wav;base64,AAAA' })
		call('InnerAudio.setSrc', { instanceId: 'a3', src: 'https://example.com/a.mp3' })
		expect(hostAudioInstanceForTest('a2').audio.src).toBe('data:audio/wav;base64,AAAA')
		expect(hostAudioInstanceForTest('a3').audio.src).toBe('https://example.com/a.mp3')
	})

	it('play 回执 success 并把 DOM play 事件点火到常驻回调 id', async () => {
		const { call, fired } = makeHarness()
		call('InnerAudio.on', { instanceId: 'a4', event: 'play', success: 'evtPlay' })
		call('InnerAudio.setSrc', { instanceId: 'a4', src: 'a.wav', success: 'ok', fail: 'err' })
		call('InnerAudio.play', { instanceId: 'a4', success: 'ok2', fail: 'err2' })
		await vi.waitFor(() => expect(fired.some(([id]) => id === 'ok2')).toBe(true))
		const rec = hostAudioInstanceForTest('a4')
		rec.audio.dispatch('play')
		expect(fired.some(([id, payload]) => id === 'evtPlay' && payload === undefined)).toBe(true)
	})

	it('stop 回执 success 且对登记者发合成 stop 事件,DOM pause 不误发', () => {
		const { call, fired } = makeHarness()
		call('InnerAudio.on', { instanceId: 'a5', event: 'stop', success: 'evtStop' })
		call('InnerAudio.setSrc', { instanceId: 'a5', src: 'a.wav' })
		const rec = hostAudioInstanceForTest('a5')
		rec.audio.paused = false
		call('InnerAudio.stop', { instanceId: 'a5', success: 'ok3' })
		expect(rec.audio.paused).toBe(true)
		expect(rec.audio.currentTime).toBe(0)
		expect(fired.some(([id]) => id === 'evtStop')).toBe(true)
	})

	it('off 摘除单个回调 id,off 无参摘除整个事件', () => {
		const { call, fired } = makeHarness()
		call('InnerAudio.on', { instanceId: 'a6', event: 'ended', success: 'evt1' })
		call('InnerAudio.on', { instanceId: 'a6', event: 'ended', success: 'evt2' })
		call('InnerAudio.off', { instanceId: 'a6', event: 'ended', success: 'evt1' })
		hostAudioInstanceForTest('a6').audio.dispatch('ended')
		expect(fired.map(([id]) => id)).toEqual(['evt2'])
		call('InnerAudio.off', { instanceId: 'a6', event: 'ended' })
		hostAudioInstanceForTest('a6').audio.dispatch('ended')
		expect(fired.map(([id]) => id)).toEqual(['evt2'])
	})

	it('destroy 暂停、清空事件表并删实例', () => {
		const { call, fired } = makeHarness()
		call('InnerAudio.on', { instanceId: 'a7', event: 'error', success: 'evtErr' })
		call('InnerAudio.setSrc', { instanceId: 'a7', src: 'a.wav' })
		call('InnerAudio.destroy', { instanceId: 'a7', success: 'ok4' })
		expect(hostAudioInstanceForTest('a7')).toBeUndefined()
		expect(fired.some(([id]) => id === 'ok4')).toBe(true)
	})

	it('getState 返回 duration/currentTime/paused;未知实例回落安全值', () => {
		const { call } = makeHarness()
		call('InnerAudio.setSrc', { instanceId: 'a8', src: 'a.wav' })
		const rec = hostAudioInstanceForTest('a8')
		rec.audio.duration = 12.5
		rec.audio.currentTime = 3.2
		rec.audio.paused = false
		const state = call('InnerAudio.getState', { instanceId: 'a8' })
		expect(state).toEqual({ duration: 12.5, currentTime: 3.2, paused: false })
		expect(call('InnerAudio.getState', { instanceId: 'nope' })).toEqual({ duration: 0, currentTime: 0, paused: true })
	})

	it('setProperty 映射 loop/volume/playbackRate/autoplay', () => {
		const { call } = makeHarness()
		call('InnerAudio.setProperty', {
			instanceId: 'a9',
			loop: true,
			volume: 2,
			playbackRate: 1.5,
			autoplay: true,
			success: 'ok5',
		})
		const rec = hostAudioInstanceForTest('a9')
		expect(rec.audio.loop).toBe(true)
		expect(rec.audio.volume).toBe(1) // 越界钳到 1
		expect(rec.audio.playbackRate).toBe(1.5)
		expect(rec.audio.autoplay).toBe(true)
	})
})
