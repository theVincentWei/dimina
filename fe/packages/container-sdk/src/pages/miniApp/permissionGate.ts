import type { StorageAdapter } from '../../types.js'

/**
 * 平台权限契约 v1.0 的 web SDK 守门（与 Android 容器 PermissionGate 同表对齐，
 * 契约冻结基准：sysmon-perms/platform-permissions-contract-draft.md §1/§3/§4，2026-11-14）。
 *
 * web 侧执行语义：
 *  - consent 按 appId 挂 localStorage 前缀（命名法同容器 storage v2 前缀
 *    `<prefix>_<len>:<appId>:`，独立 dimina_perm_v1_ 前缀，小程序 wx.setStorage 碰不到）；
 *  - 声明来源 = 启动拉取的 `<resourceBaseUrl><appId>/manifest.json` 的 permissions 段
 *    （wanqi 下发，老包 404 = 未声明任何敏感能力）；
 *  - 映射表内 web 未实现的 API 统一 `:fail not supported on web`（契约 §3）；
 *  - getClipboardData 是 web 唯一直达浏览器权限面的直调，声明过闸后放行给
 *    navigator.clipboard（浏览器自己的授权 UI 在后）；
 *  - request/connectSocket 等网络声明级：本机网关恒放行，外网目标按 origin 比对声明
 *    domains；授权级（record/notification）web 均无实现，走 not supported on web，
 *    无需弹窗。
 */

/** 授权级：web 无实现，纯目录占位（声明面 getSetting 展示）。 */
export const LEVEL_AUTHORIZED = 'authorized'

/** 声明级：声明才可用，不弹窗。 */
export const LEVEL_DECLARED = 'declared'

/** 占位级：目录内保留，未声明调用=诚实 fail。 */
export const LEVEL_PLACEHOLDER = 'placeholder'

/** v1 诚实不可用：调用一律 fail not supported。 */
export const LEVEL_UNSUPPORTED = 'unsupported'

/** 冻结 scope 目录 v1.0（契约 §1）。 */
export const SCOPE_CATALOG: Record<string, string> = {
	'scope.record': LEVEL_AUTHORIZED,
	'scope.notification': LEVEL_AUTHORIZED,
	'network.external': LEVEL_DECLARED,
	'scope.contacts': LEVEL_DECLARED,
	'scope.camera': LEVEL_PLACEHOLDER,
	'scope.writePhotosAlbum': LEVEL_PLACEHOLDER,
	'scope.bluetooth': LEVEL_PLACEHOLDER,
	'scope.clipboard': LEVEL_PLACEHOLDER,
	'scope.userLocation': LEVEL_UNSUPPORTED,
}

const SCOPE_RECORD = 'scope.record'
const SCOPE_NOTIFICATION = 'scope.notification'
const SCOPE_CAMERA = 'scope.camera'
const SCOPE_CLIPBOARD = 'scope.clipboard'
const SCOPE_USER_LOCATION = 'scope.userLocation'
const SCOPE_NETWORK = 'network.external'

/** getLocation/onLocationChange 族（v1 诚实不可用）。 */
const LOCATION_FAMILY: string[] = [
	'getLocation', 'getFuzzyLocation', 'chooseLocation', 'openLocation',
	'startLocationUpdate', 'stopLocationUpdate',
	'startFuzzyLocationUpdate', 'stopFuzzyLocationUpdate',
	'onLocationChange', 'offLocationChange', 'offLocationChangeError',
	'onFuzzyLocationChange', 'offFuzzyLocationChange',
]

