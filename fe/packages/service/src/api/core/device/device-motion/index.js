import { invokeAPI } from '@/api/common'
import { createSensorEvent } from '../sensor/shared'

// change 事件与 start/stop 的 wire 协议见 ../sensor/shared：on 隐式激活采样、
// off 不停源、interval 节流都由宿主侧实现，service 层只做回调 id 管理与透传。
// 注意微信的 start/stop 公开名是 *DeviceMotionListening，目录名才是 device-motion。
const deviceMotionEvent = createSensorEvent('onDeviceMotionChange', 'offDeviceMotionChange')

/**
 * 监听设备方向变化事件。回调入参 {alpha, beta, gamma} 单位角度，遵循
 * 设备坐标系欧拉角约定（alpha 0-360，beta/gamma -180-180）。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/device-motion/wx.onDeviceMotionChange.html
 *
 * @param {(res: {alpha: number, beta: number, gamma: number}) => void} listener
 */
export function onDeviceMotionChange(listener) {
	deviceMotionEvent.on(listener)
}

/**
 * 取消监听设备方向变化事件。不带参数摘除全部监听；按微信语义 off 不停源，
 * 采样仍可用 startDeviceMotionListening 重启。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/device-motion/wx.offDeviceMotionChange.html
 *
 * @param {(res: {alpha: number, beta: number, gamma: number}) => void} [listener]
 */
export function offDeviceMotionChange(listener) {
	deviceMotionEvent.off(listener)
}

/**
 * 开始监听设备方向变化。interval 取 'game'(20ms)/'ui'(60ms)/'normal'(200ms)，
 * 宿主负责按 interval 节流，service 层原样透传。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/device-motion/wx.startDeviceMotionListening.html
 *
 * @param {{interval?: 'game'|'ui'|'normal', success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function startDeviceMotionListening(opts) {
	return invokeAPI('startDeviceMotionListening', opts)
}

/**
 * 停止监听设备方向变化。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/device-motion/wx.stopDeviceMotionListening.html
 *
 * @param {{success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function stopDeviceMotionListening(opts) {
	return invokeAPI('stopDeviceMotionListening', opts)
}
