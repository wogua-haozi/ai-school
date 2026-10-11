<template>
  <!-- 底部导航（图1）：白底圆角顶、四角 Tab + 中央「记录」凸起按钮 -->
  <nav class="tabbar" aria-label="主导航">
    <RouterLink to="/" class="tab" :class="{ on: tab === 'home' }">
      <van-icon :name="tab === 'home' ? 'wap-home' : 'wap-home-o'" /><span>首页</span>
    </RouterLink>
    <RouterLink to="/class" class="tab" :class="{ on: tab === 'class' }">
      <van-icon :name="tab === 'class' ? 'friends' : 'friends-o'" /><span>班级</span>
    </RouterLink>
    <button class="tab fab-tab" type="button" aria-label="快捷记录" @click="sheetOpen = true">
      <span class="fab"><van-icon name="plus" /></span><span class="fab-label">记录</span>
    </button>
    <RouterLink to="/notice" class="tab" :class="{ on: tab === 'notice' }">
      <span v-if="running" class="badge">{{ running > 99 ? '99+' : running }}</span>
      <van-icon name="underway-o" /><span>生成中心</span>
    </RouterLink>
    <RouterLink to="/mine" class="tab" :class="{ on: tab === 'mine' }">
      <van-icon :name="tab === 'mine' ? 'manager' : 'manager-o'" /><span>我的</span>
    </RouterLink>
  </nav>

  <!-- 中央＋号：快捷记录菜单（拍照记录·微光时刻排位第一） -->
  <van-action-sheet
    v-model:show="sheetOpen" cancel-text="取消" :round="true" class="record-sheet"
    :actions="actions" @select="onSelect"
  />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAiTasksStore } from '../stores/aiTasks'
import { useAuthStore } from '../stores/auth'
import type { ActionSheetAction } from 'vant'

defineProps<{ tab: 'home' | 'class' | 'notice' | 'mine' }>()

const router = useRouter()
const store = useAiTasksStore()
const auth = useAuthStore()
const running = computed(() => store.runningCount)

const sheetOpen = ref(false)
// 批49：成绩入口按成绩可见门过滤（教师/班主任不再出现）
const actions = computed<ActionSheetAction[]>(() => [
  { name: '拍照记录', subname: '微光时刻 · 随手拍', icon: 'photograph' },
  { name: '微光瞬间', subname: '班级微光照片墙', icon: 'photo-o' },
  { name: '素养评价', subname: '课堂/作业表现', icon: 'edit' },
  ...(auth.scoreAccess ? [{ name: '成绩录入', subname: '学科成绩', icon: 'bar-chart-o' }] : []),
  { name: '课程记录', subname: '扬长课程活动', icon: 'flag-o' },
  { name: '荣誉记录', subname: '获奖证书', icon: 'medal-o' },
])
const routes: Record<string, string> = { '拍照记录': '/moment/new', '微光瞬间': '/moment', '素养评价': '/evaluate', '成绩录入': '/scores', '课程记录': '/activity', '荣誉记录': '/honor' }

function onSelect(action: ActionSheetAction) {
  sheetOpen.value = false
  router.push(routes[action.name])
}
</script>

<style scoped>
.tabbar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 200;
  display: flex; align-items: stretch;
  background: rgba(255, 255, 255, .96); backdrop-filter: blur(12px);
  border-top: 1px solid var(--app-card-border);
  padding-bottom: var(--sab);
}
/* 平板限宽居中（与 .app-page 同步 720px） */
@media (min-width: 600px) {
  .tabbar { left: 50%; transform: translateX(-50%); width: 720px; border-radius: 18px 18px 0 0; margin-bottom: 0; }
}
.tab { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  padding: 8px 0 6px; text-decoration: none; color: var(--app-text-3); font-size: 10px;
  background: none; border: none; -webkit-tap-highlight-color: transparent; }
.tab .van-icon { font-size: 22px; }
.tab.on { color: var(--app-blue); font-weight: 600; }
.tab span { line-height: 1; }
/* 中央凸起「记录」按钮（图1） */
.fab-tab { position: relative; }
.fab { display: flex; align-items: center; justify-content: center;
  width: 52px; height: 52px; margin-top: -26px; border-radius: 50%;
  background: linear-gradient(150deg, #8C1D23, #A8232B);
  color: #fff; box-shadow: 0 6px 16px rgba(140, 29, 35, .35); }
.fab .van-icon { font-size: 24px; font-weight: 700; }
.fab-label { margin-top: 3px; }
/* 通知角标 */
.badge { position: absolute; top: 4px; right: 50%; transform: translateX(20px);
  min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
  background: #EF4444; color: #fff; font-size: 10px; line-height: 16px; text-align: center; }
/* 快捷菜单图标颜色对齐主蓝 */
.record-sheet :deep(.van-action-sheet__item .van-icon) { color: var(--app-blue); }
</style>
