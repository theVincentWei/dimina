// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
	accelerometerPayloadFromEvent,
	compassPayloadFromEvent,
	deviceMotionPayloadFromEvent,
	gyroscopePayloadFromEvent,
	injectSensorSample,
	normalizeIntervalLabel,
	registerSensorApi,
	resetSensorHostForTest,
	sensorChannelForTest,
	setSensorPanelActive,
} from '../src/sensorHost'

function makeHarness() {
	const registered = new Map()
	const container = { registerApi: (name, handler) => registered.set(name, handler) }
	registerSensorApi(container)
	const fired = []
	const makeApp = () => ({
		appInfo: { appId: 'wxtest' },
		createCallbackFunction: id => payload => fired.push([id, payload]),
	})
	const app = makeApp()
	const call = (name, params = {}, onApp = app) => registered.get(name)?.call(onApp, params)
	return { call, fired, app, makeApp }
}

/** 捕获 window 的传感器 DOM 监听器,可手动派发事件对象(jsdom 无 DeviceMotion 构造器)。 */
function captureDomListeners() {
	const addSpy = vi.spyOn(window, 'addEventListener')
	const removeSpy = vi.spyOn(window, 'removeEventListener')
	return {
		addSpy,
		removeSpy,
		dispatch(type, event) {
			for (const [handlerType, handler] of addSpy.mock.calls) {
				if (handlerType === type) {
					handler(event)
				}
			}
		},
		attachedCount() {
			return addSpy.mock.calls.filter(([type]) => type === 'devicemotion' || type === 'deviceorientation').length
		},
		detachedCount() {
			return removeSpy.mock.calls.filter(([type]) => type === 'devicemotion' || type === 'deviceorientation').length
		},
	}
}

