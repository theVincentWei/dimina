import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import router from '../src/core/router.js'
import { createCanvas, createOffscreenCanvas } from '../src/api/core/ui/canvas/index.js'
import { resetMiniGameCanvas } from '../src/api/core/ui/canvas/canvas-node.js'

let previousInvoke
const canvases = []
beforeEach(() => {
	resetMiniGameCanvas()
	router.setInitId('frame-presentation-test')
	previousInvoke = globalThis.DiminaServiceBridge.invoke
	globalThis.DiminaServiceBridge.canvasSyncSupported = true
	globalThis.DiminaServiceBridge.invoke = vi.fn(() => ({ contexts: {}, typedArrays: [] }))
	globalThis.DiminaServiceBridge.publish = vi.fn()
})
afterEach(() => {
	for (const canvas of canvases.splice(0)) canvas.dispose()
	globalThis.DiminaServiceBridge.onMessage({ type: 'pageUnload', body: { bridgeId: 'frame-presentation-test' } })
	globalThis.DiminaServiceBridge.invoke = previousInvoke
	delete globalThis.DiminaServiceBridge.canvasSyncSupported
})

it.each([false, true])('publishes a completed screen frame after the last query consumed its drawing operations (offscreen: %s)', async (offscreen) => {
	const canvas = offscreen ? createOffscreenCanvas({ type: 'webgl' }) : createCanvas()
	canvases.push(canvas)
	const gl = canvas.getContext('webgl')
	await Promise.resolve()
	globalThis.DiminaServiceBridge.publish.mockClear()
	gl.clear(gl.COLOR_BUFFER_BIT)
	gl.getError()
	expect(globalThis.DiminaServiceBridge.invoke.mock.calls.at(-1)[0].body.params.operations)
		.toContainEqual(expect.objectContaining({ op: 'contextCall', method: 'clear' }))
	expect(globalThis.DiminaServiceBridge.publish).not.toHaveBeenCalled()
	await Promise.resolve()
	const packets = globalThis.DiminaServiceBridge.publish.mock.calls.map(([, message]) => message)
	if (offscreen) expect(packets).toEqual([])
	else expect(packets).toEqual([expect.objectContaining({
		// [rperf] 空批次以空字符串 operationsCompact 过桥（无指令时的 present 通知帧）
		body: expect.objectContaining({ name: 'canvasNodeFlush', params: expect.objectContaining({ operationsCompact: '' }) }),
	})])
	await Promise.resolve()
	expect(globalThis.DiminaServiceBridge.publish.mock.calls).toHaveLength(offscreen ? 0 : 1)
})
