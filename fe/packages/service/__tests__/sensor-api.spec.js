/**
 * 微信兼容传感器 API（加速度计/罗盘/陀螺仪/设备方向）service 层单测。
 *
 * 走真实 @/api/common + 桩 DiminaServiceBridge，断言到达桥的最终 wire 形状
 * （body.name / body.params），覆盖三方契约：
 *   - onXxxChange 下发 { success: keepId }，宿主保存 id、数据到达按 id 回点火；
 *   - offXxxChange 带 success 摘单个、不带 success 摘全部；
 *   - 同一函数重复 on 天然去重（keep id 不变，wire 不重复下发）；
 *   - start/stop 走一次性回执（success/fail 成对 id，宿主点 success），
 *     无回调参数调用 promise 化（对齐微信，同 vibrateShort 等同类 API）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { callback } from '@dimina/common'
import {
	offAccelerometerChange,
	onAccelerometerChange,
	startAccelerometer,
	stopAccelerometer,
} from '../src/api/core/device/accelerometer/index.js'
import {
	offCompassChange,
	onCompassChange,
	startCompass,
	stopCompass,
} from '../src/api/core/device/compass/index.js'
import {
	offDeviceMotionChange,
	onDeviceMotionChange,
	startDeviceMotionListening,
	stopDeviceMotionListening,
} from '../src/api/core/device/device-motion/index.js'
import {
	offGyroscopeChange,
	onGyroscopeChange,
	startGyroscope,
	stopGyroscope,
} from '../src/api/core/device/gyroscope/index.js'

const SENSORS = [
	{
		moduleName: '加速度计',
		on: onAccelerometerChange,
		off: offAccelerometerChange,
		start: startAccelerometer,
		stop: stopAccelerometer,
		onName: 'onAccelerometerChange',
		offName: 'offAccelerometerChange',
		startName: 'startAccelerometer',
		stopName: 'stopAccelerometer',
		payload: { x: 0.1, y: -0.2, z: 9.8 },
	},
	{
		moduleName: '罗盘',
		on: onCompassChange,
		off: offCompassChange,
		start: startCompass,
		stop: stopCompass,
		onName: 'onCompassChange',
		offName: 'offCompassChange',
		startName: 'startCompass',
		stopName: 'stopCompass',
		payload: { direction: 359.5, accuracy: 0.9 },
	},
	{
		moduleName: '陀螺仪',
		on: onGyroscopeChange,
		off: offGyroscopeChange,
		start: startGyroscope,
		stop: stopGyroscope,
		onName: 'onGyroscopeChange',
		offName: 'offGyroscopeChange',
		startName: 'startGyroscope',
		stopName: 'stopGyroscope',
		payload: { x: 0.01, y: -0.02, z: 0.03 },
	},
	{
		moduleName: '设备方向',
		on: onDeviceMotionChange,
		off: offDeviceMotionChange,
		start: startDeviceMotionListening,
		stop: stopDeviceMotionListening,
		onName: 'onDeviceMotionChange',
		offName: 'offDeviceMotionChange',
		startName: 'startDeviceMotionListening',
		stopName: 'stopDeviceMotionListening',
		payload: { alpha: 90, beta: 45, gamma: -10 },
	},
]

function wireInvokes() {
	return globalThis.DiminaServiceBridge.invoke.mock.calls.map(([msg]) => msg)
}

function lastWire() {
	const calls = wireInvokes()
	expect(calls.length).toBeGreaterThan(0)
	return calls[calls.length - 1]
}

const lastWireName = () => lastWire().body.name
const lastWireParams = () => lastWire().body.params

beforeEach(() => {
	// 先挂 invoke 桩再清订阅：模块的 off() 会真实下发摘全部 wire。
	globalThis.DiminaServiceBridge.invoke = vi.fn(() => 'invoke-result')
	globalThis.DiminaServiceBridge.publish = vi.fn(() => 'publish-result')
	offAccelerometerChange()
	offCompassChange()
	offGyroscopeChange()
	offDeviceMotionChange()
	callback.remove()
	// 清掉 beforeEach 自己产生的 wire 调用，各用例从零计数。
	globalThis.DiminaServiceBridge.invoke.mockClear()
	globalThis.DiminaServiceBridge.publish.mockClear()
})

describe('传感器 on/off change 事件 wire 协议', () => {
	it.each(SENSORS)('$moduleName：on 下发 keep 回调 id，宿主保存 id、回点可反复触发', ({ on, onName, payload }) => {
		const listener = vi.fn()
		on(listener)

		const msg = lastWire()
		expect(msg.type).toBe('invokeAPI')
		expect(msg.target).toBe('container')
		expect(msg.body.name).toBe(onName)
		const params = msg.body.params
		expect(params).toEqual({ success: expect.any(String) })

		// keep 语义：同一 id 回点两次都能触发，且回调不被一次性消费。
		callback.invoke(params.success, payload)
		callback.invoke(params.success, payload)
		expect(listener).toHaveBeenCalledTimes(2)
		expect(listener).toHaveBeenLastCalledWith(payload)
	})

	it.each(SENSORS)('$moduleName：同一函数重复 on 去重，wire 只下发一次', ({ on, onName }) => {
		const listener = vi.fn()
		on(listener)
		on(listener)
		on(listener)

		const calls = wireInvokes()
		expect(calls).toHaveLength(1)
		expect(calls[0].body.name).toBe(onName)
	})

	it.each(SENSORS)('$moduleName：不同回调各自拿到独立 keep id，宿主按 id 分别回点', ({ on, payload }) => {
		const first = vi.fn()
		const second = vi.fn()
		on(first)
		on(second)

		const calls = wireInvokes()
		expect(calls).toHaveLength(2)
		const firstId = calls[0].body.params.success
		const secondId = calls[1].body.params.success
		expect(firstId).not.toBe(secondId)

		callback.invoke(firstId, payload)
		expect(first).toHaveBeenCalledTimes(1)
		expect(second).not.toHaveBeenCalled()

		callback.invoke(secondId, payload)
		expect(second).toHaveBeenCalledTimes(1)
	})

	it.each(SENSORS)('$moduleName：off(cb) 只摘对应 keep id，wire 带 success，其余监听不受影响', ({ on, off, offName, payload }) => {
		const first = vi.fn()
		const second = vi.fn()
		on(first)
		on(second)
		const firstId = wireInvokes()[0].body.params.success
		const secondId = wireInvokes()[1].body.params.success

		off(first)
		expect(lastWireName()).toBe(offName)
		expect(lastWireParams()).toEqual({ success: firstId })

		// 被摘的 id 回点无效，未摘的照常触发。
		callback.invoke(firstId, payload)
		expect(first).not.toHaveBeenCalled()
		callback.invoke(secondId, payload)
		expect(second).toHaveBeenCalledTimes(1)
	})

	it.each(SENSORS)('$moduleName：off() 无参摘全部，wire 不带 success，全部 keep id 失效', ({ on, off, offName, payload }) => {
		const first = vi.fn()
		const second = vi.fn()
		on(first)
		on(second)
		const firstId = wireInvokes()[0].body.params.success
		const secondId = wireInvokes()[1].body.params.success

		off()
		expect(lastWireName()).toBe(offName)
		expect(lastWireParams()).toEqual({})

		callback.invoke(firstId, payload)
		callback.invoke(secondId, payload)
		expect(first).not.toHaveBeenCalled()
		expect(second).not.toHaveBeenCalled()
	})

	it.each(SENSORS)('$moduleName：本地无登记时 off() 照发摘全部 wire（兜住宿主残留）', ({ off, offName }) => {
		off()
		expect(lastWireName()).toBe(offName)
		expect(lastWireParams()).toEqual({})
	})

	it.each(SENSORS)('$moduleName：off 未登记过的回调不下发 wire', ({ on, off }) => {
		on(vi.fn())
		const before = wireInvokes().length

		off(vi.fn())
		expect(wireInvokes()).toHaveLength(before)
	})

	it.each(SENSORS)('$moduleName：on 非函数是 no-op，不下发 wire', ({ on }) => {
		on(undefined)
		on(null)
		on('cb')
		on(123)
		expect(wireInvokes()).toHaveLength(0)
	})

	it.each(SENSORS)('$moduleName：off 后重新 on 同一函数拿到新 keep id 并重新登记', ({ on, off }) => {
		const listener = vi.fn()
		on(listener)
		const firstId = lastWireParams().success

		off(listener)
		on(listener)

		const calls = wireInvokes()
		expect(calls).toHaveLength(3)
		const secondId = lastWireParams().success
		expect(secondId).not.toBe(firstId)
		expect(lastWireParams()).toEqual({ success: secondId })
	})
})

describe('传感器 start/stop 一次性回执', () => {
	it.each(SENSORS)('$moduleName：$startName 透传 interval 并登记成对回执，success 触发后 fail 失效', ({ start, startName }) => {
		const success = vi.fn()
		const fail = vi.fn()
		const complete = vi.fn()

		const ret = start({ interval: 'game', success, fail, complete })
		// 回调风格不 promise 化（sync 返回值是桥的透传，不由 service 层定义）。
		expect(ret).not.toBeInstanceOf(Promise)

		expect(lastWireName()).toBe(startName)
		const params = lastWireParams()
		expect(params.interval).toBe('game')
		expect(params.success).toEqual(expect.any(String))
		expect(params.fail).toEqual(expect.any(String))
		expect(params.complete).toEqual(expect.any(String))

		const result = { errMsg: `${startName}:ok` }
		callback.invoke(params.success, result)
		expect(success).toHaveBeenCalledWith(result)

		// 一次性回执成对清账：success 点过之后 fail id 已失效。
		callback.invoke(params.fail, { errMsg: `${startName}:fail` })
		expect(fail).not.toHaveBeenCalled()

		// complete 独立收尾。
		callback.invoke(params.complete, { errMsg: `${startName}:ok` })
		expect(complete).toHaveBeenCalledTimes(1)
	})

	it.each(SENSORS)('$moduleName：$stopName 只传 success 时自动补 fail 侧回执', ({ stop, stopName }) => {
		const success = vi.fn()

		stop({ success })

		const params = lastWireParams()
		expect(params).toEqual({ success: expect.any(String), fail: expect.any(String) })

		const result = { errMsg: `${stopName}:ok` }
		callback.invoke(params.success, result)
		expect(success).toHaveBeenCalledWith(result)
	})

	it.each(SENSORS)('$moduleName：$startName/$stopName 无回调参数时 promise 化（对齐微信）', async ({ start, stop, startName, stopName }) => {
		const promise = start({ interval: 'ui' })
		expect(promise).toBeInstanceOf(Promise)
		expect(lastWireName()).toBe(startName)
		const params = lastWireParams()
		expect(params).toEqual({ interval: 'ui', success: expect.any(String), fail: expect.any(String) })
		const result = { errMsg: `${startName}:ok` }
		callback.invoke(params.success, result)
		await expect(promise).resolves.toEqual(result)

		const stopPromise = stop()
		expect(stopPromise).toBeInstanceOf(Promise)
		expect(lastWireName()).toBe(stopName)
		const stopParams = lastWireParams()
		expect(Object.keys(stopParams).sort()).toEqual(['fail', 'success'])
		const stopResult = { errMsg: `${stopName}:ok` }
		callback.invoke(stopParams.success, stopResult)
		await expect(stopPromise).resolves.toEqual(stopResult)
	})
})

describe('传感器 API 面', () => {
	it('四个模块合计导出 16 个名字，与微信公开 API 一一对应', async () => {
		const [accelerometer, compass, gyroscope, deviceMotion] = await Promise.all([
			import('../src/api/core/device/accelerometer/index.js'),
			import('../src/api/core/device/compass/index.js'),
			import('../src/api/core/device/gyroscope/index.js'),
			import('../src/api/core/device/device-motion/index.js'),
		])
		const names = [accelerometer, compass, gyroscope, deviceMotion]
			.flatMap(mod => Object.keys(mod))
			.sort()
		expect(names).toEqual([
			'offAccelerometerChange',
			'offCompassChange',
			'offDeviceMotionChange',
			'offGyroscopeChange',
			'onAccelerometerChange',
			'onCompassChange',
			'onDeviceMotionChange',
			'onGyroscopeChange',
			'startAccelerometer',
			'startCompass',
			'startDeviceMotionListening',
			'startGyroscope',
			'stopAccelerometer',
			'stopCompass',
			'stopDeviceMotionListening',
			'stopGyroscope',
		])
	})
})
