<template>
  <div class="app-page p-content-detail">
    <van-skeleton v-if="loading" :row="8" style="padding: 14px" />

    <template v-else-if="item">
      <!-- 标题卡 -->
      <div class="app-card head tex-36">
        <h1>{{ item.title }}</h1>
        <p class="meta">
          {{ fmtTime(item.publishTime) }}
          <span v-if="item.scope === 'CLASS'" class="app-chip cls">{{ item.className ?? '本班' }}</span>
          <span v-else-if="item.scope === 'GRADE'" class="app-chip cls">{{ item.gradeName || '年级' }}</span>
          <span v-else-if="item.scope === 'STAFF'" class="app-chip cls">教师</span>
        </p>
      </div>

      <!-- 封面大图 -->
      <img v-if="coverSrc" class="app-card cover" :src="coverSrc" alt="" />

      <!-- 正文（宣纸底，保留换行） -->
      <div v-if="item.content" class="app-card body-text tex-37">
        <p>{{ item.content }}</p>
      </div>

      <!-- 外链视频（App 内点开走系统浏览器；视频文件不直传服务器） -->
      <a v-if="item.videoUrl" class="app-card video-link tex-01" :href="item.videoUrl" target="_blank" rel="noopener">
        <span class="v-icon">▶</span>
        <span class="v-text">观看视频<span class="v-sub">（在浏览器中打开）</span></span>
      </a>

      <!-- 批66② 校园通知回执（发布人/管理员）：已读进度 + 未读名单 + 删除 -->
      <div v-if="stats" class="app-card stats tex-02">
        <div class="stats-line">
          <b :class="{ all: stats.read === stats.total }">{{ stats.read }} / {{ stats.total }}</b>
          <span>老师已读{{ stats.total ? `（${Math.round((stats.read / stats.total) * 100)}%）` : '' }}</span>
        </div>
        <div v-if="stats.unread.length" class="unread">
          <span class="sec">未读（{{ stats.unread.length }}）：</span>
          <span v-for="(u, i) in stats.unread" :key="i" class="u">{{ u.name }}</span>
        </div>
        <p v-else class="all-read">全部已读 🎉</p>
        <van-button size="small" round plain type="danger" block style="margin-top: 12px" @click="removeMine">
          删除这条通知
        </van-button>
      </div>
    </template>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showConfirmDialog } from 'vant'
import { api, fetchBlob } from '../../api/http'
import { useAuthStore } from '../../stores/auth'

/* 内容详情：通知公告/扬长课程共用（type 由接口数据自带）；
   批37 教师端双轨：家长走 /api/parent（含已读回执），教师走 /api/content/notices；
   批66② 教师打开校园通知打回执；发布人/管理员看回执统计并可删除 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isParent = auth.role === 'PARENT'
const item = ref<any>(null)
const coverSrc = ref('')
const loading = ref(true)
const stats = ref<{ read: number; total: number; unread: { name: string }[] } | null>(null)

function fmtTime(t?: string | null) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

onMounted(async () => {
  try {
    item.value = await api<any>(isParent ? `/api/parent/contents/${route.params.id}` : `/api/content/notices/${route.params.id}`)
    // 已读回执：家长打点（批9 家长统计口径）；教师打校园通知点（批66② 教师回执口径）
    const campus = item.value.type === 'NOTICE'
      && (item.value.scope === 'STAFF' || item.value.scope === 'GRADE')
    if (item.value.type === 'NOTICE' && isParent) {
      api(`/api/parent/notice/${route.params.id}/read`, { method: 'POST' }).catch(() => {})
    } else if (campus) {
      api(`/api/content/notice/${route.params.id}/read`, { method: 'POST' }).catch(() => {})
    }
    // 发布人/管理员：拉回执统计
    if (campus && (item.value.mine || auth.role === 'ADMIN')) {
      stats.value = await api(`/api/content/notice/${route.params.id}/read-stats`).catch(() => null)
    }
    if (item.value.coverUrl) {
      try {
        const blob = await fetchBlob(`/api/content/file/${item.value.id}`)
        coverSrc.value = URL.createObjectURL(blob)
      } catch { /* 封面缺失不阻塞详情 */ }
    }
  } finally {
    loading.value = false
  }
})

async function removeMine() {
  await showConfirmDialog({
    title: '删除通知',
    message: '删除后老师端立即不可见，已读记录一并清除，确定？',
  })
  await api(`/api/content/notice/${route.params.id}`, { method: 'DELETE' })
  showSuccessToast('已删除')
  router.replace('/notices')
}
</script>

<style scoped>
/* C 风格页面：结构复用第一版全局类 */
.head { padding: 16px 16px 12px; }
.head h1 { margin: 0; font-size: 19px; font-weight: 700; color: var(--app-text-1); line-height: 1.5; }
.meta { margin: 8px 0 0; font-size: 12px; color: var(--app-text-3);
  display: flex; align-items: center; gap: 8px; }
.cls { color: var(--shine-navy); background: #fff; border: 1px solid var(--shine-line); }

.cover { display: block; width: 100%; padding: 0; border-radius: var(--app-card-radius, 14px); }

.body-text { padding: 14px 16px; background: var(--shine-bg); }
.body-text p { margin: 0; font-size: 14px; line-height: 1.9; color: var(--app-text-1);
  white-space: pre-wrap; word-break: break-word; }

.video-link { display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  text-decoration: none; margin-top: 12px; }
.v-icon { flex: none; width: 40px; height: 40px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: var(--shine-gradient); color: var(--shine-gold); font-size: 15px; }
.v-text { font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.v-sub { font-size: 11px; font-weight: 400; color: var(--app-text-3); margin-left: 6px; }

/* 批66② 回执卡 */
.stats { margin-top: 12px; padding: 14px 16px; }
.stats-line { display: flex; align-items: baseline; gap: 10px; }
.stats-line b { font-size: 24px; color: #B8860B; }
.stats-line b.all { color: #0D9467; }
.stats-line span { font-size: 13px; color: var(--app-text-3); }
.unread { margin-top: 10px; font-size: 12px; line-height: 2; color: var(--app-text-3); }
.sec { color: var(--app-text-2); }
.u { display: inline-block; margin: 0 8px 0 0; padding: 1px 10px; border-radius: 999px;
  background: #F5F5F5; color: var(--app-text-2); }
.all-read { margin: 12px 0 4px; text-align: center; color: #0D9467; font-size: 13px; }
</style>
