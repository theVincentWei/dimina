import { invokeAPI } from '@/api/common'
import { createSensorEvent } from '../sensor/shared'

// change 事件与 start/stop 的 wire 协议见 ../sensor/shared：on 隐式激活采样、
// off 不停源、interval 节流都由宿主侧实现，service 层只做回调 id 管理与透传。
const compassEvent = createSensorEvent('onCompassChange', 'offCompassChange')

/**
 * 监听罗盘数据事件。回调入参 {direction, accuracy}，direction 取值 0-360（度），
 * accuracy 为方向准确度数值。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/compass/wx.onCompassChange.html
 *
 * @param {(res: {direction: number, accuracy: number}) => void} listener
 */
export function onCompassChange(listener) {
	compassEvent.on(listener)
}

/**
 * 取消监听罗盘数据事件。不带参数摘除全部监听；按微信语义 off 不停源，
 * 采样仍可用 startCompass 重启。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/compass/wx.offCompassChange.html
 *
 * @param {(res: {direction: number, accuracy: number}) => void} [listener]
 */
export function offCompassChange(listener) {
	compassEvent.off(listener)
}

/**
 * 开始监听罗盘数据。宿主负责采样控制，service 层原样透传。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/compass/wx.startCompass.html
 *
 * @param {{success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function startCompass(opts) {
	return invokeAPI('startCompass', opts)
}

/**
 * 停止监听罗盘数据。
 * https://developers.weixin.qq.com/miniprogram/dev/api/device/compass/wx.stopCompass.html
 *
 * @param {{success?: Function, fail?: Function, complete?: Function}} [opts]
 */
export function stopCompass(opts) {
	return invokeAPI('stopCompass', opts)
}
