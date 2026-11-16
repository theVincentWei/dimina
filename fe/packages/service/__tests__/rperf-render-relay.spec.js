import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createOffscreenCanvas } from '../src/api/core/ui/canvas/index.js'
import { pauseBackgroundWork, resumeBackgroundWork } from '../src/core/background-scheduler.js'
// env 侧装好 BackgroundScheduler 并注册 service 全局——rperfRender 的判别底座（对照 app-events.spec）
import '../src/core/env'
import message from '../src/core/message.js'
import { setStorageSync } from '@/api/core/storage/index'

vi.mock('@/api/core/storage/index', () => ({ setStorageSync: vi.fn() }))

describe('rperfRender relay', () => {
  beforeEach(() => {
    globalThis.DiminaServiceBridge.publish = vi.fn()
    globalThis.DiminaServiceBridge.invoke = vi.fn()
  })

  afterEach(() => {
    resumeBackgroundWork()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('delivers rperfRender immediately even while background work is paused', async () => {
    pauseBackgroundWork()
    const bodies = []
    message.on('rperfRender', body => bodies.push(body))
    const body = { flush: 3, ops: 30, exec_p50: 1.2, exec_p90: 2.4, exec_max: 3.6 }
    message.handleMsg({ type: 'rperfRender', body })
    // 修复点：rperfRender 在 controlMessages 里，业务积压/暂停时仍同步投递
    expect(bodies).toEqual([body])

    // 对照组：普通业务消息在暂停期不得同步投递（调度器自身行为正确）
    const business = []
    message.on('rperfBusinessProbe', b => business.push(b))
    message.handleMsg({ type: 'rperfBusinessProbe', body: { tick: 1 } })
    expect(business).toEqual([])

    resumeBackgroundWork()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(business).toEqual([{ tick: 1 }])
  })

  it('feeds rperfSvc.lastRender so the next 5s dump writes a non-null summary.rnd', async () => {
    const realNow = Date.now()
    vi.useFakeTimers()
    // fake 时钟拨到远早于模块导入时刻的 lastDump，保证第一次 flush 不触发 dump
    vi.setSystemTime(1)

    const canvas = createOffscreenCanvas({ type: '2d', width: 4, height: 4 })
    const ctx = canvas.getContext('2d')
    const flush = async () => {
      ctx.fillRect(0, 0, 1, 1)
      await Promise.resolve()
      await Promise.resolve()
    }

    await flush()
    expect(setStorageSync).not.toHaveBeenCalled()

    const body = { flush: 7, ops: 700, exec_p50: 1.5, exec_p90: 2.5, exec_max: 3, present_n: 1, present_avg: 0.4, present_max: 0.9, decode_p50: 0.1, decode_p90: 0.2 }
    message.handleMsg({ type: 'rperfRender', body })
    vi.setSystemTime(realNow + 6000)
    await flush()

    expect(setStorageSync).toHaveBeenCalledTimes(1)
    const [storageKey, payload] = setStorageSync.mock.calls[0]
    expect(storageKey).toBe('rperf_kernel')
    const summary = JSON.parse(payload)
    // 核心断言：5s dump 的 rnd 字段落地（修复前恒为 null）
    expect(summary.rnd).toEqual(body)
    expect(summary.svc.flush).toBeGreaterThan(0)
    expect(summary.svc.ops).toBeGreaterThan(0)
  })
})