describe('sensor host wire 契约', () => {
	beforeEach(() => {
		resetSensorHostForTest()
	})

	afterEach(() => {
		vi.restoreAllMocks()
		vi.unstubAllGlobals()
		resetSensorHostForTest()
	})

	it('onAccelerometerChange 登记 keep id,注入样本按 {x,y,z} 回点火', () => {
		const { call, fired, app } = makeHarness()
		call('onAccelerometerChange', { success: 'keep1' })
		expect(sensorChannelForTest(app, 'accelerometer').active).toBe(true)
		injectSensorSample('accelerometer', { x: 1.5, y: -2, z: 9.81 })
		expect(fired).toEqual([['keep1', { x: 1.5, y: -2, z: 9.81 }]])
	})

	it('同一 keep id 重复 on 幂等,一帧只点一次', () => {
		const { call, fired } = makeHarness()
		call('onAccelerometerChange', { success: 'keep1' })
		call('onAccelerometerChange', { success: 'keep1' }) // service 侧同一函数重复 on 拿同一 id
		injectSensorSample('accelerometer', { x: 0, y: 0, z: 0 })
		expect(fired).toHaveLength(1)
	})

	it('多个 keep id 各点一次;off 带参摘单个、off 无参摘全部', () => {
		vi.useFakeTimers()
		try {
			const { call, fired } = makeHarness()
			call('onAccelerometerChange', { success: 'keep1' })
			call('onAccelerometerChange', { success: 'keep2' })
			injectSensorSample('accelerometer', { x: 1, y: 2, z: 3 }) // 首帧立即,各点一次
			expect(fired.map(([id]) => id)).toEqual(['keep1', 'keep2'])

			call('offAccelerometerChange', { success: 'keep1' })
			injectSensorSample('accelerometer', { x: 4, y: 2, z: 3 }) // 窗口内 → 尾帧
			vi.advanceTimersByTime(200) // 尾帧只剩 keep2
			expect(fired.map(([id]) => id)).toEqual(['keep1', 'keep2', 'keep2'])

			call('offAccelerometerChange', {}) // 无 success 摘全部 → 停源
			injectSensorSample('accelerometer', { x: 9, y: 9, z: 9 })
			expect(fired.map(([id]) => id)).toEqual(['keep1', 'keep2', 'keep2'])
		}
		finally {
			vi.useRealTimers()
		}
	})

	it('off 不停已 start 的源;stop 才停', () => {
		const { call, fired, app } = makeHarness()
		call('onCompassChange', { success: 'keep1' })
		call('startCompass', { interval: 'game', success: 'ok1' })
		call('offCompassChange', {}) // 全部摘除但 started=true → 源不停(off 不停源)
		expect(sensorChannelForTest(app, 'compass').active).toBe(true)
		const countAfterOff = fired.length
		injectSensorSample('compass', { direction: 90, accuracy: 0 })
		expect(fired.length).toBe(countAfterOff) // keep 已摘光,源活跃但无人可点
		call('stopCompass', { success: 'ok2' })
		const countAfterStop = fired.length
		injectSensorSample('compass', { direction: 91, accuracy: 0 })
		expect(fired.length).toBe(countAfterStop) // stop → 停源,注入不再点火
		expect(fired).toEqual([
			['ok1', { errMsg: 'startCompass:ok' }],
			['ok2', { errMsg: 'stopCompass:ok' }],
		])
	})

	it('start 带 interval 节流:前缘立即发,窗口内合并成尾帧取最新值', () => {
		vi.useFakeTimers()
		try {
			const { call, fired } = makeHarness()
			call('onAccelerometerChange', { success: 'keep1' })
			injectSensorSample('accelerometer', { x: 1, y: 0, z: 0 }) // 首帧立即
			expect(fired).toEqual([['keep1', { x: 1, y: 0, z: 0 }]])

			injectSensorSample('accelerometer', { x: 2, y: 0, z: 0 }) // 窗口内 → 只挂尾帧
			injectSensorSample('accelerometer', { x: 3, y: 0, z: 0 })
			expect(fired).toHaveLength(1)

			vi.advanceTimersByTime(200) // normal=200ms 尾帧,取合并后的最新值
			expect(fired).toEqual([
				['keep1', { x: 1, y: 0, z: 0 }],
				['keep1', { x: 3, y: 0, z: 0 }],
			])
		}
		finally {
			vi.useRealTimers()
		}
	})

	it('start(interval=game) 后节流窗口变成 20ms', () => {
		vi.useFakeTimers()
		try {
			const { call, fired } = makeHarness()
			call('startAccelerometer', { interval: 'game', success: 'ok' })
			expect(fired).toEqual([['ok', { errMsg: 'startAccelerometer:ok' }]])

			call('onAccelerometerChange', { success: 'keep1' })
			injectSensorSample('accelerometer', { x: 1, y: 0, z: 0 })
			vi.advanceTimersByTime(19)
			injectSensorSample('accelerometer', { x: 2, y: 0, z: 0 })
			expect(fired.filter(([id]) => id === 'keep1')).toHaveLength(1) // 19ms < 20ms 窗口,仍只有首帧
			vi.advanceTimersByTime(1)
			injectSensorSample('accelerometer', { x: 3, y: 0, z: 0 })
			expect(fired.filter(([id]) => id === 'keep1')).toHaveLength(2)
		}
		finally {
			vi.useRealTimers()
		}
	})

	it('start 缺省 interval 按 normal;非法 interval 点 fail 且不激活', () => {
		const { call, fired, app } = makeHarness()
		call('startGyroscope', { success: 'ok1' }) // 缺省 → normal 档,照常激活并回执
		expect(sensorChannelForTest(app, 'gyroscope').active).toBe(true)
		expect(fired).toEqual([['ok1', { errMsg: 'startGyroscope:ok' }]])
		call('startGyroscope', { interval: 'turbo', success: 'ok2', fail: 'err2' })
		expect(fired).toContainEqual(['err2', { errMsg: 'startGyroscope:fail invalid interval "turbo"' }])
		expect(fired.filter(([id]) => id === 'ok2')).toEqual([])
	})

	it('stop 回执 success;stop 后全摘且未 start 停源', () => {
		const { call, fired, app } = makeHarness()
		call('onGyroscopeChange', { success: 'keep1' })
		call('stopGyroscope', { success: 'ok1' }) // 仍有 keep 登记者 → 源不停
		expect(sensorChannelForTest(app, 'gyroscope').active).toBe(true)
		call('offGyroscopeChange', {}) // 全摘且未 start → 停源
		expect(sensorChannelForTest(app, 'gyroscope').active).toBe(false)
		expect(fired).toEqual([['ok1', { errMsg: 'stopGyroscope:ok' }]])
	})

	it('多实例隔离:两个 app 各自登记互不串扰,广播各自回点火', () => {
		const { call, fired, makeApp } = makeHarness()
		const appB = makeApp()
		call('onDeviceMotionChange', { success: 'keepA' })
		call('onDeviceMotionChange', { success: 'keepB' }, appB)
		injectSensorSample('deviceMotion', { alpha: 10, beta: 20, gamma: -5 })
		expect(fired).toEqual([
			['keepA', { alpha: 10, beta: 20, gamma: -5 }],
			['keepB', { alpha: 10, beta: 20, gamma: -5 }],
		])
		call('offDeviceMotionChange', {}, appB)
		injectSensorSample('deviceMotion', { alpha: 11, beta: 21, gamma: -6 })
		expect(fired.filter(([id]) => id === 'keepB')).toHaveLength(1)
	})

	it('重新 on 激活后首帧立即回点火(不继承旧节流相位)', () => {
		vi.useFakeTimers()
		try {
			const { call, fired } = makeHarness()
			call('onAccelerometerChange', { success: 'keep1' })
			injectSensorSample('accelerometer', { x: 1, y: 0, z: 0 })
			call('offAccelerometerChange', {})
			call('onAccelerometerChange', { success: 'keep1' })
			injectSensorSample('accelerometer', { x: 7, y: 0, z: 0 })
			const payloads = fired.filter(([id]) => id === 'keep1').map(([, p]) => p.x)
			expect(payloads).toEqual([1, 7])
		}
		finally {
			vi.useRealTimers()
		}
	})

	it('normalizeIntervalLabel:缺省→normal,合法档透传,非法→null', () => {
		expect(normalizeIntervalLabel(undefined)).toBe('normal')
		expect(normalizeIntervalLabel('')).toBe('normal')
		expect(normalizeIntervalLabel('game')).toBe('game')
		expect(normalizeIntervalLabel('ui')).toBe('ui')
		expect(normalizeIntervalLabel('normal')).toBe('normal')
		expect(normalizeIntervalLabel('turbo')).toBe(null)
	})

	it('未知通道名注入抛错(面板接线防呆)', () => {
		expect(() => injectSensorSample('magnetometer', { x: 1 })).toThrow(/unknown channel/)
	})
})

