import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/common', () => ({
	invokeAPI: vi.fn(),
	invokeAPIWithoutPromise: vi.fn(),
}))

import { callback } from '@dimina/common'
import { invokeAPI } from '@/api/common'
import { createInnerAudioContext, getRecorderManager } from '../src/api/core/media/audio/index.js'

// 契约（dimina-audio-contract.md 线协议速查）固定的 11 个事件，
// wire 上 event 字段取去 on 前缀的驼峰事件名。
const INNER_AUDIO_EVENT_NAMES = [
	['onCanplay', 'canplay'],
	['onPlay', 'play'],
	['onPause', 'pause'],
	['onStop', 'stop'],
	['onEnded', 'ended'],
	['onTimeUpdate', 'timeUpdate'],
	['onError', 'error'],
	['onWaiting', 'waiting'],
	['onSeeking', 'seeking'],
	['onSeeked', 'seeked'],
	['onLoadedmetadata', 'loadedmetadata'],
]

const RECORDER_EVENTS = [
	'onStart',
	'onPause',
	'onStop',
	'onError',
	'onInterruptionBegin',
	'onInterruptionEnd',
]

function lastCall() {
	return invokeAPI.mock.calls[invokeAPI.mock.calls.length - 1]
}

describe('InnerAudioContext', () => {
	beforeEach(() => {
		vi.mocked(invokeAPI).mockReset()
		callback.remove()
	})

	it('creates instances with WeChat defaults and a prefixed instanceId', () => {
		const ctx = createInnerAudioContext()
		expect(ctx.instanceId).toMatch(/^inner_audio_/)
		expect(ctx.src).toBe('')
		expect(ctx.startTime).toBe(0)
		expect(ctx.autoplay).toBe(false)
		expect(ctx.loop).toBe(false)
		expect(ctx.obeyMuteSwitch).toBe(true)
		expect(ctx.volume).toBe(1)
		expect(ctx.playbackRate).toBe(1)
		expect(ctx.paused).toBe(true)
		expect(ctx.duration).toBe(0)
		expect(ctx.currentTime).toBe(0)
	})

	it('forwards src assignment to InnerAudio.setSrc with stored callback ids, not promises', () => {
		const ctx = createInnerAudioContext()
		ctx.src = 'https://example.com/audio.mp3'

		expect(invokeAPI).toHaveBeenCalledTimes(1)
		const [name, params, target, allowPromise] = lastCall()
		expect(name).toBe('InnerAudio.setSrc')
		expect(params.instanceId).toBe(ctx.instanceId)
		expect(params.src).toBe('https://example.com/audio.mp3')
		expect(typeof params.success).toBe('string')
		expect(typeof params.fail).toBe('string')
		expect(target).toBe('container')
		expect(allowPromise).toBe(false)
		expect(ctx.src).toBe('https://example.com/audio.mp3')
	})

	it.each([
		['volume', 0.5],
		['loop', true],
		['playbackRate', 1.5],
		['startTime', 12.5],
		['autoplay', true],
		['obeyMuteSwitch', false],
	])('passes the %s assignment through to InnerAudio.setProperty', (key, value) => {
		const ctx = createInnerAudioContext()
		ctx[key] = value

		const [name, params] = lastCall()
		expect(name).toBe('InnerAudio.setProperty')
		expect(params.instanceId).toBe(ctx.instanceId)
		expect(params[key]).toBe(value)
		expect(ctx[key]).toBe(value)
	})

	it.each([
		['play', 'InnerAudio.play'],
		['pause', 'InnerAudio.pause'],
		['stop', 'InnerAudio.stop'],
	])('forwards %s over the dotted wire name', (method, wire) => {
		const ctx = createInnerAudioContext()
		ctx[method]()

		const [name, params] = lastCall()
		expect(name).toBe(wire)
		expect(params.instanceId).toBe(ctx.instanceId)
	})

	it('forwards seek with a position in seconds and ignores invalid positions', () => {
		const ctx = createInnerAudioContext()
		ctx.seek(30.5)

		let [name, params] = lastCall()
		expect(name).toBe('InnerAudio.seek')
		expect(params.instanceId).toBe(ctx.instanceId)
		expect(params.position).toBe(30.5)

		const callsBefore = invokeAPI.mock.calls.length
		ctx.seek(-1)
		ctx.seek(Number.NaN)
		ctx.seek('10')
		expect(invokeAPI.mock.calls.length).toBe(callsBefore)
	})

	it('updates the paused cache from control receipts', () => {
		const ctx = createInnerAudioContext()

		ctx.play()
		callback.invoke(lastCall()[1].success, { errMsg: 'InnerAudio.play:ok' })
		expect(ctx.paused).toBe(false)

		ctx.pause()
		callback.invoke(lastCall()[1].success, { errMsg: 'InnerAudio.pause:ok' })
		expect(ctx.paused).toBe(true)

		ctx.play()
		callback.invoke(lastCall()[1].success, { errMsg: 'InnerAudio.play:ok' })
		ctx.stop()
		callback.invoke(lastCall()[1].success, { errMsg: 'InnerAudio.stop:ok' })
		expect(ctx.paused).toBe(true)
	})

	it('reads duration/currentTime/paused from the getState sync result and caches the last values', () => {
		const ctx = createInnerAudioContext()
		vi.mocked(invokeAPI).mockReturnValueOnce({ duration: 180, currentTime: 42.5, paused: false })

		expect(ctx.duration).toBe(180)
		const [name, params, target, allowPromise] = invokeAPI.mock.calls[0]
		expect(name).toBe('InnerAudio.getState')
		expect(params.instanceId).toBe(ctx.instanceId)
		expect(target).toBe('container')
		expect(allowPromise).toBe(false)

		// 桥读不到同步结果时回落到上次缓存，而不是清零。
		expect(ctx.currentTime).toBe(42.5)
		expect(ctx.paused).toBe(false)
		expect(ctx.duration).toBe(180)
	})

	it.each(INNER_AUDIO_EVENT_NAMES)(
		'registers %s as InnerAudio.on with event "%s" and delivers payloads through the callback id',
		(method, event) => {
			const ctx = createInnerAudioContext()
			const listener = vi.fn()
			ctx[method](listener)

			const [name, params] = lastCall()
			expect(name).toBe('InnerAudio.on')
			expect(params.instanceId).toBe(ctx.instanceId)
			expect(params.event).toBe(event)
			expect(typeof params.success).toBe('string')

			callback.invoke(params.success, { marker: event })
			expect(listener).toHaveBeenCalledTimes(1)
			expect(listener).toHaveBeenCalledWith({ marker: event })

			ctx[`off${method.slice(2)}`](listener)
			const [offName, offParams] = lastCall()
			expect(offName).toBe('InnerAudio.off')
			expect(offParams.event).toBe(event)
			expect(offParams.success).toBe(params.success)

			callback.invoke(params.success, { marker: event })
			expect(listener).toHaveBeenCalledTimes(1)
		},
	)

	it('delivers an event to every registered listener with one wire registration each', () => {
		const ctx = createInnerAudioContext()
		const first = vi.fn()
		const second = vi.fn()
		ctx.onPlay(first)
		ctx.onPlay(second)

		const onCalls = invokeAPI.mock.calls.filter(([name]) => name === 'InnerAudio.on')
		expect(onCalls).toHaveLength(2)
		for (const [, params] of onCalls) callback.invoke(params.success, {})
		expect(first).toHaveBeenCalledTimes(1)
		expect(second).toHaveBeenCalledTimes(1)

		ctx.offPlay(first)
		for (const [, params] of invokeAPI.mock.calls.filter(([name]) => name === 'InnerAudio.on')) {
			callback.invoke(params.success, {})
		}
		expect(first).toHaveBeenCalledTimes(1)
		expect(second).toHaveBeenCalledTimes(2)
	})

	it('offXxx() removes the whole event, drops the success id from the wire, and purges callbacks', () => {
		const ctx = createInnerAudioContext()
		const first = vi.fn()
		const second = vi.fn()
		ctx.onPlay(first)
		ctx.onPlay(second)
		const ids = invokeAPI.mock.calls
			.filter(([name]) => name === 'InnerAudio.on')
			.map(([, params]) => params.success)

		ctx.offPlay()
		const [offName, offParams] = lastCall()
		expect(offName).toBe('InnerAudio.off')
		expect(offParams).toEqual({ instanceId: ctx.instanceId, event: 'play' })
		for (const id of ids) callback.invoke(id, {})
		expect(first).not.toHaveBeenCalled()
		expect(second).not.toHaveBeenCalled()
	})

	it('lets the same callback listen on two events over one callback id; offing one keeps the other alive', () => {
		const ctx = createInnerAudioContext()
		const listener = vi.fn()
		ctx.onPlay(listener)
		ctx.onTimeUpdate(listener)

		const onCalls = invokeAPI.mock.calls.filter(([name]) => name === 'InnerAudio.on')
		expect(onCalls).toHaveLength(2)
		expect(onCalls[0][1].success).toBe(onCalls[1][1].success)
		const sharedId = onCalls[0][1].success

		ctx.offPlay(listener)
		callback.invoke(sharedId, { currentTime: 1 })
		expect(listener).toHaveBeenCalledWith({ currentTime: 1 })

		ctx.offTimeUpdate(listener)
		callback.invoke(sharedId, { currentTime: 2 })
		expect(listener).toHaveBeenCalledTimes(1)
	})

	it('lets two instances share one listener id; offing one instance keeps the other alive', () => {
		const first = createInnerAudioContext()
		const second = createInnerAudioContext()
		const listener = vi.fn()
		first.onPlay(listener)
		second.onPlay(listener)

		const onCalls = invokeAPI.mock.calls.filter(([name]) => name === 'InnerAudio.on')
		expect(onCalls).toHaveLength(2)
		expect(onCalls[0][1].success).toBe(onCalls[1][1].success)
		const sharedId = onCalls[0][1].success

		first.offPlay(listener)
		callback.invoke(sharedId, {})
		expect(listener).toHaveBeenCalledTimes(1)

		second.offPlay(listener)
		callback.invoke(sharedId, {})
		expect(listener).toHaveBeenCalledTimes(1)
	})

	it('registering the same listener twice on one event sends the wire registration once', () => {
		const ctx = createInnerAudioContext()
		const listener = vi.fn()
		ctx.onPlay(listener)
		ctx.onPlay(listener)

		const onCalls = invokeAPI.mock.calls.filter(([name]) => name === 'InnerAudio.on')
		expect(onCalls).toHaveLength(1)

		const [, params] = onCalls[0]
		callback.invoke(params.success, {})
		expect(listener).toHaveBeenCalledTimes(1)
	})

	it('does not send wire when offing a callback that was never registered', () => {
		const ctx = createInnerAudioContext()
		ctx.offPlay(vi.fn())
		expect(invokeAPI).not.toHaveBeenCalled()
	})

	it('still forwards a full-clear InnerAudio.off when nothing was registered', () => {
		const ctx = createInnerAudioContext()
		ctx.offPlay()
		const [name, params] = lastCall()
		expect(name).toBe('InnerAudio.off')
		expect(params).toEqual({ instanceId: ctx.instanceId, event: 'play' })
	})

	it('destroy sends the wire once, then every method becomes a silent no-op', () => {
		const ctx = createInnerAudioContext()
		const listener = vi.fn()
		ctx.onPlay(listener)
		const onCall = invokeAPI.mock.calls.find(([name]) => name === 'InnerAudio.on')

		ctx.destroy()
		const destroyCall = invokeAPI.mock.calls.find(([name]) => name === 'InnerAudio.destroy')
		expect(destroyCall?.[1]).toMatchObject({ instanceId: ctx.instanceId })
		expect(typeof destroyCall?.[1].success).toBe('string')
		expect(typeof destroyCall?.[1].fail).toBe('string')

		invokeAPI.mockClear()
		ctx.play()
		ctx.pause()
		ctx.stop()
		ctx.seek(1)
		ctx.src = 'https://example.com/next.mp3'
		ctx.startTime = 5
		ctx.onPlay(vi.fn())
		ctx.offPlay()
		ctx.destroy()
		expect(ctx.paused).toBe(true)
		expect(invokeAPI).not.toHaveBeenCalled()

		// 销毁时已摘除回调表，原生即使晚到的事件也唤不回监听。
		callback.invoke(onCall[1].success, {})
		expect(listener).not.toHaveBeenCalled()
	})
})

