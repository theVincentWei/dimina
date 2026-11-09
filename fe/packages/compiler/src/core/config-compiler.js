import fs from 'node:fs'
import {
	getAppConfigInfo,
	getAppId,
	getAppName,
	getContentByPath,
	getPageConfigInfo,
	getTargetPath,
	getThemeLocationPath,
	getWorkPath,
	isMiniGame,
} from '../env.js'
import { collectAssets } from '../common/utils.js'
import { copyBinaryRuntimeAssets } from '../common/binary-assets.js'

/**
 * 读取 app.json 声明的主题变量表并合成注入 app 配置。
 *
 * 微信语义:app.json 声明 "darkmode": true 时,"themeLocation"(默认 theme.json,
 * 相对项目根)指向一份 {"light":{...},"dark":{...}} 变量表。window/tabBar 等
 * 配置里以 "@key" 引用变量,编译器对这些引用一律原样透传、不做解析,由容器
 * 运行时按当前系统主题取值。
 *
 * darkmode 本身经 app.json 全键拷贝自然透传,无需在此写入;本函数只负责
 * 合成注入 theme 字段,并落实容错契约:
 * - darkmode 非 true:不产出 theme(保持未适配语义)。
 * - 主题文件缺失 / JSON 损坏 / 不含任何合法变量:视为「声明了却没主题表」,
 *   按未适配处理——丢弃 darkmode 并 warn,不允许编译失败。
 * - @key 未在变量表中定义:引用值保持原样(容器侧自行回退)。
 */
function processThemeConfig(app) {
	if (app?.darkmode !== true) {
		return
	}

	const themePath = getThemeLocationPath()

	let theme = null
	let themeError = null
	try {
		theme = normalizeThemeTable(JSON.parse(getContentByPath(themePath)))
	}
	catch (error) {
		themeError = error
	}
	if (!theme) {
		const reason = themeError ? themeError.message : '缺少合法的 light/dark 变量表'
		console.warn(`[darkmode] 无法使用主题文件 ${themePath}(${reason}),已按未适配深色模式处理(丢弃 darkmode)`)
		delete app.darkmode
		return
	}

	// compiler-synthesized:theme.json 是独立于 app.json 的文件,该字段并非
	// app.json 原生字段,而是编译产物提供的运行时契约:
	//   app.darkmode(true 时必有 theme)| app.theme = {light, dark}
	app.theme = theme
}

/**
 * 规整主题变量表:light/dark 均只保留字符串值的键(与容器
 * Map<String, Map<String, String>> 契约对齐),两表全空视为非法。
 */
function normalizeThemeTable(raw) {
	if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
		return null
	}
	const pickStringValues = (table) => {
		if (!table || typeof table !== 'object' || Array.isArray(table)) {
			return {}
		}
		return Object.fromEntries(
			Object.entries(table).filter(([, value]) => typeof value === 'string'),
		)
	}
	const theme = {
		light: pickStringValues(raw.light),
		dark: pickStringValues(raw.dark),
	}
	if (Object.keys(theme.light).length === 0 && Object.keys(theme.dark).length === 0) {
		return null
	}
	return theme
}

/**
 * 处理 tabBar.list 中的 iconPath / selectedIconPath。
 * 视为相对小程序根目录的资源，复用 collectAssets 拷贝到 main/static/，
 * 并把 list 中的路径改写成产物 URL，避免容器侧再做特殊解析。
 *
 * 注意：会在原 app 配置上原地修改，不影响后续输出（compileConfig 是
 * 整个流水线最后才走到的环节，不会被再次读取）。
 */
function processTabBarIcons(app) {
	const list = app?.tabBar?.list
	if (!Array.isArray(list) || list.length === 0) {
		return
	}
	const workPath = getWorkPath()
	const targetPath = getTargetPath()
	const appId = getAppId()

	for (const item of list) {
		// 第二参数 pagePath 留空 → collectAssets 内部 relativePath = ''，
		// 等价于把 src 当成相对小程序根目录解析，与 app.json 同级
		if (item.iconPath) {
			item.iconPath = collectAssets(workPath, '', item.iconPath, targetPath, appId)
		}
		if (item.selectedIconPath) {
			item.selectedIconPath = collectAssets(workPath, '', item.selectedIconPath, targetPath, appId)
		}
	}
}

/**
 *
 * 编译项目配置文件 app-config.json
 */
function compileConfig(dependencyGraph, { ignoredPaths = [] } = {}) {
	const app = getAppConfigInfo()
	copyBinaryRuntimeAssets(getWorkPath(), getTargetPath(), app.subPackages, dependencyGraph, { ignoredPaths, owner: isMiniGame() ? app.entryPagePath : 'app' })

	// darkmode/theme 合成注入(失败按未适配降级,详见函数注释)
	processThemeConfig(app)

	// 把 tabBar 图标复制到产物目录并改写 iconPath
	processTabBarIcons(app)

	const compileResInfo = {
		app,
		modules: getPageConfigInfo(),
		projectName: getAppName(),
	}

	const json = JSON.stringify(compileResInfo, null, 4)
	const mainDir = `${getTargetPath()}/main`
	if (!fs.existsSync(mainDir)) {
		fs.mkdirSync(mainDir, { recursive: true })
	}
	fs.writeFileSync(`${mainDir}/app-config.json`, json)
}

export default compileConfig