describe('sensor host DOM 源', () => {
	beforeEach(() => {
		resetSensorHostForTest()
	})

	afterEach(() => {
		vi.restoreAllMocks()
		vi.unstubAllGlobals()
		resetSensorHostForTest()
	})

	it('桌面无 DeviceMotion/Orientation 时静默降级:on/start 不挂监听不报错', () => {
		// jsdom 30 自带两个事件类,stub 成 undefined 才是「桌面老浏览器」形态。
		vi.stubGlobal('DeviceMotionEvent', undefined)
		vi.stubGlobal('DeviceOrientationEvent', undefined)
		const { call, fired } = makeHarness()
		const dom = captureDomListeners()
		call('onAccelerometerChange', { success: 'keep1' })
		call('startCompass', { success: 'ok1' })
		expect(dom.attachedCount()).toBe(0)
		expect(fired).toEqual([['ok1', { errMsg: 'startCompass:ok' }]]) // API 面照常回执
		injectSensorSample('accelerometer', { x: 1, y: 0, z: 0 }) // 面板注入仍然可用
		expect(fired.filter(([id]) => id === 'keep1')).toHaveLength(1)
	})

	it('有 DeviceMotionEvent 时 on 激活挂监听,devicemotion 回点火加速度计与陀螺仪', () => {
		vi.stubGlobal('DeviceMotionEvent', class {})
		const { call, fired } = makeHarness()
		const dom = captureDomListeners()
		call('onAccelerometerChange', { success: 'keepA' })
		call('onGyroscopeChange', { success: 'keepG' })
		expect(dom.attachedCount()).toBeGreaterThan(0)

		dom.dispatch('devicemotion', {
			accelerationIncludingGravity: { x: 0.5, y: -1.25, z: 9.8 },
			rotationRate: { alpha: 180 / Math.PI, beta: 0, gamma: -36 / Math.PI },
		})
		const gyro = fired.find(([id]) => id === 'keepG')[1]
		expect(fired.find(([id]) => id === 'keepA')[1]).toEqual({ x: 0.5, y: -1.25, z: 9.8 })
		expect(gyro.x).toBeCloseTo(1, 5) // deg/s × π/180 → rad/s
		expect(gyro.y).toBeCloseTo(0, 5)
		expect(gyro.z).toBeCloseTo(-0.2, 5)
	})

	it('deviceorientation 回点火罗盘与设备方向', () => {
		vi.stubGlobal('DeviceOrientationEvent', class {})
		const { call, fired } = makeHarness()
		const dom = captureDomListeners()
		call('onCompassChange', { success: 'keepC' })
		call('onDeviceMotionChange', { success: 'keepD' })
		dom.dispatch('deviceorientation', { webkitCompassHeading: 361, alpha: 10, beta: 20, gamma: -30 })
		expect(fired.find(([id]) => id === 'keepC')[1]).toEqual({ direction: 1, accuracy: 0 }) // 361 归一化 0-360
		expect(fired.find(([id]) => id === 'keepD')[1]).toEqual({ alpha: 10, beta: 20, gamma: -30 })
	})

	it('面板开启时 DOM 源让位,注入链路不受影响;关闭后 DOM 接回', async () => {
		vi.stubGlobal('DeviceMotionEvent', class {})
		const { call, fired } = makeHarness()
		const dom = captureDomListeners()
		call('onAccelerometerChange', { success: 'keep1' })
		const attachedBefore = dom.attachedCount()

		setSensorPanelActive(true)
		dom.dispatch('devicemotion', { accelerationIncludingGravity: { x: 99, y: 99, z: 99 } })
		expect(fired).toEqual([]) // DOM 样本被丢弃

		injectSensorSample('accelerometer', { x: 1, y: 0, z: 0 }) // 面板注入照常
		expect(fired).toEqual([['keep1', { x: 1, y: 0, z: 0 }]])

		setSensorPanelActive(false)
		expect(dom.attachedCount()).toBeGreaterThan(attachedBefore) // DOM 监听接回
		dom.dispatch('devicemotion', { accelerationIncludingGravity: { x: 5, y: 0, z: 0 } })
		// 距上一帧注入不足节流窗口,走尾帧合并,waitFor 等 200ms 尾帧
		await vi.waitFor(() => expect(fired.some(([, p]) => p.x === 5)).toBe(true))
	})

	it('全部通道停源后摘掉 DOM 监听', () => {
		vi.stubGlobal('DeviceMotionEvent', class {})
		const { call, app } = makeHarness()
		const dom = captureDomListeners()
		call('onAccelerometerChange', { success: 'keep1' })
		call('offAccelerometerChange', {})
		expect(dom.detachedCount()).toBeGreaterThan(0)
		expect(sensorChannelForTest(app, 'accelerometer').active).toBe(false)
	})
})

