/**
 * Web 传感器调试面板(?sensorPanel=1 显式开启,生产构建也可用)。
 *
 * 定位:web 容器的开发/验收工具——桌面浏览器没有 DeviceMotion/Orientation,手机
 * 页面在浏览器里验收传感器交互(小球、罗盘)不能靠摇真机,这里用面板把四个通道的
 * 数值注入宿主。注入走 sensorHost.injectSensorSample,与真实 DOM 源同一条
 * broadcast→节流→回点火链路(mock 源就是源的一个实现),面板开启时 DOM 源让位。
 *
 * UI:右下角抽屉 + 收起态 FAB,挂 document.body(容器 phone-frame 之外,不遮胶囊)。
 * 深浅色跟 prefers-color-scheme(容器页面本身不随小程序深色主题变,面板浮在页面层)。
 *
 * 通道:加速度计 X/Y/Z(±10 m/s²,步进 0.01)+ 摇一摇(随机脉冲序列)+ 重力翻转;
 * 罗盘方向(0-360)+ 表盘拖动 + 自动旋转;陀螺仪 X/Y/Z(±10 rad/s);设备方向
 * alpha/beta/gamma。全局「自动摆动」让当前通道按正弦波动,模拟手持晃动,验收免手拖。
 */

import { injectSensorSample, setSensorPanelActive } from './sensorHost'
import './sensorPanel.scss'

const TABS = [
	{ id: 'accelerometer', label: '加速度' },
	{ id: 'compass', label: '罗盘' },
	{ id: 'gyroscope', label: '陀螺仪' },
	{ id: 'deviceMotion', label: '方向' },
]

const FAB_ICON = `<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
	<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.6"/>
	<path d="M15.5 8.5 13.2 13.2 8.5 15.5 10.8 10.8Z" fill="currentColor"/>
	<circle cx="12" cy="12" r="1.1" fill="currentColor"/>
</svg>`

const state = {
	tab: 'accelerometer',
	open: false,
	wiggle: false,
	autoRotate: false,
	shakeUntil: 0,
	spinOffset: 0,
	lastTick: 0,
	frame: 0,
	channels: {
		accelerometer: { x: 0, y: 0, z: 9.81 },
		compass: { direction: 0 },
		gyroscope: { x: 0, y: 0, z: 0 },
		deviceMotion: { alpha: 0, beta: 0, gamma: 0 },
	},
}

const AXIS_CONFIG = {
	accelerometer: [
		{ key: 'x', label: 'X', min: -10, max: 10, step: 0.01, unit: ' m/s²' },
		{ key: 'y', label: 'Y', min: -10, max: 10, step: 0.01, unit: ' m/s²' },
		{ key: 'z', label: 'Z', min: -10, max: 10, step: 0.01, unit: ' m/s²' },
	],
	compass: [
		{ key: 'direction', label: '方向', min: 0, max: 360, step: 1, unit: '°', integer: true },
	],
	gyroscope: [
		{ key: 'x', label: 'X', min: -10, max: 10, step: 0.01, unit: ' rad/s' },
		{ key: 'y', label: 'Y', min: -10, max: 10, step: 0.01, unit: ' rad/s' },
		{ key: 'z', label: 'Z', min: -10, max: 10, step: 0.01, unit: ' rad/s' },
	],
	deviceMotion: [
		{ key: 'alpha', label: 'α', min: 0, max: 360, step: 1, unit: '°', integer: true },
		{ key: 'beta', label: 'β', min: -180, max: 180, step: 1, unit: '°', integer: true },
		{ key: 'gamma', label: 'γ', min: -90, max: 90, step: 1, unit: '°', integer: true },
	],
}

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

function formatValue(value, integer) {
	return integer ? String(Math.round(value)) : value.toFixed(2)
}

