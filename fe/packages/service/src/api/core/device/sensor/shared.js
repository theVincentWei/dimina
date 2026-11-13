import { callback } from '@dimina/common'
import { invokeAPIWithoutPromise } from '@/api/common'

/**
 * 传感器（加速度计/罗盘/陀螺仪/设备方向）共用的 change 事件订阅机制。
 * wire 协议（三方契约，勿单方更改）：
 *   onXxxChange  -> invokeAPI('onXxxChange', { success: keepId })
 *                  keepId 是 callback.store(cb, true) 的常驻 id，宿主保存 id，
 *                  数据到达时按 id 回点火；同一函数重复 on 天然拿到同一 id，
 *                  宿主按幂等登记处理。
 *   offXxxChange -> invokeAPI('offXxxChange', { success: keepId } | {})
 *                  success 有值摘单个订阅，无值（不带 success）摘全部。
 *
 * 微信语义中 onXxxChange 隐式激活采样（首个 keep id 登记时宿主激活传感器）、
 * off 摘完不停源（仍可 start 重启）、start/stop 的 interval 节流，都由宿主侧
 * 实现；service 层只负责回调 id 管理与参数透传。
 *
 * 本文件刻意不叫 index.js：api/index.js 用 import.meta.glob 收集 core 目录下的
 * index.js 实现并登记到 wx 上，非 index.js 的文件不会被收集，这里的工具函数
 * 不会以 API 名字泄漏到 wx 上。
 */
export function createSensorEvent(onName, offName) {
	// keepId -> 用户回调。callback.store 的 keep 语义保证同一函数全局只占一个
	// id；每个传感器模块只有一个 change 事件、没有实例概念，id 与条目一一对应，
	// 因此不需要 audio 模块那种跨事件/跨实例的引用计数：同一函数重复 on 走下面的
	// has 去重直接跳过，摘除时删除唯一条目即可。
	const listeners = new Map()

	return {
		on(listener) {
			if (typeof listener !== 'function') {
				return
			}
			const keepId = callback.store(listener, true)
			if (listeners.has(keepId)) {
				// 同一函数重复 on：keepId 不变，宿主登记幂等，不再重复下发 wire。
				return
			}
			listeners.set(keepId, listener)
			invokeAPIWithoutPromise(onName, { success: keepId })
		},
		off(listener) {
			if (typeof listener === 'function') {
				for (const [keepId, bound] of listeners) {
					if (bound === listener) {
						listeners.delete(keepId)
						callback.remove(keepId)
						invokeAPIWithoutPromise(offName, { success: keepId })
						return
					}
				}
				// 未登记过的回调无可摘除，不下发 wire（与 audio 的按回调注销同语义）。
				return
			}
			for (const keepId of [...listeners.keys()]) {
				listeners.delete(keepId)
				callback.remove(keepId)
			}
			// 无参注销摘全部：本地没登记过也照发，清掉宿主侧可能残留的订阅
			//（与 audio 的无参注销同语义）。
			invokeAPIWithoutPromise(offName, {})
		},
	}
}