describe('sensor host DOM 事件 → payload 换算', () => {
	it('accelerationIncludingGravity 直报 m/s²;字段缺失返回 null', () => {
		expect(accelerometerPayloadFromEvent({ accelerationIncludingGravity: { x: 1, y: 2, z: 3 } })).toEqual({ x: 1, y: 2, z: 3 })
		expect(accelerometerPayloadFromEvent({ accelerationIncludingGravity: null })).toBe(null)
		expect(accelerometerPayloadFromEvent({})).toBe(null)
	})

	it('rotationRate deg/s × π/180 → rad/s', () => {
		const payload = gyroscopePayloadFromEvent({ rotationRate: { alpha: 180 / Math.PI, beta: 36 / Math.PI, gamma: 0 } })
		expect(payload.x).toBeCloseTo(1, 5)
		expect(payload.y).toBeCloseTo(0.2, 5)
		expect(payload.z).toBeCloseTo(0, 5)
		expect(gyroscopePayloadFromEvent({})).toBe(null)
	})

	it('compass:webkitCompassHeading 优先并归一化 0-360;无 heading 用 (360-alpha)%360', () => {
		expect(compassPayloadFromEvent({ webkitCompassHeading: 10, webkitCompassAccuracy: 3 })).toEqual({ direction: 10, accuracy: 3 })
		expect(compassPayloadFromEvent({ alpha: 350 })).toEqual({ direction: 10, accuracy: 0 })
		expect(compassPayloadFromEvent({ alpha: 0 })).toEqual({ direction: 0, accuracy: 0 })
		expect(compassPayloadFromEvent({})).toBe(null)
		expect(compassPayloadFromEvent(undefined)).toBe(null)
	})

	it('deviceMotion:alpha/beta/gamma 直报;任一缺失返回 null', () => {
		expect(deviceMotionPayloadFromEvent({ alpha: 1, beta: -2, gamma: 3 })).toEqual({ alpha: 1, beta: -2, gamma: 3 })
		expect(deviceMotionPayloadFromEvent({ alpha: 1, beta: 2 })).toBe(null)
	})
})
