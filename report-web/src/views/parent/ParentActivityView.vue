<template>
  <div class="app-page p-activity">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 批72d：家长只读扬长课程（含课程视频外链跳转）；录课程/封面/视频由教师端管理员操作 -->
    <div class="cats">
      <button v-for="c in ['全部', ...CATS]" :key="c" class="app-chip cat" type="button"
        :class="{ on: cat === c }" @click="cat = c">{{ c }}</button>
    </div>

    <div class="app-sec">{{ cat === '全部' ? '全部课程' : cat }} · {{ filtered.length }} 个</div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <div v-else-if="!filtered.length" class="app-card empty">暂无{{ cat === '全部' ? '' : cat }}课程记录</div>

    <article v-for="(a, i) in filtered" :key="a.id" class="app-card item" :class="texAt(base + i)">
      <div class="cover" :class="texAt(base + i)" @click="preview(a.id)">
        <img v-if="covers[a.id]" :src="covers[a.id]" alt="">
        <span v-else class="no-cover">暂无封面</span>
        <span class="type app-chip">{{ a.type }}</span>
      </div>
      <div class="body">
        <h2>{{ a.title }}</h2>
        <p class="meta">
          <template v-if="a.startTime">{{ fmtTime(a.startTime) }}</template>
          <template v-if="a.place"> · {{ a.place }}</template>
        </p>
        <p v-if="a.intro" class="intro">{{ a.intro }}</p>
        <van-button v-if="a.videoUrl" round size="small" type="primary" class="watch"
          @click="openVideo(a)">观看课程视频</van-button>
      </div>
    </article>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showImagePreview } from 'vant'
import { api, fetchBlob } from '../../api/http'
import { texAt } from '../../utils/tex'

/** 与后端 ActivityController.CATEGORIES / 教师端 ActivityView 一致 */
const CATS = ['德育课程', '扬长选修', '体艺特训队', '跨学科学习', '做中学', '社会实践', '其他']

interface Activity { id: number; title: string; type?: string; startTime?: string; place?: string; intro?: string; videoUrl?: string }

const rows = ref<Activity[]>([])
const covers = ref<Record<number, string>>({})
const cat = ref('全部')
const loading = ref(true)
const refreshing = ref(false)

// 批52③ 纹理池：本页基准取 7（页内仅封面垫图一类静态位）
const base = 7
const filtered = computed(() =>
  cat.value === '全部' ? rows.value : rows.value.filter((a) => a.type === cat.value),
)

function fmtTime(t: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

async function load() {
  loading.value = true
  try {
    rows.value = await api<Activity[]>('/api/activity/list')
    // 封面走带 JWT 的二进制接口（任意登录可看），转 object URL；无封面的用纹理垫图
    for (const a of rows.value) {
      if (a.id in covers.value) continue
      try {
        covers.value[a.id] = ''
        const blob = await fetchBlob(`/api/activity/${a.id}/cover`)
        covers.value[a.id] = URL.createObjectURL(blob)
      } catch { /* 封面未上传，保持空串走垫图 */ }
    }
  } finally {
    loading.value = false
  }
}

async function reload() {
  try { await load() } finally { refreshing.value = false }
}

function preview(id: number) {
  if (covers.value[id]) showImagePreview({ images: [covers.value[id]] })
}

/** 外链视频（腾讯视频/B站等）：新窗打开由平台播放器承接，不占本校服务器带宽 */
function openVideo(a: Activity) {
  window.open(a.videoUrl, '_blank', 'noopener')
}

onMounted(load)
</script>

<style scoped>
/* C 风格只读列表：复用全局 .app-page/.app-card/.app-sec/.app-chip，点缀色 C 令牌 */
.cats { display: flex; gap: 8px; padding: 14px 2px 0; flex-wrap: wrap; }
.cat { border: none; cursor: pointer; padding: 5px 14px; background: #fff; color: var(--app-text-2); }
.cat.on { background: var(--shine-red-soft); color: var(--shine-red); font-weight: 600; }

.empty { text-align: center; color: var(--app-text-2); font-size: 13px; padding: 26px 0; }

.item { padding: 0; overflow: hidden; margin-bottom: 12px; }
.cover { position: relative; height: 150px; background-size: cover; background-position: center; }
.cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.no-cover { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: rgba(255,255,255,.85); text-shadow: 0 1px 2px rgba(10,22,60,.4); }
.type { position: absolute; left: 10px; top: 10px; background: rgba(255,255,255,.92);
  color: var(--shine-navy); font-weight: 600; }

.body { padding: 12px 14px 14px; }
.body h2 { margin: 0; font-size: 16px; font-weight: 700; color: var(--app-text-1); }
.meta { margin: 6px 0 0; font-size: 12px; color: var(--app-text-3); }
.intro { margin: 8px 0 0; padding: 8px 10px; font-size: 13px; line-height: 1.6;
  color: var(--app-text-2); background: var(--shine-bg); border-radius: 8px; }
.watch { margin-top: 10px; }
</style>