function buildDrawerBody(tabId) {
	const values = state.channels[tabId]
	const parts = []

	if (tabId === 'compass') {
		parts.push(`<div class="dimina-sensor-panel__dial-wrap">
			<div class="dimina-sensor-panel__dial" data-role="dial">
				<span class="dimina-sensor-panel__dial-n">N</span>
				<div class="dimina-sensor-panel__needle" data-role="needle"></div>
			</div>
			<div class="dimina-sensor-panel__dial-reading"><span data-role="dial-value">0</span>°</div>
		</div>`)
	}

	for (const axis of AXIS_CONFIG[tabId]) {
		parts.push(`<div class="dimina-sensor-panel__row">
			<label class="dimina-sensor-panel__axis">${axis.label}</label>
			<input class="dimina-sensor-panel__slider" type="range" data-role="axis"
				data-key="${axis.key}" min="${axis.min}" max="${axis.max}" step="${axis.step}" value="${values[axis.key]}">
			<span class="dimina-sensor-panel__val" data-role="val" data-key="${axis.key}">${formatValue(values[axis.key], axis.integer)}${axis.unit}</span>
		</div>`)
	}

	if (tabId === 'accelerometer') {
		parts.push(`<div class="dimina-sensor-panel__actions">
			<button type="button" class="dimina-sensor-panel__btn" data-action="shake">摇一摇</button>
			<button type="button" class="dimina-sensor-panel__btn" data-action="flipGravity">重力翻转</button>
		</div>`)
	}
	if (tabId === 'compass') {
		parts.push(`<label class="dimina-sensor-panel__toggle"><input type="checkbox" data-toggle="autoRotate"><span>自动旋转</span></label>`)
	}

	return parts.join('')
}

/* ---------------- 数值驱动:面板循环 = mock 源的产生端 ---------------- */

function currentPayloads(now, dt) {
	const t = now / 1000
	const values = state.channels
	const shaking = now < state.shakeUntil

	const accel = { ...values.accelerometer }
	if (shaking) {
		// 摇一摇:随机脉冲序列(强抖 X/Y、轻抖 Z),停了自然回落到滑杆基准值。
		accel.x += (Math.random() * 2 - 1) * 7
		accel.y += (Math.random() * 2 - 1) * 7
		accel.z += (Math.random() * 2 - 1) * 3
	}
	else if (state.wiggle && state.tab === 'accelerometer') {
		accel.x += Math.sin(t * 2.1) * 1.8
		accel.y += Math.sin(t * 1.7 + 1.3) * 1.8
		accel.z += Math.sin(t * 2.6 + 0.5) * 0.9
	}

	if (state.autoRotate) {
		state.spinOffset = (state.spinOffset + dt * 36) % 360 // 10 秒一圈
	}
	let direction = (values.compass.direction + state.spinOffset) % 360
	if (state.wiggle && state.tab === 'compass') {
		direction = (direction + 360 + Math.sin(t * 1.2) * 22) % 360
	}

	const gyro = { ...values.gyroscope }
	if (state.wiggle && state.tab === 'gyroscope') {
		gyro.x += Math.sin(t * 2.4) * 2.5
		gyro.y += Math.sin(t * 1.9 + 0.8) * 2.5
		gyro.z += Math.sin(t * 2.9 + 1.6) * 2.5
	}

	const orient = { ...values.deviceMotion }
	if (state.wiggle && state.tab === 'deviceMotion') {
		orient.alpha += Math.sin(t * 1.1) * 25
		orient.beta += Math.sin(t * 1.6 + 0.4) * 15
		orient.gamma += Math.sin(t * 2.0 + 1.1) * 10
	}

	return {
		accelerometer: accel,
		compass: { direction: (direction + 360) % 360, accuracy: 0 },
		gyroscope: gyro,
		deviceMotion: {
			alpha: clamp(orient.alpha, 0, 360),
			beta: clamp(orient.beta, -180, 180),
			gamma: clamp(orient.gamma, -90, 90),
		},
	}
}

function tick(now) {
	if (!state.open) {
		state.frame = 0
		return
	}
	const dt = state.lastTick ? Math.min((now - state.lastTick) / 1000, 0.1) : 0
	state.lastTick = now
	const payloads = currentPayloads(now, dt)
	for (const [channel, payload] of Object.entries(payloads)) {
		injectSensorSample(channel, payload)
	}
	syncReadouts(payloads)
	state.frame = requestAnimationFrame(tick)
}

