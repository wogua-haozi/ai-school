<template>
  <MotionConfig reducedMotion="user">
    <!-- App 形态（tab/keepTab）：主内容区 + 底部导航 -->
    <div v-if="layout === 'tab' || layout === 'keepTab'" class="app-shell">
      <main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
      <AppTabbar :tab="(route.meta.tab as any)" />
    </div>

    <!-- sub 二级/功能页：meta.title 有值挂「返回+标题」导航条；无值（学生详情）仅滚动容器。
         管理页桌面 ≥1024 切侧栏形态（AdminView 自带顶栏/导航），不渲染返回条 -->
    <div v-else-if="layout === 'sub'" class="app-sub" :class="{ admin: route.meta.admin }">
      <header v-if="route.meta.title && !(route.meta.admin && desktop)" class="sub-nav">
        <button class="back" type="button" aria-label="返回" @click="goBack">
          <van-icon name="arrow-left" />
        </button>
        <h1>{{ route.meta.title }}</h1>
      </header>
      <main class="sub-main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <!-- ptab 家长端一级页（PARENT）：C 风格底 + 两 Tab 导航 -->
    <div v-else-if="layout === 'ptab'" class="app-shell shine-shell">
      <main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
      <ParentTabbar :tab="(route.meta.tab as any)" />
    </div>

    <!-- psub 家长二级页：C 风格「返回+标题」导航条（藏蓝底金线） -->
    <div v-else-if="layout === 'psub'" class="app-sub shine-shell">
      <header v-if="route.meta.title" class="shine-nav">
        <button class="back" type="button" aria-label="返回" @click="goBack">
          <van-icon name="arrow-left" />
        </button>
        <h1>{{ route.meta.title }}</h1>
      </header>
      <main class="sub-main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <!-- lhome 领导端（LEADER）/ gshell 门卫端（GUARD）：单页，页面自带头部（无 Tabbar） -->
    <div v-else-if="layout === 'lhome' || layout === 'gshell'" class="app-sub shine-shell">
      <main class="sub-main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <!-- bare：登录页直接渲染 -->
    <router-view v-else />
  </MotionConfig>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MotionConfig } from 'motion-v'
import { showToast } from 'vant'
import { App as CapApp } from '@capacitor/app'
import { useAuthStore } from './stores/auth'
import { useAiTasksStore } from './stores/aiTasks'
import { useNotifyStore } from './stores/notify'
import { useAssetStore } from './stores/asset'
import { isNative } from './api/nativeShare'
import { checkForUpdate } from './utils/appUpdate'
import AppTabbar from './components/AppTabbar.vue'
import ParentTabbar from './components/ParentTabbar.vue'

const auth = useAuthStore()
const aiTasks = useAiTasksStore()
const notify = useNotifyStore()
const asset = useAssetStore()
const router = useRouter()
const route = useRoute()

/** 布局形态由路由 meta 决定：tab/keepTab → Tab 壳；sub → 返回导航壳；bare → 登录页 */
const layout = computed(() => (route.meta.layout as string | undefined) ?? 'bare')

/* 桌面 ≥1024：管理页此形态切侧栏，隐藏移动返回条（断点与 AdminView/style.css 三档一致） */
const desktop = ref(window.matchMedia('(min-width: 1024px)').matches)
const mq = window.matchMedia('(min-width: 1024px)')
const onMq = (e: MediaQueryListEvent) => { desktop.value = e.matches }
onMounted(() => mq.addEventListener('change', onMq))
onUnmounted(() => mq.removeEventListener('change', onMq))
/* 素材库远程优先（批35）：启动拉一次已自定义素材清单，失败静默全走内置 */
onMounted(() => { asset.load() })

/** 导航条返回：有上一页则回退，否则（深链直入）回首页 */
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

/* App 硬件返回键：业内惯例「主层级再按一次退出」——能回退先回退（hash 路由与
   WebView 历史同栈），到栈底 2 秒内再按才退出，防误触直接杀掉 App */
if (isNative) {
  let lastBack = 0
  CapApp.addListener('backButton', ({ canGoBack }) => {
    if (canGoBack) { window.history.back(); return }
    const now = Date.now()
    if (now - lastBack < 2200) CapApp.exitApp()
    else { lastBack = now; showToast('再按一次退出') }
  })
}

/* AI 任务轮询随登录态启停（登录即恢复展示后台跑的任务，退出即停并清空）；
   登录后顺带检测 App 更新（有新版本弹窗提示，见 utils/appUpdate.ts）；
   通知未读角标（批29）同步起停（2 分钟一轮） */
