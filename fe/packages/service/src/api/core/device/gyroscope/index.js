import { invokeAPI } from '@/api/common'
import { createSensorEvent } from '../sensor/shared'

// change 事件与 start/stop 的 wire 协议见 ../sensor/shared：on 隐式激活采样、
// off 不停源、interval 节流都由宿主侧实现，service 层只做回调 id 管理与透传。
const gyroscopeEvent = createSensorEvent('onGyroscopeChange', 'offGyroscopeChange')

/**
 * 监听陀螺仪数据事件。回调入参 {x, y, z} 单位 rad/s，采样频率由
 * startGyroscope 的 interval 决定（on 隐式激活采样时按默认频率）。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/gyroscope/wx.onGyroscopeChange.html
 *
 * @param {(res: {x: number, y: number, z: number}) => void} listener
 */
export function onGyroscopeChange(listener) {
	gyroscopeEvent.on(listener)
}

/**
 * 取消监听陀螺仪数据事件。不带参数摘除全部监听；按微信语义 off 不停源，
 * 采样仍可用 startGyroscope 重启。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/gyroscope/wx.offGyroscopeChange.html
 *
 * @param {(res: {x: number, y: number, z: number}) => void} [listener]
 */
export function offGyroscopeChange(listener) {
	gyroscopeEvent.off(listener)
}

/**
 * 开始监听陀螺仪数据。interval 取 'game'(20ms)/'ui'(60ms)/'normal'(200ms)，
 * 宿主负责按 interval 节流，service 层原样透传。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/gyroscope/wx.startGyroscope.html
 *
 * @param {{interval?: 'game'|'ui'|'normal', success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function startGyroscope(opts) {
	return invokeAPI('startGyroscope', opts)
}

/**
 * 停止监听陀螺仪数据。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/gyroscope/wx.stopGyroscope.html
 *
 * @param {{success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function stopGyroscope(opts) {
	return invokeAPI('stopGyroscope', opts)
}
