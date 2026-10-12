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
    </template>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, fetchBlob } from '../../api/http'
import { useAuthStore } from '../../stores/auth'

/* 内容详情：通知公告/扬长课程共用（type 由接口数据自带）；
   批37 教师端双轨：家长走 /api/parent（含已读回执），教师走 /api/content/notices */
const route = useRoute()
const isParent = useAuthStore().role === 'PARENT'
const item = ref<any>(null)
const coverSrc = ref('')
const loading = ref(true)

function fmtTime(t?: string | null) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

onMounted(async () => {
  try {
    item.value = await api<any>(isParent ? `/api/parent/contents/${route.params.id}` : `/api/content/notices/${route.params.id}`)
    // 通知已读回执（批9，家长端统计口径）：打开即打点（幂等；失败不阻塞阅读）
    if (isParent && item.value.type === 'NOTICE') {
      api(`/api/parent/notice/${route.params.id}/read`, { method: 'POST' }).catch(() => {})
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
</style>