describe('RecorderManager', () => {
	beforeEach(() => {
		vi.mocked(invokeAPI).mockReset()
		callback.remove()
	})

	it('returns the same singleton across calls', () => {
		expect(getRecorderManager()).toBe(getRecorderManager())
	})

	it('starts with WeChat defaults and paired settler ids', () => {
		getRecorderManager().start()

		const [name, params, target, allowPromise] = lastCall()
		expect(name).toBe('Recorder.start')
		expect(params.duration).toBe(60000)
		expect(params.format).toBe('aac')
		expect(typeof params.success).toBe('string')
		expect(typeof params.fail).toBe('string')
		expect(target).toBe('container')
		expect(allowPromise).toBe(false)
	})

	it('forwards custom recording options', () => {
		getRecorderManager().start({
			duration: 5000,
			sampleRate: 44100,
			numberOfChannels: 2,
			encodeBitRate: 192000,
			format: 'wav',
		})

		const [name, params] = lastCall()
		expect(name).toBe('Recorder.start')
		expect(params).toEqual({
			duration: 5000,
			sampleRate: 44100,
			numberOfChannels: 2,
			encodeBitRate: 192000,
			format: 'wav',
			success: expect.any(String),
			fail: expect.any(String),
		})
	})

	it('clamps duration to the 600000ms ceiling and falls back to the default on garbage', () => {
		const mgr = getRecorderManager()
		mgr.start({ duration: 999999 })
		expect(lastCall()[1].duration).toBe(600000)

		mgr.start({ duration: 'oops' })
		expect(lastCall()[1].duration).toBe(60000)
	})

	it('rejects unsupported formats through onError without touching the bridge', () => {
		const mgr = getRecorderManager()
		const onError = vi.fn()
		mgr.onError(onError)
		const callsAfterSubscribe = invokeAPI.mock.calls.length

		mgr.start({ format: 'mp3' })

		expect(invokeAPI.mock.calls.length).toBe(callsAfterSubscribe)
		expect(onError).toHaveBeenCalledTimes(1)
		expect(onError.mock.calls[0][0].errMsg).toContain('Recorder.start:fail invalid format')
	})

	it('routes native start failures to the onError listeners', () => {
		const mgr = getRecorderManager()
		const onError = vi.fn()
		mgr.onError(onError)

		mgr.start({ format: 'aac' })
		const startCall = invokeAPI.mock.calls.find(([name]) => name === 'Recorder.start')
		callback.invoke(startCall[1].fail, { errMsg: 'Recorder.start:fail auth deny' })

		expect(onError).toHaveBeenCalledTimes(1)
		expect(onError).toHaveBeenCalledWith({ errMsg: 'Recorder.start:fail auth deny' })
	})

	it.each([
		['pause', 'Recorder.pause'],
		['resume', 'Recorder.resume'],
		['stop', 'Recorder.stop'],
	])('forwards %s over the dotted wire name', (method, wire) => {
		getRecorderManager()[method]()

		const [name, params] = lastCall()
		expect(name).toBe(wire)
		expect(typeof params.success).toBe('string')
		expect(typeof params.fail).toBe('string')
	})

	it.each(
		RECORDER_EVENTS.map((method) => [method, `Recorder.${method}`, `off${method.slice(2)}`, `Recorder.off${method.slice(2)}`]),
	)(
		'registers %s over %s and removes it over %s',
		(method, onWire, offMethod, offWire) => {
			const mgr = getRecorderManager()
			const listener = vi.fn()
			mgr[method](listener)

			const [name, params] = lastCall()
			expect(name).toBe(onWire)
			expect(typeof params.success).toBe('string')

			callback.invoke(params.success, { via: method })
			expect(listener).toHaveBeenCalledWith({ via: method })

			mgr[offMethod](listener)
			const [offName, offParams] = lastCall()
			expect(offName).toBe(offWire)
			expect(offParams.success).toBe(params.success)

			callback.invoke(params.success, { via: method })
			expect(listener).toHaveBeenCalledTimes(1)
		},
	)

	it('delivers the onStop recording result payload unchanged', () => {
		const mgr = getRecorderManager()
		const onStop = vi.fn()
		mgr.onStop(onStop)

		const [, params] = lastCall()
		callback.invoke(params.success, { tempFilePath: 'difile://tmp/rec.aac', duration: 60000, fileSize: 960000 })

		expect(onStop).toHaveBeenCalledWith({ tempFilePath: 'difile://tmp/rec.aac', duration: 60000, fileSize: 960000 })
	})

	it('offXxx() drops the success id from the wire and clears every listener of the event', () => {
		const mgr = getRecorderManager()
		const first = vi.fn()
		const second = vi.fn()
		mgr.onStart(first)
		mgr.onStart(second)
		const ids = invokeAPI.mock.calls
			.filter(([name]) => name === 'Recorder.onStart')
			.map(([, params]) => params.success)

		mgr.offStart()
		const [offName, offParams] = lastCall()
		expect(offName).toBe('Recorder.offStart')
		expect(offParams).toEqual({})
		for (const id of ids) callback.invoke(id, {})
		expect(first).not.toHaveBeenCalled()
		expect(second).not.toHaveBeenCalled()
	})
})
