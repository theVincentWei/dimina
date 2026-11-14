import { beforeEach, describe, expect, it } from 'vitest'
import {
	apiScopeOf,
	consentKey,
	consentOf,
	gateApi,
	isCameraSource,
	isUrlAllowed,
	parseManifestPermissions,
	readConsent,
	resetDeclarationCache,
	setManifestPermissions,
	setScopeGranted,
	urlOrigin,
} from '../src/pages/miniApp/permissionGate.js'

import type { StorageAdapter } from '../src/types.js'

/** 内存版 StorageAdapter（与容器宿主自定义 adapter 同形状）。 */
function createFakeAdapter(): StorageAdapter {
	const map = new Map<string, string>()
	return {
		getItem: key => map.get(key) ?? null,
		setItem: (key, value) => { map.set(key, value) },
		removeItem: key => { map.delete(key) },
		get length() { return map.size },
		key: index => [...map.keys()][index] ?? null,
	}
}

const APP_ID = 'wx7c3e9d2a1b4f5e68'
const WANQI_MANIFEST = {
	scopes: [
		{ key: 'scope.clipboard', title: '剪贴板', usage: '用于读取复制内容', mode: 'placeholder' },
		{ key: 'scope.camera', title: '相机', usage: '用于拍摄照片和视频', mode: 'placeholder' },
	],
	network: { domains: ['https://open.bigmodel.cn'] },
}

let adapter: StorageAdapter

beforeEach(() => {
	adapter = createFakeAdapter()
	resetDeclarationCache()
})

describe('frozen api→scope mapping (contract §4)', () => {
	it('maps catalog families and Recorder prefix', () => {
		expect(apiScopeOf('getRecorderManager')).toBe('scope.record')
		expect(apiScopeOf('Recorder.start')).toBe('scope.record')
		expect(apiScopeOf('requestSubscribeMessage')).toBe('scope.notification')
		expect(apiScopeOf('chooseMedia')).toBe('scope.camera')
		expect(apiScopeOf('chooseVideo')).toBe('scope.camera')
		expect(apiScopeOf('getClipboardData')).toBe('scope.clipboard')
		expect(apiScopeOf('addPhoneContact')).toBe('scope.contacts')
		expect(apiScopeOf('request')).toBe('network.external')
		expect(apiScopeOf('connectSocket')).toBe('network.external')
		expect(apiScopeOf('getLocation')).toBe('scope.userLocation')
		expect(apiScopeOf('openBluetoothAdapter')).toBe('scope.bluetooth')
	})

	it('leaves exempt surfaces unmapped', () => {
		expect(apiScopeOf('setStorageSync')).toBeNull()
		expect(apiScopeOf('setClipboardData')).toBeNull()
		expect(apiScopeOf('showModal')).toBeNull()
		expect(apiScopeOf('onAccelerometerChange')).toBeNull()
		expect(apiScopeOf('FileSystemManager.readFile')).toBeNull()
	})
})

describe('gateApi verdicts', () => {
	it('passes unknown apis through to the normal dispatch', () => {
		expect(gateApi('setStorageSync', { key: 'a' }, APP_ID, adapter).handled).toBe(false)
	})

	it('fails web-unimplemented catalog apis honestly', () => {
		expect(gateApi('getLocation', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'getLocation:fail not supported' })
		expect(gateApi('getRecorderManager', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'getRecorderManager:fail not supported on web' })
		expect(gateApi('Recorder.start', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'Recorder.start:fail not supported on web' })
		expect(gateApi('requestSubscribeMessage', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'requestSubscribeMessage:fail not supported on web' })
		expect(gateApi('chooseImage', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'chooseImage:fail not supported on web' })
		expect(gateApi('openBluetoothAdapter', {}, APP_ID, adapter)).toEqual({ handled: true, errMsg: 'openBluetoothAdapter:fail not supported on web' })
	})

	it('gates getClipboardData on declaration', () => {
		const undeclared = gateApi('getClipboardData', {}, APP_ID, adapter)
		expect(undeclared).toEqual({ handled: true, errMsg: 'getClipboardData:fail permission undeclared' })

		setManifestPermissions(APP_ID, WANQI_MANIFEST)
		expect(gateApi('getClipboardData', {}, APP_ID, adapter).handled).toBe(false)
	})

	it('gates chooseVideo only for camera source', () => {
		setManifestPermissions(APP_ID, WANQI_MANIFEST)
		// 纯相册路径不消耗 scope.camera
		expect(gateApi('chooseVideo', { sourceType: ['album'] }, APP_ID, adapter).handled).toBe(false)
		// 缺省（含 camera）+ 已声明 → 放行给文件选择器
		expect(gateApi('chooseVideo', {}, APP_ID, adapter).handled).toBe(false)
		// 未声明 + 相机源 → 诚实 fail
		expect(gateApi('chooseVideo', { sourceType: ['camera'] }, 'wx-other', adapter))
			.toEqual({ handled: true, errMsg: 'chooseVideo:fail permission undeclared' })
	})

	it('loopback gateway always passes, external needs declared origin', () => {
		expect(gateApi('request', { url: 'http://127.0.0.1:7610/api/v1/apps/x' }, APP_ID, adapter).handled).toBe(false)

		const undeclared = gateApi('request', { url: 'https://open.bigmodel.cn/api/paas/v4/models' }, APP_ID, adapter)
		expect(undeclared).toEqual({ handled: true, errMsg: 'request:fail permission undeclared' })

		setManifestPermissions(APP_ID, WANQI_MANIFEST)
		expect(gateApi('request', { url: 'https://open.bigmodel.cn/api/paas/v4/models' }, APP_ID, adapter).handled).toBe(false)
		expect(gateApi('request', { url: 'https://evil.example.com/api' }, APP_ID, adapter))
			.toEqual({ handled: true, errMsg: 'request:fail url not in domain list' })
		expect(gateApi('connectSocket', { url: 'wss://open.bigmodel.cn/ws' }, APP_ID, adapter))
			.toEqual({ handled: true, errMsg: 'connectSocket:fail url not in domain list' })
	})
})

