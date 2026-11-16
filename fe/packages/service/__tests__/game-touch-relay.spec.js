import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { pauseBackgroundWork, resumeBackgroundWork } from '../src/core/background-scheduler.js'
// env 侧装好 BackgroundScheduler 并注册 service 全局——gameTouch 的判别底座（对照 app-events.spec）
import '../src/core/env'
// 捕获 Service 派发处交给游戏的最终事件对象（__svcAt/__svcWait 埋点 + 未知字段透传）
vi.mock('../src/core/game-events.js', () => ({ emitGameTouch: vi.fn() }))
// 引入 Service 入口：注册 gameTouch 派发处埋点供第三条用例断言
import '../src/index.js'
import { emitGameTouch } from '../src/core/game-events.js'
import message from '../src/core/message.js'

describe('gameTouch relay', () => {
  beforeEach(() => {
    globalThis.DiminaServiceBridge.publish = vi.fn()
    globalThis.DiminaServiceBridge.invoke = vi.fn()
  })

  afterEach(() => {
    resumeBackgroundWork()
    vi.clearAllMocks()
  })

  it('delivers gameTouch immediately even while background work is paused', async () => {
    pauseBackgroundWork()
    const touches = []
    message.on('gameTouch', body => touches.push(body))
    const body = {
      eventType: 'touchstart',
      touches: [{ identifier: 0, x: 12, y: 34 }],
      changedTouches: [{ identifier: 0, x: 12, y: 34 }],
    }
    message.handleMsg({ type: 'gameTouch', body })
    // 修复点：gameTouch 在 controlMessages 里与 rAF tick 同级，业务积压/暂停时仍同步投递
    expect(touches).toEqual([body])

    // 对照组：普通业务消息在暂停期不得同步投递（调度器自身行为正确）
    const business = []
    message.on('gameTouchBusinessProbe', b => business.push(b))
    message.handleMsg({ type: 'gameTouchBusinessProbe', body: { tick: 1 } })
    expect(business).toEqual([])

    resumeBackgroundWork()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(business).toEqual([{ tick: 1 }])
  })

  it('delivers every touch phase immediately and in order under render pressure', async () => {
    pauseBackgroundWork()
    const phases = []
    message.on('gameTouch', body => phases.push(body.eventType))
    for (const eventType of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
      message.handleMsg({ type: 'gameTouch', body: { eventType, touches: [], changedTouches: [] } })
    }
    // 四个相位全部即时到达且顺序保持（不进 background 队列、不乱序）
    expect(phases).toEqual(['touchstart', 'touchmove', 'touchend', 'touchcancel'])
  })

  it('stamps __gtAt at handleMsg entry and __svcAt/__svcWait at the gameTouch dispatch site', () => {
    // P4 埋点源码化：handleMsg 入口记 __gtAt（message.js），派发处补 __svcAt/__svcWait（index.js）
    const body = { eventType: 'touchstart', touches: [], changedTouches: [], __natWall: 12345 }
    message.handleMsg({ type: 'gameTouch', body })
    expect(typeof globalThis.__gtAt).toBe('number')
    expect(emitGameTouch).toHaveBeenCalledWith('touchstart', expect.objectContaining({
      __svcAt: expect.any(Number),
      __natWall: 12345, // 契约：不剥未知字段——原生路径附带的 __natWall 原样透传给游戏回调
    }))
    const forwarded = emitGameTouch.mock.calls.at(-1)[1]
    expect(forwarded.__svcWait).toBe(forwarded.__svcAt - globalThis.__gtAt)
  })
})
