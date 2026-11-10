import { modDefine } from '@dimina/common'
import { describe, expect, it, vi } from 'vitest'
import '../src/core/env'
import runtime from '../src/core/runtime'
// 导入 service 单例：init() 会接管 DiminaServiceBridge.onMessage（桥桩已在 test-setup 就位）
import '../src/index.js'

// 回归背景（2026-11-11，双拼教练结果页按钮失效）：
// 容器 redirectTo 换页在修复前复用旧桥（updatePath destroy(true)+init(false)），
// 渲染层事件链静默死亡；容器修复后换页序列 = pageUnload(旧桥) + loadResource(全新桥、
// 新 resourceLoadId)。本用例按修复后的容器行为驱动 service 运行时，锁死三条不变式：
//   1. redirectTo 后旧页面 onUnload 必须执行；
//   2. 新页面实例必须创建并进入路由栈（moduleId 与旧页不同）；
//   3. 换页后页内 tap（onRetry/onStats）必须路由到新页面实例。
// 容器 Kotlin 侧（invokeFromJS 兜底分发 + messageInvoke service 信任）由设备冒烟
// 脚本覆盖，本用例只守 fe 运行时。
describe('redirectTo lifecycle (result page taps alive after redirect)', () => {
	it('routes taps to the redirected page and unloads the previous page', async () => {
		const trace = []
		globalThis.DiminaServiceBridge.publish = () => {}
		globalThis.DiminaServiceBridge.invoke = vi.fn(() => null)

		modDefine('app', () => {
			globalThis.__extraInfo = { path: 'app', usingComponents: {} }
			globalThis.App({ onLaunch() {} })
		})
		modDefine('pages/practice/practice', () => {
			globalThis.__extraInfo = { path: 'pages/practice/practice', usingComponents: {} }
			globalThis.Page({
				data: {},
				onLoad() { trace.push('practice.onLoad') },
				onUnload() { trace.push('practice.onUnload') },
			})
		})
		modDefine('pages/result/result', () => {
			globalThis.__extraInfo = { path: 'pages/result/result', usingComponents: {} }
			globalThis.Page({
				data: {},
				onLoad() { trace.push('result.onLoad') },
				onRetry() { trace.push('result.onRetry') },
				onStats() { trace.push('result.onStats') },
			})
		})

		const drive = (type, body) => globalThis.DiminaServiceBridge.onMessage({ type, body })

		// Phase 1: 首屏 practice（navigateTo 进入，桥 bridge_P）
		drive('loadResource', { appId: 'wx', bridgeId: 'bridge_P', pagePath: 'pages/practice/practice', query: {}, resourceLoadId: 'r1', runtimeType: 'miniProgram' })
		drive('resourceLoaded', { bridgeId: 'bridge_P', pagePath: 'pages/practice/practice', resourceLoadId: 'r1' })
		const practicePage = runtime.createInstance({ bridgeId: 'bridge_P', moduleId: 'page_p1', path: 'pages/practice/practice', query: {}, stackId: 'stack_main' })
		expect(practicePage).toBeTruthy()
		await Promise.resolve()
		expect(trace).toContain('practice.onLoad')

		// Phase 2: 修复后容器的 redirectTo 序列（pageUnload 旧桥 → 全新桥 loadResource → 新页实例）
		drive('pageUnload', { bridgeId: 'bridge_P' })
		drive('loadResource', { appId: 'wx', bridgeId: 'bridge_R', pagePath: 'pages/result/result', query: { id: 'na' }, resourceLoadId: 'r2', runtimeType: 'miniProgram' })
		drive('resourceLoaded', { bridgeId: 'bridge_R', pagePath: 'pages/result/result', resourceLoadId: 'r2' })
		const resultPage = runtime.createInstance({ bridgeId: 'bridge_R', moduleId: 'page_r1', path: 'pages/result/result', query: { id: 'na' }, stackId: 'stack_main' })
		expect(resultPage).toBeTruthy()
		expect(resultPage).not.toBe(practicePage)
		await Promise.resolve()
		expect(trace).toContain('practice.onUnload')
		expect(trace).toContain('result.onLoad')

		// Phase 3: 换页后页内 tap 必须路由到新页面实例
		drive('t', { bridgeId: 'bridge_R', moduleId: 'page_r1', methodName: 'onRetry', event: { type: 'tap' } })
		drive('t', { bridgeId: 'bridge_R', moduleId: 'page_r1', methodName: 'onStats', event: { type: 'tap' } })
		await Promise.resolve()
		expect(trace).toContain('result.onRetry')
		expect(trace).toContain('result.onStats')
	})
})