/** BLE 全族（与 Android BluetoothApi.SUPPORTED_API_NAMES 镜像）。 */
const BLUETOOTH_FAMILY: string[] = [
	'openBluetoothAdapter', 'closeBluetoothAdapter', 'getBluetoothAdapterState',
	'startBluetoothDevicesDiscovery', 'stopBluetoothDevicesDiscovery',
	'getBluetoothDevices', 'getConnectedBluetoothDevices',
	'onBluetoothAdapterStateChange', 'offBluetoothAdapterStateChange',
	'onBluetoothDeviceFound', 'offBluetoothDeviceFound',
	'createBLEConnection', 'closeBLEConnection',
	'getBLEDeviceServices', 'getBLEDeviceCharacteristics',
	'readBLECharacteristicValue', 'writeBLECharacteristicValue',
	'notifyBLECharacteristicValueChange', 'getBLEDeviceRSSI', 'setBLEMTU', 'getBLEMTU',
	'onBLEConnectionStateChange', 'offBLEConnectionStateChange',
	'onBLECharacteristicValueChange', 'offBLECharacteristicValueChange',
	'onBLEMTUChange', 'offBLEMTUChange', 'isBluetoothDevicePaired', 'makeBluetoothPair',
]

/**
 * 冻结映射表（契约 §4）+ web 实现面。表中在 web 有实现的：
 * getClipboardData / chooseVideo / request / connectSocket（downloadFile/uploadFile 暂无
 * 实现，落 not supported on web）；其余在 web 均无实现（Recorder 族经前缀规则命中）。
 */
const API_SCOPE_MAP: Record<string, string> = {
	getRecorderManager: SCOPE_RECORD,
	requestSubscribeMessage: SCOPE_NOTIFICATION,
	chooseMedia: SCOPE_CAMERA,
	chooseVideo: SCOPE_CAMERA,
	chooseImage: SCOPE_CAMERA,
	scanCode: SCOPE_CAMERA,
	saveImageToPhotosAlbum: 'scope.writePhotosAlbum',
	saveVideoToPhotosAlbum: 'scope.writePhotosAlbum',
	getClipboardData: SCOPE_CLIPBOARD,
	addPhoneContact: 'scope.contacts',
	chooseContact: 'scope.contacts',
	request: SCOPE_NETWORK,
	downloadFile: SCOPE_NETWORK,
	uploadFile: SCOPE_NETWORK,
	connectSocket: SCOPE_NETWORK,
	...Object.fromEntries(LOCATION_FAMILY.map(name => [name, SCOPE_USER_LOCATION])),
	...Object.fromEntries(BLUETOOTH_FAMILY.map(name => [name, 'scope.bluetooth'])),
}

/** wx.* → scope（Recorder 操作族闸，onXxx/offXxx 订阅放行，与 Android 同规则）。 */
export function apiScopeOf(apiName: string): string | null {
	const mapped = API_SCOPE_MAP[apiName]
	if (mapped) return mapped
	if (apiName.startsWith('Recorder.')) {
		const op = apiName.slice('Recorder.'.length)
		return ['start', 'pause', 'resume', 'stop'].includes(op) ? SCOPE_RECORD : null
	}
	return null
}

export function scopeLevel(scope: string): string | null {
	return Object.prototype.hasOwnProperty.call(SCOPE_CATALOG, scope) ? SCOPE_CATALOG[scope] : null
}

/** camera 源甄别：仅 sourceType 含 camera 才算相机用途（缺省=微信默认，含 camera）。 */
export function isCameraSource(params: Record<string, unknown>): boolean {
	const sourceType = params.sourceType
	if (!Array.isArray(sourceType) || sourceType.length === 0) return true
	return sourceType.includes('camera')
}

// ---------------------------------------------------------------------------
// manifest.json permissions 段（声明）
// ---------------------------------------------------------------------------

export interface ScopeDeclaration {
	scope: string
	desc: string
}

export interface ManifestPermissions {
	scopes: Record<string, ScopeDeclaration>
	domains: string[]
}

/**
 * manifest.json（wanqi 形态 {scopes:[{key,title,usage,mode}],network:{domains}}，兼容
 * bundle.json 的 {scope,desc}）宽容解析；目录外 scope 忽略（wanqi 严式校验是第一道）。
 */