function syncReadouts(payloads) {
	const root = panelRoot
	if (!root) {
		return
	}
	for (const el of root.querySelectorAll('[data-role="val"]')) {
		const key = el.dataset.key
		const axis = AXIS_CONFIG[state.tab].find(a => a.key === key)
		if (axis) {
			el.textContent = `${formatValue(payloads[state.tab][key], axis.integer)}${axis.unit}`
			const slider = root.querySelector(`.dimina-sensor-panel__slider[data-key="${key}"]`)
			// 摆动/摇一摇驱动数值时滑杆跟着走;用户正在拖动时 number input 会自己回写,不抢。
			if (slider && !slider.matches(':active')) {
				slider.value = String(payloads[state.tab][key])
			}
		}
	}
	const direction = payloads.compass.direction
	const needle = root.querySelector('[data-role="needle"]')
	if (needle) {
		needle.style.transform = `rotate(${direction}deg)`
	}
	const dialValue = root.querySelector('[data-role="dial-value"]')
	if (dialValue) {
		dialValue.textContent = String(Math.round(direction))
	}
}

/* ---------------- DOM 组装与事件 ---------------- */

let panelRoot = null

function renderTabs(root) {
	const tabs = root.querySelector('[data-role="tabs"]')
	tabs.innerHTML = TABS.map(tab =>
		`<button type="button" class="dimina-sensor-panel__tab${tab.id === state.tab ? ' is-active' : ''}" data-tab="${tab.id}">${tab.label}</button>`,
	).join('')
}

function renderBody(root) {
	const body = root.querySelector('[data-role="body"]')
	body.innerHTML = buildDrawerBody(state.tab)
	bindBodyEvents(body)
}

function bindBodyEvents(body) {
	for (const slider of body.querySelectorAll('[data-role="axis"]')) {
		slider.addEventListener('input', () => {
			const key = slider.dataset.key
			const raw = Number(slider.value)
			const axis = AXIS_CONFIG[state.tab].find(a => a.key === key)
			// 罗盘拖动直接落到基准值并清自转相位,滑到哪指针就在哪。
			state.channels[state.tab][key] = raw
			if (state.tab === 'compass') {
				state.spinOffset = 0
			}
			if (axis?.integer) {
				state.channels[state.tab][key] = Math.round(raw)
			}
			injectCurrentFrame()
		})
	}

	// 罗盘表盘:按住拖动,指针跟手(极角 → 方位角)。
	const dial = body.querySelector('[data-role="dial"]')
	if (dial) {
		const dragTo = (event) => {
			const rect = dial.getBoundingClientRect()
			const dx = event.clientX - (rect.left + rect.width / 2)
			const dy = event.clientY - (rect.top + rect.height / 2)
			const angle = (Math.atan2(dx, -dy) * 180 / Math.PI + 360) % 360
			state.channels.compass.direction = Math.round(angle)
			state.spinOffset = 0
			const slider = body.querySelector('.dimina-sensor-panel__slider[data-key="direction"]')
			if (slider) {
				slider.value = String(Math.round(angle))
			}
			injectCurrentFrame()
		}
		dial.addEventListener('pointerdown', (event) => {
			dial.setPointerCapture?.(event.pointerId)
			dragTo(event)
			const move = (moveEvent) => dragTo(moveEvent)
			const up = () => {
				dial.removeEventListener('pointermove', move)
				dial.removeEventListener('pointerup', up)
				dial.removeEventListener('pointercancel', up)
			}
			dial.addEventListener('pointermove', move)
			dial.addEventListener('pointerup', up)
			dial.addEventListener('pointercancel', up)
		})
	}

	for (const button of body.querySelectorAll('[data-action="shake"]')) {
		button.addEventListener('click', () => {
			// 随机脉冲序列 ~700ms,期间加速度计被脉冲驱动,结束后回落滑杆基准值。
			state.shakeUntil = performance.now() + 700
			startLoopIfNeeded()
		})
	}
	for (const button of body.querySelectorAll('[data-action="flipGravity"]')) {
		button.addEventListener('click', () => {
			const z = state.channels.accelerometer
			z.z = Number((-z.z).toFixed(2))
			const slider = body.querySelector('.dimina-sensor-panel__slider[data-key="z"]')
			if (slider) {
				slider.value = String(z.z)
			}
			injectCurrentFrame()
		})
	}
	for (const toggle of body.querySelectorAll('[data-toggle="autoRotate"]')) {
		toggle.addEventListener('change', () => {
			state.autoRotate = toggle.checked
			if (!state.autoRotate) {
				state.spinOffset = 0
			}
			startLoopIfNeeded()
		})
	}
}

