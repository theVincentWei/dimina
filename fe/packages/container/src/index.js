import { createContainer, QueryRouter } from '@dimina/fe-container-sdk'
import '@dimina/fe-container-sdk/style.css'
import { AppList } from '@/pages/appList/appList'
import { Device } from '@/pages/device/device'
import { getMiniAppInfo, manifestInfoCache, resolveManifestResourceBaseUrl } from '@/services'
import '@/styles/app.scss'

// 万器(dsh-go AppHub)网关固定基址。request 胶水(dev 下)对它的请求剥掉 origin
// 走 vite dev server 的 /api/v1、/icons 同源代理(vite.config.mjs),不进 dev
// proxy——后者按 SSRF 防护拒绝私网地址,而网关恰恰在本机回环上。
const WANQI_GATEWAY_ORIGIN = 'http://127.0.0.1:7610'

window.onload = function () {
	const device = new Device()
	const container = createContainer({
		mount: device.appContainer,
		shell: device, // Device 实现了 SDK 要求的 getStatusBarRect/updateStatusBarColor 接口
		getAppInfo: appId => manifestInfoCache.get(appId) ?? getMiniAppInfo(appId),
		// SDK 自身的默认值恒为 '/'（预构建 dist，感知不到宿主的部署路径）；这里必须显式传本包
		// 构建时的 BASE_URL（GitHub Pages 部署在 /dimina/ 子路径下，见 vite.config.mjs 的 base 配置），
		// 否则小程序资源与 pageFrame.html 都会从站点根路径请求，在子路径部署下 404。
		resourceBaseUrl: import.meta.env.BASE_URL,
	})
	device.open(container.application)

	/**
	 * 注册 Mock 第三方扩展模块，供 ext-bridge demo 页面使用。
	 * 宿主接入时用真实的 native bridge 实现替换此处的注册调用。
	 *
	 * handler 签名：({ event, data, success, fail }) => unsubscribeFn | void
	 * - 持续订阅（extOnBridge）：返回取消订阅函数
	 * - 一次性调用（extBridge）：调用 success/fail，无需返回值
	 */
	container.registerExtModule('DemoNativeModule', ({ event, data, success, fail }) => {
		if (event === 'getUserInfo') {
			setTimeout(() => {
				success?.({ uid: data?.uid ?? 'unknown', name: 'Mock User', level: 3 })
			}, 300)
			return
		}

		if (event === 'onTickEvent') {
			let count = 0
			const timer = setInterval(() => {
				count++
				success?.({ tick: count, timestamp: Date.now() })
			}, 1000)
			return () => clearInterval(timer)
		}

		fail?.({ errMsg: `DemoNativeModule: unknown event "${event}"` })
	})

	/**
	 * SDK 不内置 wx.request 的网络请求实现（container-sdk 是通用运行时，不能假设宿主
	 * 一定有本地代理这类基础设施）；本 demo 配套 fe/packages/server 的开发态 CORS 代理，
	 * 在这里通过 registerApi 接上，行为与移除前完全一致。
	 */
	container.registerApi('request', function ({
		url,
		data,
		header = {},
		timeout = 0, // 0 表示不设超时
		method = 'GET',
		dataType = 'json',
		responseType = 'text',
		success,
		fail,
		complete,
	}) {
		const init = {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
			},
			body: JSON.stringify({
				url,
				data,
				header,
				timeout,
				method,
				dataType,
				responseType,
			}),
		}

		const onSuccess = this.createCallbackFunction(success)
		const onFail = this.createCallbackFunction(fail)
		const onComplete = this.createCallbackFunction(complete)

		// 万器网关直连分流(仅 dev server 生效,生产构建不含本分支):网关固定基址是
		// 本机回环,dev proxy 的 SSRF 防护会拒绝它;这里剥掉 origin 变同源相对路径,
		// 由 vite proxy 转发。方法/头/超时/回调包语义与下方 /proxy 路径保持一致。
		if (import.meta.env.DEV && typeof url === 'string' && url.startsWith(WANQI_GATEWAY_ORIGIN)) {
			const requestMethod = method.toUpperCase()
			const directHeaders = { ...header }
			let body
			if (requestMethod !== 'GET' && requestMethod !== 'HEAD' && data !== undefined && data !== null) {
				if (typeof data === 'string') {
					body = data
				}
				else {
					if (!Object.keys(directHeaders).some((key) => key.toLowerCase() === 'content-type')) {
						directHeaders['Content-Type'] = 'application/json'
					}
					body = JSON.stringify(data)
				}
			}

			const abort = new AbortController()
			const timer = timeout > 0 ? setTimeout(() => abort.abort(), timeout) : null

			fetch(url.slice(WANQI_GATEWAY_ORIGIN.length), {
				method: requestMethod,
				headers: directHeaders,
				body,
				signal: abort.signal,
			})
				.then(async (response) => {
					if (!response.ok) {
						const error = new Error(response.statusText)
						error.code = response.status
						throw error
					}

					const headers = {}
					response.headers.forEach((value, key) => {
						headers[key] = value
					})

					if (responseType === 'arraybuffer') {
						return { data: await response.arrayBuffer(), header: headers, statusCode: response.status }
					}

					const text = await response.text()
					let parsed = text
					if (dataType === 'json') {
						try {
							parsed = JSON.parse(text)
						}
						catch {
							// 与 wx.request 的 json 语义一致:解析失败回落原始文本
						}
					}
					return { data: parsed, header: headers, statusCode: response.status }
				})
				.then((data) => {
					onSuccess?.(data)
				})
				.catch((error) => {
					const message = error.name === 'AbortError' ? `request:fail timeout (${timeout}ms)` : error.message
					onFail?.({ errMsg: message, errno: error.code })
				})
				.finally(() => {
					if (timer) {
						clearTimeout(timer)
					}
					onComplete?.()
				})
			return
		}

		fetch('http://localhost:7788/proxy', init)
			.then((response) => {
				if (!response.ok) {
					const error = new Error(response.statusText)
					error.code = response.status
					throw error
				}

				const headers = {}
				response.headers.forEach((value, key) => {
					headers[key] = value
				})

				switch (dataType) {
					case 'json':
						return response.json().then(data => ({ data: JSON.parse(data), header: headers, statusCode: response.status }))
					case 'arraybuffer':
						return response.arrayBuffer().then(data => ({ data, header: headers, statusCode: response.status }))
					default:
						return response.text().then(data => ({ data, header: headers, statusCode: response.status }))
				}
			})
			.then((data) => {
				onSuccess?.(data)
			})
			.catch((error) => {
				onFail?.({ errMsg: error.message, errno: error.code })
			})
			.finally(() => {
				onComplete?.()
			})
	})

	const appListPage = new AppList(container)
	container.setRootView(appListPage)

	const searchParams = new URLSearchParams(window.location.search)
	const manifestUrl = searchParams.get('manifestUrl')
	if (manifestUrl) {
		resolveManifestResourceBaseUrl(null, manifestUrl)
			.then(manifest => container.openApp({
				appId: manifest.appId,
				path: searchParams.get('entry') || manifest.path,
				resourceBaseUrl: manifest.resourceBaseUrl,
				scene: 1001,
				destroy: true,
			}))
			.catch((error) => {
				console.error('[container] open from manifestUrl failed:', error)
			})
		return
	}

	// 刷新后从 query 路由恢复小程序及页面；兼容旧 hash 路由
	const parsed = QueryRouter.parse(window.location.hash, window.location.search)
	if (parsed) {
		resolveManifestResourceBaseUrl(parsed.appId, null)
			.then(manifest => container.openApp({
				appId: parsed.appId,
				resourceBaseUrl: manifest?.resourceBaseUrl,
				scene: 1001,
				destroy: true,
				// 省略 path：入口页取 restoreStack[0]（pagePath + query）；完整栈用于静默恢复二级页面
				restoreStack: parsed.stack,
			}))
			.catch((error) => {
				// URL 是用户可改的输入（appId 可能无效，manifest 可能取不到），失败不能只剩 unhandled rejection
				console.error('[container] restore openApp failed:', error)
			})
	}
}
