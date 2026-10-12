<template>
  <div class="app-page notify">
    <!-- 筛选 + 全部已读 -->
    <div class="app-card tl tex-03 bar">
      <div class="chips">
        <button v-for="c in [{k:'unread',t:`未读${unread ? '(' + unread + ')' : ''}`},{k:'all',t:'全部'}]"
          :key="c.k" class="chip" :class="{ on: filter === c.k }" type="button" @click="switchFilter(c.k)">{{ c.t }}</button>
      </div>
      <button class="read-all" type="button" :disabled="!unread" @click="readAll">全部已读</button>
    </div>

    <!-- 通知列表：点击已读+按 link 直达处理页 -->
    <div class="app-card tex-04 list">
      <van-empty v-if="!rows.length" image-size="72" description="暂无通知" />
      <div v-for="n in rows" :key="n.id" class="nt" :class="{ unread: !n.read }" @click="open(n)">
        <div class="nt-head">
          <span class="tag" :class="tagClass(n.type)">{{ n.typeLabel }}</span>
          <span v-if="!n.read" class="dot" />
          <span class="time">{{ fmtTime(n.createTime) }}</span>
        </div>
        <p class="nt-title">{{ n.title }}</p>
        <p v-if="n.content" class="nt-content">{{ n.content }}</p>
        <span v-if="n.link" class="go">去处理 ›</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast } from 'vant'
import { api } from '../api/http'
import { useNotifyStore } from '../stores/notify'

const router = useRouter()
const notify = useNotifyStore()
const filter = ref('unread')
const rows = ref<any[]>([])
const unread = ref(0)

async function load() {
  const d = await api<{ records: any[]; unread: number }>(`/api/notification/list?filter=${filter.value}&page=1&size=50`)
  rows.value = d.records
  unread.value = d.unread
}

function switchFilter(k: string) {
  filter.value = k
  load()
}

/** 点击：标已读（幂等）→ 有 link 直达处理页（如 /leave 待审批、/p/leave 结果） */
async function open(n: any) {
  if (!n.read) {
    try {
      await api(`/api/notification/${n.id}/read`, { method: 'PUT' })
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
      notify.refresh()
    } catch { /* 已读失败不拦截跳转 */ }
  }
  if (n.link) router.push(n.link)
}

async function readAll() {
  await api('/api/notification/read-all', { method: 'PUT' })
  showSuccessToast('已全部标记已读')
  await load()
  notify.refresh()
}

function tagClass(type: string) {
  if (type === 'ALERT') return 'alert'
  return type?.endsWith('_TODO') ? 'todo' : 'info'
}

function fmtTime(t?: string) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(5, 16)
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; }
.chips { display: flex; gap: 8px; }
.chip { padding: 6px 16px; border: 1px solid var(--app-card-border); border-radius: 999px;
  background: #fff; font-size: 13px; color: var(--app-text-2); cursor: pointer; }
.chip.on { background: var(--shine-gradient, #1B2E6B); border-color: transparent; color: #fff; font-weight: 600; }
.read-all { padding: 6px 14px; border: none; border-radius: 999px; font-size: 13px;
  background: #F5F0E6; color: #8A6D1F; cursor: pointer; }
.read-all:disabled { opacity: .45; cursor: default; }

.list { padding: 4px 14px; }
.nt { padding: 12px 0 10px; border-bottom: 1px dashed var(--app-card-border); cursor: pointer; }
.nt:last-child { border-bottom: none; }
.nt-head { display: flex; align-items: center; gap: 6px; }
.tag { padding: 2px 8px; border-radius: 4px; font-size: 11px; }
.tag.todo { background: #FDF3DF; color: #B8860B; }
.tag.alert { background: #FEECEC; color: #DC2626; }
.tag.info { background: #EDF2FA; color: #3B5B92; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: #E54D42; }
.time { margin-left: auto; font-size: 11px; color: var(--app-text-3); }
.nt-title { margin: 6px 0 2px; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.nt.unread .nt-title { color: var(--app-blue-deep, #1B2E6B); }
.nt-content { margin: 0; font-size: 12px; color: var(--app-text-2); line-height: 1.6; }
.go { display: inline-block; margin-top: 4px; font-size: 12px; color: var(--app-blue); font-weight: 600; }
</style>