function injectCurrentFrame() {
	// 面板开着时循环本来就会带出最新值;关着(理论上不可达,按钮只在抽屉里)也兜底注入一次。
	if (!state.open) {
		for (const [channel, payload] of Object.entries(currentPayloads(performance.now(), 0))) {
			injectSensorSample(channel, payload)
		}
	}
}

function setOpen(root, open) {
	state.open = open
	setSensorPanelActive(open)
	root.classList.toggle('is-open', open)
	const fab = root.querySelector('[data-role="fab"]')
	fab?.setAttribute('aria-expanded', String(open))
	if (open) {
		state.lastTick = 0
		if (!state.frame) {
			state.frame = requestAnimationFrame(tick)
		}
	}
	else if (state.frame) {
		cancelAnimationFrame(state.frame)
		state.frame = 0
	}
}

function mountPanel() {
	const root = document.createElement('div')
	root.className = 'dimina-sensor-panel'
	root.innerHTML = `
		<button type="button" class="dimina-sensor-panel__fab" data-role="fab" aria-expanded="false" title="传感器调试面板">
			${FAB_ICON}
		</button>
		<div class="dimina-sensor-panel__drawer" data-role="drawer">
			<div class="dimina-sensor-panel__header">
				<span class="dimina-sensor-panel__title">传感器调试</span>
				<code class="dimina-sensor-panel__hint">?sensorPanel=1</code>
				<button type="button" class="dimina-sensor-panel__close" data-role="close" aria-label="收起">✕</button>
			</div>
			<div class="dimina-sensor-panel__tabs" data-role="tabs"></div>
			<div class="dimina-sensor-panel__body" data-role="body"></div>
			<label class="dimina-sensor-panel__toggle dimina-sensor-panel__toggle--global">
				<input type="checkbox" data-toggle-global="wiggle"><span>自动摆动（当前通道正弦波动）</span>
			</label>
		</div>`
	document.body.appendChild(root)
	panelRoot = root

	renderTabs(root)
	renderBody(root)

	root.querySelector('[data-role="fab"]').addEventListener('click', () => setOpen(root, !state.open))
	root.querySelector('[data-role="close"]').addEventListener('click', () => setOpen(root, false))

	root.querySelector('[data-role="tabs"]').addEventListener('click', (event) => {
		const tab = event.target.closest('[data-tab]')
		if (!tab || tab.dataset.tab === state.tab) {
			return
		}
		state.tab = tab.dataset.tab
		renderTabs(root)
		renderBody(root)
	})
	for (const toggle of root.querySelectorAll('[data-toggle-global="wiggle"]')) {
		toggle.addEventListener('change', () => {
			state.wiggle = toggle.checked
			startLoopIfNeeded()
		})
	}
}

function startLoopIfNeeded() {
	if (state.open && !state.frame && panelRoot) {
		state.lastTick = 0
		state.frame = requestAnimationFrame(tick)
	}
}

/**
 * 容器入口调用:?sensorPanel=1 时把面板挂到 document.body。
 * 参数不满足时是纯空操作(不引样式副作用之外的东西进关键路径)。
 */
export function mountSensorPanelIfRequested(searchParams) {
	if (searchParams?.get('sensorPanel') !== '1') {
		return
	}
	mountPanel()
	// URL 直开时抽屉默认收起,点 FAB 展开;不自动展开是为了不挡验收页第一眼。
}