export function parseManifestPermissions(permissions: unknown): ManifestPermissions | null {
	if (!permissions || typeof permissions !== 'object') return null
	const root = permissions as Record<string, unknown>
	const scopes: Record<string, ScopeDeclaration> = {}
	if (Array.isArray(root.scopes)) {
		for (const entry of root.scopes) {
			if (!entry || typeof entry !== 'object') continue
			const record = entry as Record<string, unknown>
			const scope = typeof record.key === 'string' && record.key ? record.key
				: (typeof record.scope === 'string' ? record.scope : '')
			if (!scope || !(scope in SCOPE_CATALOG)) continue
			const desc = [record.usage, record.desc, record.title].find(value => typeof value === 'string' && value) as string | undefined
			scopes[scope] = { scope, desc: desc ?? '' }
		}
	}
	const network = root.network as Record<string, unknown> | undefined
	const domains = network && Array.isArray(network.domains)
		? network.domains.filter((domain): domain is string => typeof domain === 'string')
		: []
	if (Object.keys(scopes).length === 0 && domains.length === 0) return null
	return { scopes, domains }
}

const declarationCache = new Map<string, ManifestPermissions>()

/** 启动 manifest.json 拉取成功后回填（404/老包传 null = 清空声明）。 */
export function setManifestPermissions(
	appId: string,
	permissions: unknown,
	adapter?: StorageAdapter,
): ManifestPermissions | null {
	const parsed = parseManifestPermissions(permissions)
	if (!parsed) {
		declarationCache.delete(appId)
		return null
	}
	declarationCache.set(appId, parsed)
	syncNetworkDeclaration(appId, parsed.domains, adapter ?? resolveAdapter())
	return parsed
}

export function manifestPermissions(appId: string): ManifestPermissions | null {
	return declarationCache.get(appId) ?? null
}

/** 清空内存声明缓存（测试/容器重建用；consent 持久层不动）。 */
export function resetDeclarationCache(): void {
	declarationCache.clear()
}

// ---------------------------------------------------------------------------
// per-appid consent（localStorage 前缀，命名法同 storage v2）
// ---------------------------------------------------------------------------

export interface ConsentEntry {
	granted: boolean
	grantedAt: number
}

export interface Consent {
	scopes: Record<string, ConsentEntry>
	network: { domains: string[] }
}

/** 与容器 storage v2 同款 `<前缀>_<len>:<appId>:` 命名法，独立 dimina_perm_v1_ 前缀。 */
export function consentKey(appId: string): string {
	return `dimina_perm_v1_${appId.length}:${appId}:consent`
}

export function readConsent(appId: string, adapter: StorageAdapter): Consent {
	const raw = adapter.getItem(consentKey(appId))
	if (!raw) return { scopes: {}, network: { domains: [] } }
	try {
		const parsed = JSON.parse(raw) as Partial<Consent>
		return {
			scopes: parsed.scopes && typeof parsed.scopes === 'object' ? parsed.scopes : {},
			network: parsed.network && typeof parsed.network === 'object'
				? { domains: Array.isArray(parsed.network.domains) ? parsed.network.domains : [] }
				: { domains: [] },
		}
	}
	catch {
		return { scopes: {}, network: { domains: [] } }
	}
}

export function writeConsent(appId: string, consent: Consent, adapter: StorageAdapter): void {
	adapter.setItem(consentKey(appId), JSON.stringify(consent))
}

/** 容器 consent 置位（granted=false 落显式拒绝位，守门不再询问）。 */
export function setScopeGranted(appId: string, scope: string, granted: boolean, adapter: StorageAdapter): void {
	const consent = readConsent(appId, adapter)
	consent.scopes[scope] = { granted, grantedAt: Date.now() }
	writeConsent(appId, consent, adapter)
}

/** 授权级 consent 位：true/false=已落盘；null=从未询问。 */
export function consentOf(appId: string, scope: string, adapter: StorageAdapter): boolean | null {
	const entry = readConsent(appId, adapter).scopes[scope]
	return entry ? entry.granted === true : null
}

/** manifest 声明的 domains 镜像进 consent store 的 network 段（契约 store 形态）。 */
function syncNetworkDeclaration(appId: string, domains: string[], adapter: StorageAdapter | null): void {
	if (!adapter) return
	const consent = readConsent(appId, adapter)
	if (JSON.stringify(consent.network.domains) !== JSON.stringify(domains)) {
		consent.network = { domains }
		writeConsent(appId, consent, adapter)
	}
}

function resolveAdapter(): StorageAdapter | null {
	try {
		return window.localStorage
	}
	catch {
		return null
	}
}

