import { describe, expect, it, vi } from 'vitest'
import viteConfig from '../vite.config'

describe('Vite 局域网开发代理', () => {
  it('只允许 Cloudflare Quick Tunnel 的子域名访问，不放开任意 Host', () => {
    expect(viteConfig.server?.allowedHosts).toEqual(['.trycloudflare.com'])
    expect(viteConfig.server?.allowedHosts).not.toBe(true)
  })

  it('转发 API 时移除手机浏览器 Origin，避免后端把同源代理误判成跨域请求', () => {
    const proxyConfig = viteConfig.server?.proxy?.['/api']
    expect(proxyConfig).toBeTypeOf('object')

    let proxyRequestListener: ((request: { removeHeader(name: string): void }) => void) | undefined
    const proxy = {
      on: vi.fn((event: string, listener: typeof proxyRequestListener) => {
        if (event === 'proxyReq') proxyRequestListener = listener
      }),
    }

    if (typeof proxyConfig === 'object' && proxyConfig.configure) {
      proxyConfig.configure(proxy as never, {} as never)
    }

    expect(proxyRequestListener).toBeTypeOf('function')
    const removeHeader = vi.fn()
    proxyRequestListener?.({ removeHeader })
    expect(removeHeader).toHaveBeenCalledWith('origin')
  })
})