watch(() => auth.token, (t) => {
  if (t) {
    aiTasks.start()
    notify.start()
    checkForUpdate().catch(() => { /* 静默：更新检测失败不打扰 */ })
  } else {
    aiTasks.stop()
    notify.stop()
  }
}, { immediate: true })
</script>

<style scoped>
/* App 壳：主滚动区 + 底部导航（固定悬浮，主区留出通行高度） */
.app-shell { height: 100%; height: 100dvh; display: flex; flex-direction: column; background: var(--app-bg); }
.app-main { flex: 1; overflow-y: auto; -webkit-overflow-scrolling: touch;
  padding-bottom: calc(96px + var(--sab)); }  /* 64 tabbar + 32 中央+键凸出高度，免遮列表尾行 */

/* sub 壳：深蓝渐变导航条（垫虚化校园底图，与 hero 同语言）+ 滚动主区 */
.app-sub { height: 100%; height: 100dvh; display: flex; flex-direction: column; background: var(--app-bg); }
.sub-nav {
  position: relative; display: flex; align-items: center; gap: 10px; flex: none;
  padding: calc(8px + var(--sat)) 14px 8px;
  background: var(--app-gradient); color: #fff; overflow: hidden;
  border-bottom: 1px solid rgba(201,138,45,.4);   /* 校门金一线 */
}
.sub-nav::before {                 /* 虚化校园底图（同 .app-hero；批72c 24%→17% 调淡） */
  content: ''; position: absolute; inset: -30px;
  background: var(--campus-bg, url('/campus-bg.jpg')) center 42%/cover no-repeat;
  opacity: .17; filter: blur(8px) saturate(1.15); pointer-events: none;
}
.sub-nav::after {                  /* 右上光斑（同 hero 装饰语言）；C 金光斑 */
  content: ''; position: absolute; top: -60px; right: -45px; width: 190px; height: 190px;
  border-radius: 50%; pointer-events: none;
  background: radial-gradient(closest-side, rgba(201,162,39,.30), rgba(201,162,39,0));
}
.sub-nav > * { position: relative; }
.sub-nav .back { display: flex; align-items: center; justify-content: center; width: 32px; height: 32px;
  border: none; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  font-size: 16px; cursor: pointer; }
.sub-nav h1 { margin: 0; font-size: 17px; font-weight: 700; letter-spacing: 1px; }
.sub-main { flex: 1; overflow-y: auto; -webkit-overflow-scrolling: touch; }

/* 功能页过渡期仍用 Element Plus 组件：除管理页外，EP 主色批6 起对齐 C 操作红
   （管理端保留石实红体系，见 style.css §1） */
.app-sub:not(.admin) {
  --el-color-primary: #A8232B;
  --el-color-primary-light-3: #C2656B;
  --el-color-primary-light-5: #D39195;
  --el-color-primary-light-7: #E5BEC0;
  --el-color-primary-light-8: #EED4D6;
  --el-color-primary-light-9: #F6EBEC;
  --el-color-primary-dark-2: #8C1D23;
}

/* 家长/领导端壳（批1 新增）：宣纸底 + C 风格导航条（第一版 sub-nav 形态：渐变+虚化校园底图+光斑，换 C 藏蓝/金） */
.shine-shell { background: var(--shine-bg); }
.shine-nav {
  position: relative; display: flex; align-items: center; gap: 10px; flex: none;
  padding: calc(8px + var(--sat)) 14px 8px;
  background: var(--shine-gradient); color: #fff; overflow: hidden;
  border-bottom: 1px solid var(--shine-gold);
}
.shine-nav::before {                 /* 虚化校园底图（同 .app-hero；批72c 24%→17% 调淡） */
  content: ''; position: absolute; inset: -30px;
  background: var(--campus-bg, url('/campus-bg.jpg')) center 42%/cover no-repeat;
  opacity: .17; filter: blur(8px) saturate(1.15); pointer-events: none;
}
.shine-nav::after {                  /* 右上光斑（C 金） */
  content: ''; position: absolute; top: -60px; right: -45px; width: 190px; height: 190px;
  border-radius: 50%; pointer-events: none;
  background: radial-gradient(closest-side, rgba(201,162,39,.28), rgba(201,162,39,0));
}
.shine-nav > * { position: relative; }
.shine-nav .back { display: flex; align-items: center; justify-content: center; width: 32px; height: 32px;
  border: none; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  font-size: 16px; cursor: pointer; }
.shine-nav h1 { margin: 0; font-size: 17px; font-weight: 700; letter-spacing: 1px; }
</style>
