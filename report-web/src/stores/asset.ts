import { defineStore } from 'pinia'
import { TEX_KEYS } from '../utils/tex'

/**
 * 素材库远程优先（批35 学校自治）：启动拉一次 manifest（管理端上传过的素材 key→版本），
 * 消费端 url(key, 内置路径) —— 有自定义用 /api/asset/{key}?v=版本（版本变即破缓存），
 * 没有用内置默认图。CSS 消费（导航条虚化底图/纹理）经 CSS 变量与覆盖样式注入。
 * 拉取失败静默全走内置，不打扰任何页面。
 */
export const useAssetStore = defineStore('asset', {
  state: () => ({
    manifest: {} as Record<string, string>,
    loaded: false,
  }),
  actions: {
    async load() {
      try {
        const r = await fetch('/api/asset/manifest')
        if (r.ok) this.manifest = await r.json()
      } catch { /* 静默：全走内置 */ }
      this.loaded = true
      this.applyOverrides()
    },
    url(key: string, builtin: string) {
      const v = this.manifest[key]
      return v ? `/api/asset/${key}?v=${v}` : builtin
    },
    /** CSS 消费覆盖：hero 底图走根变量 --campus-bg；纹理 .tex-01~37 注入覆盖样式（同特异性后置即胜） */
    applyOverrides() {
      const bg = this.manifest['campus-bg']
      if (bg) document.documentElement.style.setProperty('--campus-bg', `url('/api/asset/campus-bg?v=${bg}')`)
      const texKeys = TEX_KEYS.filter((k) => this.manifest[k])
      let styleEl = document.getElementById('asset-tex-overrides') as HTMLStyleElement | null
      if (!texKeys.length) {
        if (styleEl) styleEl.remove()
        return
      }
      if (!styleEl) {
        styleEl = document.createElement('style')
        styleEl.id = 'asset-tex-overrides'
        document.head.appendChild(styleEl)
      }
      styleEl.textContent = texKeys
        .map((k) => `.${k} { --tex: url('/api/asset/${k}?v=${this.manifest[k]}'); }`)
        .join('\n')
    },
  },
})
