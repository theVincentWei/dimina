import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import build from '../src/index.js'

const THEME_TABLE = {
	light: {
		navBgColor: '#f7f7f7',
		navTxtStyle: 'black',
		tabBgColor: '#ffffff',
	},
	dark: {
		navBgColor: '#1a1a1a',
		navTxtStyle: 'white',
		tabBgColor: '#191919',
	},
}

describe('darkmode theme compilation', () => {
	let projectDir
	let outputDir

	beforeEach(() => {
		projectDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dimina-darkmode-'))
		outputDir = path.join(projectDir, 'dist')
	})

	afterEach(() => {
		vi.restoreAllMocks()
		fs.rmSync(projectDir, { recursive: true, force: true })
	})

	function write(relativePath, content) {
		const filePath = path.join(projectDir, relativePath)
		fs.mkdirSync(path.dirname(filePath), { recursive: true })
		fs.writeFileSync(filePath, content)
	}

	function writePage() {
		write('pages/index/index.json', '{}')
		write('pages/index/index.js', 'Page({})')
		write('pages/index/index.wxml', '<view>darkmode</view>')
		write('pages/index/index.wxss', '')
	}

	async function compile() {
		await build(outputDir, projectDir, false)
		return JSON.parse(fs.readFileSync(path.join(outputDir, 'main/app-config.json'), 'utf8'))
	}

	it('injects the theme table and passes @ references through verbatim', async () => {
		write('project.config.json', JSON.stringify({ appid: 'darkmode-app' }))
		write('theme.json', JSON.stringify(THEME_TABLE))
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: true,
			themeLocation: 'theme.json',
			window: {
				navigationBarBackgroundColor: '@navBgColor',
				navigationBarTextStyle: '@navTxtStyle',
				// 故意引用未定义变量:编译器必须原样透传,由容器侧回退
				backgroundColorTop: '@missingColor',
			},
			tabBar: {
				color: '@tabColor',
				backgroundColor: '@tabBgColor',
				list: [{ pagePath: 'pages/index/index', text: '首页' }],
			},
		}))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBe(true)
		expect(config.app.theme).toEqual(THEME_TABLE)
		// @key 引用一律原样透传(含未定义变量),编译器不解析
		expect(config.app.window.navigationBarBackgroundColor).toBe('@navBgColor')
		expect(config.app.window.backgroundColorTop).toBe('@missingColor')
		expect(config.app.tabBar.color).toBe('@tabColor')
	})

	it('keeps darkmode false without producing a theme table', async () => {
		write('project.config.json', JSON.stringify({ appid: 'darkmode-off-app' }))
		write('theme.json', JSON.stringify(THEME_TABLE))
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: false,
		}))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBe(false)
		expect(config.app.theme).toBeUndefined()
	})

	it('omits theme entirely when darkmode is not declared', async () => {
		write('project.config.json', JSON.stringify({ appid: 'darkmode-none-app' }))
		write('theme.json', JSON.stringify(THEME_TABLE))
		write('app.json', JSON.stringify({ pages: ['pages/index/index'] }))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBeUndefined()
		expect(config.app.theme).toBeUndefined()
	})

	it('drops darkmode with a warning when the theme file is missing', async () => {
		const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
		write('project.config.json', JSON.stringify({ appid: 'darkmode-missing-app' }))
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: true,
		}))
		writePage()

		const config = await compile()

		// 「声明了却没主题表」按未适配处理:丢弃 darkmode,编译不失败
		expect(config.app.darkmode).toBeUndefined()
		expect(config.app.theme).toBeUndefined()
		expect(warnSpy).toHaveBeenCalledWith(expect.stringMatching(/\[darkmode\]/))
	})

	it('drops darkmode with a warning when the theme file is malformed', async () => {
		const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
		write('project.config.json', JSON.stringify({ appid: 'darkmode-broken-app' }))
		write('theme.json', '{ broken json')
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: true,
			themeLocation: 'theme.json',
		}))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBeUndefined()
		expect(config.app.theme).toBeUndefined()
		expect(warnSpy).toHaveBeenCalledWith(expect.stringMatching(/\[darkmode\]/))
	})

	it('drops darkmode when the theme file has no usable light/dark table', async () => {
		const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
		write('project.config.json', JSON.stringify({ appid: 'darkmode-empty-app' }))
		// 合法 JSON 但没有合法变量表:视为坏主题文件
		write('theme.json', JSON.stringify({ light: {}, dark: {} }))
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: true,
		}))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBeUndefined()
		expect(config.app.theme).toBeUndefined()
		expect(warnSpy).toHaveBeenCalledWith(expect.stringMatching(/\[darkmode\]/))
	})

	it('keeps only string values and resolves a custom themeLocation', async () => {
		write('project.config.json', JSON.stringify({ appid: 'darkmode-custom-app' }))
		write('styles/theme.json', JSON.stringify({
			light: { navBgColor: '#ffffff', notAString: 42, nested: { key: 'value' } },
			dark: { navBgColor: '#000000' },
		}))
		write('app.json', JSON.stringify({
			pages: ['pages/index/index'],
			darkmode: true,
			themeLocation: 'styles/theme.json',
		}))
		writePage()

		const config = await compile()

		expect(config.app.darkmode).toBe(true)
		// 与容器 Map<String, Map<String, String>> 契约对齐:只保留字符串值
		expect(config.app.theme).toEqual({
			light: { navBgColor: '#ffffff' },
			dark: { navBgColor: '#000000' },
		})
	})
})