describe('per-appid consent store', () => {
	it('uses length-prefixed per-appid localStorage prefix', () => {
		expect(consentKey(APP_ID)).toBe(`dimina_perm_v1_${APP_ID.length}:${APP_ID}:consent`)
		expect(consentKey('wx-other')).not.toBe(consentKey(APP_ID))
	})

	it('persists contract consent shape and isolates apps', () => {
		setScopeGranted(APP_ID, 'scope.record', true, adapter)
		const raw = JSON.parse(adapter.getItem(consentKey(APP_ID))!)
		expect(raw.scopes['scope.record'].granted).toBe(true)
		expect(raw.scopes['scope.record'].grantedAt).toBeGreaterThan(0)
		expect(raw.network).toEqual({ domains: [] })

		expect(consentOf(APP_ID, 'scope.record', adapter)).toBe(true)
		expect(consentOf('wx-other', 'scope.record', adapter)).toBeNull()

		// 显式拒绝位：consentOf false（守门不再询问）
		setScopeGranted('wx-other', 'scope.record', false, adapter)
		expect(consentOf('wx-other', 'scope.record', adapter)).toBe(false)
	})

	it('readConsent degrades to empty consent on malformed payload', () => {
		adapter.setItem(consentKey(APP_ID), 'garbage')
		expect(readConsent(APP_ID, adapter)).toEqual({ scopes: {}, network: { domains: [] } })
	})
})

describe('manifest permissions parsing', () => {
	it('parses wanqi manifest shape and mirrors domains', () => {
		const parsed = setManifestPermissions(APP_ID, WANQI_MANIFEST, adapter)
		expect(parsed?.scopes['scope.clipboard']?.desc).toBe('用于读取复制内容')
		expect(parsed?.domains).toEqual(['https://open.bigmodel.cn'])
		// 声明镜像进 consent store 的 network 段
		const consent = readConsent(APP_ID, adapter)
		expect(consent.network.domains).toEqual(['https://open.bigmodel.cn'])
	})

	it('accepts bundle.json declaration shape', () => {
		const parsed = parseManifestPermissions({ scopes: [{ scope: 'scope.record', desc: '需要麦克风' }] })
		expect(parsed?.scopes['scope.record']?.desc).toBe('需要麦克风')
	})

	it('drops catalog-external scopes and empty sections', () => {
		expect(parseManifestPermissions({ scopes: [{ key: 'scope.werun' }] })).toBeNull()
		expect(parseManifestPermissions(null)).toBeNull()
		expect(parseManifestPermissions('nope')).toBeNull()
	})
})

describe('url helpers', () => {
	it('extracts origins and applies loopback rules', () => {
		expect(urlOrigin('https://open.bigmodel.cn/api/x')).toBe('https://open.bigmodel.cn')
		expect(urlOrigin('http://127.0.0.1:7610/api')).toBe('http://127.0.0.1:7610')
		expect(urlOrigin('ftp://x')).toBeNull()
		expect(isUrlAllowed(APP_ID, 'http://localhost:7620/api/v1/health')).toBe(true)
		expect(isUrlAllowed(APP_ID, 'javascript:alert(1)')).toBe(false)
	})

	it('detects camera sources', () => {
		expect(isCameraSource({ sourceType: ['album'] })).toBe(false)
		expect(isCameraSource({ sourceType: ['album', 'camera'] })).toBe(true)
		expect(isCameraSource({})).toBe(true)
	})
})