// ---------------------------------------------------------------------------
// 守门判定
// ---------------------------------------------------------------------------

export type GateVerdict = { handled: false } | { handled: true, errMsg: string }

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]'])

/** 取 scheme://host[:port] origin；非 http(s)/ws(s) 或畸形 url 返回 null。 */
export function urlOrigin(url: string): string | null {
	const trimmed = String(url ?? '').trim()
	const schemeEnd = trimmed.indexOf('://')
	if (schemeEnd <= 0) return null
	const scheme = trimmed.slice(0, schemeEnd).toLowerCase()
	if (!['http', 'https', 'ws', 'wss'].includes(scheme)) return null
	const rest = trimmed.slice(schemeEnd + 3)
	const hostPort = rest.split(/[/?#]/)[0]
	if (!hostPort) return null
	return `${scheme}://${hostPort}`
}

export function isLoopbackOrigin(origin: string): boolean {
	const host = origin.split('://')[1]?.split('/')[0].split(':')[0].replace(/^\[|\]$/g, '') ?? ''
	return LOOPBACK_HOSTS.has(host)
}

/** 目标 url 是否被声明覆盖（loopback 恒放行；外网按 origin 精确比对）。 */
export function isUrlAllowed(appId: string, url: string): boolean {
	const origin = urlOrigin(url)
	if (!origin) return false
	if (isLoopbackOrigin(origin)) return true
	return (declarationCache.get(appId)?.domains ?? []).includes(origin)
}

/**
 * wx.* 预分发闸（判定顺序=契约 §3：查声明 → 查授权 → 放行/fail）。返回 handled=true
 * 时调用方直接以 errMsg fail；handled=false 继续原有分发。
 */
export function gateApi(
	apiName: string,
	params: Record<string, unknown>,
	appId: string,
	adapter: StorageAdapter,
): GateVerdict {
	const scope = apiScopeOf(apiName)
	if (!scope) return { handled: false }
	const level = scopeLevel(scope)
	if (!level) return { handled: false }

	// v1 诚实不可用（契约 §1：getLocation 族未实现，摘出支持面）。
	if (level === LEVEL_UNSUPPORTED) {
		return { handled: true, errMsg: `${apiName}:fail not supported` }
	}

	// web 未实现的映射表 API 统一诚实文案（契约 §3），声明面在 getSetting 仍可见。
	if (isWebUnsupported(apiName, scope)) {
		return { handled: true, errMsg: `${apiName}:fail not supported on web` }
	}

	const manifest = declarationCache.get(appId)

	// 网络声明级：本机网关恒放行；外网按 origin 比对声明 domains。
	if (scope === SCOPE_NETWORK) {
		if (isUrlAllowed(appId, String(params.url ?? ''))) return { handled: false }
		const declared = (manifest?.domains.length ?? 0) > 0
		return {
			handled: true,
			errMsg: declared
				? `${apiName}:fail url not in domain list`
				: `${apiName}:fail permission undeclared`,
		}
	}

	// 相机源甄别：chooseVideo 纯相册路径不消耗 scope.camera。
	if (scope === SCOPE_CAMERA && apiName === 'chooseVideo' && !isCameraSource(params)) {
		return { handled: false }
	}

	// 声明闸：无声明=诚实 fail（对照微信「未声明不能调」）。
	if (!manifest?.scopes[scope]) {
		return { handled: true, errMsg: `${apiName}:fail permission undeclared` }
	}

	// web 有实现且已声明：放行给原实现（浏览器授权 UI/系统语义在后）。
	return { handled: false }
}

function isWebUnsupported(apiName: string, scope: string): boolean {
	if (apiName.startsWith('Recorder.') || apiName === 'getRecorderManager') return true
	if (scope === SCOPE_NOTIFICATION) return true // requestSubscribeMessage 无宿主面板概念
	switch (scope) {
		case SCOPE_CAMERA:
			return apiName !== 'chooseVideo' // chooseMedia/chooseImage/scanCode web 无实现
		case 'scope.writePhotosAlbum':
		case 'scope.bluetooth':
		case 'scope.contacts':
			return true
		default:
			return false
	}
}
