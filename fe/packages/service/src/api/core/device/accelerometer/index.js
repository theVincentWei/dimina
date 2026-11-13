import { invokeAPI } from '@/api/common'
import { createSensorEvent } from '../sensor/shared'

// change 事件与 start/stop 的 wire 协议见 ../sensor/shared：on 隐式激活采样、
// off 不停源、interval 节流都由宿主侧实现，service 层只做回调 id 管理与透传。
const accelerometerEvent = createSensorEvent('onAccelerometerChange', 'offAccelerometerChange')

/**
 * 监听加速度数据事件。回调入参 {x, y, z} 单位 m/s²，采样频率由
 * startAccelerometer 的 interval 决定（on 隐式激活采样时按默认频率）。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/accelerometer/wx.onAccelerometerChange.html
 *
 * @param {(res: {x: number, y: number, z: number}) => void} listener
 */
export function onAccelerometerChange(listener) {
	accelerometerEvent.on(listener)
}

/**
 * 取消监听加速度数据事件。不带参数摘除全部监听；按微信语义 off 不停源，
 * 采样仍可用 startAccelerometer 重启。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/accelerometer/wx.offAccelerometerChange.html
 *
 * @param {(res: {x: number, y: number, z: number}) => void} [listener]
 */
export function offAccelerometerChange(listener) {
	accelerometerEvent.off(listener)
}

/**
 * 开始监听加速度数据。interval 取 'game'(20ms)/'ui'(60ms)/'normal'(200ms)，
 * 宿主负责按 interval 节流，service 层原样透传。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/accelerometer/wx.startAccelerometer.html
 *
 * @param {{interval?: 'game'|'ui'|'normal', success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function startAccelerometer(opts) {
	return invokeAPI('startAccelerometer', opts)
}

/**
 * 停止监听加速度数据。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/accelerometer/wx.stopAccelerometer.html
 *
 * @param {{success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function stopAccelerometer(opts) {
	return invokeAPI('stopAccelerometer', opts)
}
