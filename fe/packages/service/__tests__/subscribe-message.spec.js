/**
 * wx.requestSubscribeMessage 的 service 层契约：
 *   - 入口只是 API 面：把 tmplIds 原样桥给容器/原生（面板由宿主弹），
 *     不自行判定用户点击态、不做存储/记账。
 *   - 原生返回的微信形状 { errMsg: 'requestSubscribeMessage:ok', [tmplId]: 'accept' }
 *     原样交给 success / Promise resolve。
 *   - 非用户点击态（宿主返回 fail）走 fail / Promise reject，不能吞掉失败。
 */
import { describe, expect, it, vi } from 'vitest'

async function loadSubscribeApi() {
	vi.resetModules()
	globalThis.DiminaServiceBridge = {
		onMessage: null,
		invoke: vi.fn(() => 'invoke-result'),
		publish: vi.fn(() => 'publish-result'),
	}
	const [{ requestSubscribeMessage }, { callback }] = await Promise.all([
		import('../src/api/core/open-api/subscribe-message/index.js'),
		import('@dimina/common'),
	])
	return { requestSubscribeMessage, callback, bridge: globalThis.DiminaServiceBridge }
}

describe('wx.requestSubscribeMessage service adapter', () => {
	it('bridges tmplIds to container and resolves with the wx-shaped accept payload', async () => {
		const { requestSubscribeMessage, callback, bridge } = await loadSubscribeApi()
		const tmplIds = ['tpl_abc123', 'tpl_def456']

		const promise = requestSubscribeMessage({ tmplIds })

		const message = bridge.invoke.mock.calls[0][0]
		expect(message.body.name).toBe('requestSubscribeMessage')
		expect(message.body.params.tmplIds).toEqual(tmplIds)

		const result = {
			errMsg: 'requestSubscribeMessage:ok',
			tpl_abc123: 'accept',
			tpl_def456: 'reject',
		}
		callback.invoke(message.body.params.success, result)

		await expect(promise).resolves.toEqual(result)
	})

	it('rejects with the host failure when invoked outside a user tap gesture', async () => {
		const { requestSubscribeMessage, callback, bridge } = await loadSubscribeApi()
		const success = vi.fn()
		const fail = vi.fn()

		requestSubscribeMessage({ tmplIds: ['tpl_abc123'], success, fail })

		const params = bridge.invoke.mock.calls[0][0].body.params
		const error = {
			errMsg: 'requestSubscribeMessage:fail can only be invoked by user TAP gesture',
		}
		callback.invoke(params.fail, error)

		expect(success).not.toHaveBeenCalled()
		expect(fail).toHaveBeenCalledTimes(1)
		expect(fail).toHaveBeenCalledWith(error)
	})
})
