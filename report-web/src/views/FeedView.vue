<template>
  <div class="app-page feedview">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 头区（图2） -->
    <div class="app-hero hero">
      <h1>成长记录</h1>
      <p>记录学生成长的每一个瞬间</p>
    </div>

    <!-- 筛选 chips -->
    <div class="chips">
      <button v-for="c in cats" :key="c.k" class="chip" :class="{ on: cat === c.k }" type="button"
        @click="cat = c.k">{{ c.label }}</button>
    </div>

    <!-- 卡片流（图2 样式） -->
    <div v-if="!loaded" class="cards">
      <div v-for="i in 2" :key="i" class="app-card card" :class="tex(i)">
        <van-skeleton title :row="3" class="card-skeleton" />
      </div>
    </div>
    <div v-else-if="filtered.length" class="cards">
      <div v-for="(f, i) in filtered" :key="i" class="app-card card" :class="tex(i)"
        @click="f.studentId && $router.push(`/student/${f.studentId}`)">
        <div class="c-head">
          <span class="c-title">{{ cardTitle(f) }}</span>
          <span class="app-chip" :class="chipClass(f.type)">{{ chipLabel(f) }}</span>
        </div>
        <p class="c-meta">{{ f.className || '全校' }}<template v-if="f.typeLabel"> · {{ f.typeLabel }}</template></p>
        <p class="c-body">{{ f.content || f.title }}</p>
        <div v-if="f.photoUrl" class="c-photo">
          <MomentPhoto :url="f.photoUrl" @tap="(src) => photoPreview?.open([src])" />
        </div>
        <div class="c-foot">
          <span class="c-date">{{ fullTime(f.time) }}</span>
          <span v-if="f.teacherName" class="c-from">来自：{{ f.teacherName }}</span>
        </div>
      </div>
    </div>
    <div v-else class="app-card tex-13 empty">
      <van-empty image-size="88" description="该分类下还没有记录" />
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
    <PhotoPreview ref="photoPreview" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/http'
import { fullTime } from '../utils/fmt'
import { texAt } from '../utils/tex'

// 批52③ 37 槽轮换池：本页基准 12、静态位 1，骨架/列表轮换续在 13
const tex = (i: number) => texAt(13 + i)
import MomentPhoto from '../components/MomentPhoto.vue'
import PhotoPreview from '../components/PhotoPreview.vue'

interface FeedItem {
  type: string; title?: string; content?: string; teacherName?: string; time?: string
  studentId?: number; studentName?: string; className?: string; typeLabel?: string
  photoUrl?: string; studentNames?: string
}

const feed = ref<FeedItem[]>([])
const cat = ref('全部')
const refreshing = ref(false)
const loaded = ref(false)
const photoPreview = ref<InstanceType<typeof PhotoPreview>>()
const cats = [
  { k: '全部', label: '全部' },
  { k: '微光', label: '微光' },
  { k: '评价', label: '评价' },
  { k: '寄语', label: '寄语' },
  { k: '活动', label: '活动' },
  { k: '荣誉', label: '荣誉' },
]
const filtered = computed(() => (cat.value === '全部' ? feed.value : feed.value.filter((f) => f.type === cat.value)))

function cardTitle(f: FeedItem) {
  if (f.type === '寄语') return `${f.studentName ?? ''}的班主任寄语`
  if (f.type === '荣誉') return `${f.studentName ?? ''}获得「${f.title ?? ''}」`
  if (f.type === '活动') return f.title ?? ''
  if (f.type === '微光') return `${f.studentNames || '班级'}的微光时刻`
  return `${f.studentName ?? ''} · ${f.title ?? ''}`
}
function chipClass(type: string) {
  return { 评价: 'c-eval', 荣誉: 'c-honor', 寄语: 'c-comment', 活动: 'c-act', 微光: 'c-moment' }[type] ?? ''
}
function chipLabel(f: FeedItem) {
  if (f.type === '荣誉') return '荣誉时刻'
  if (f.type === '活动') return f.typeLabel || '校园活动'
  if (f.type === '寄语') return '班主任寄语'
  if (f.type === '微光') return f.title || '微光时刻'
  return '日常表现'
}

async function load() {
  try {
    feed.value = await api<FeedItem[]>('/api/feed?limit=50')
  } catch { /* 空态兜底 */ }
  loaded.value = true
}

/** 下拉刷新：重拉成长记录流 */
async function reload() {
  try { await load() } finally { refreshing.value = false }
}

onMounted(load)
</script>

<style scoped>
.hero { padding-bottom: 18px; }
.hero h1 { margin: 4px 0 2px; font-size: 21px; font-weight: 800; }
.hero p { margin: 0; font-size: 12px; color: rgba(255,255,255,.65); }

.chips { display: flex; gap: 8px; margin: 14px 2px; overflow-x: auto; scrollbar-width: none; }
.chips::-webkit-scrollbar { display: none; }
.chip { flex: none; padding: 6px 16px; border: none; border-radius: 999px;
  background: #fff; color: var(--app-text-2); font-size: 13px; cursor: pointer;
  box-shadow: var(--app-shadow); -webkit-tap-highlight-color: transparent; }
.chip.on { background: var(--app-blue); color: #fff; font-weight: 600; }

.cards { display: flex; flex-direction: column; gap: 12px; }
.card { cursor: default; }
.card-skeleton { padding: 6px 0; }
.card:hover { box-shadow: var(--app-shadow-float); }
.c-head { display: flex; align-items: center; gap: 8px; }
.c-title { flex: 1; font-size: 15px; font-weight: 700; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.c-meta { margin: 5px 0 0; font-size: 12px; color: var(--app-text-3); }
.c-body { margin: 8px 0; font-size: 14px; line-height: 1.6; color: var(--app-text-2);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.c-foot { display: flex; align-items: center; justify-content: space-between;
  font-size: 11px; color: var(--app-text-3); }
.empty { padding: 10px 0 0; }
.c-eval { background: rgba(168,35,43,.08); color: #A8232B; }
.c-honor { background: #FBF3DF; color: #B07A1C; }
.c-comment { background: #E8F6EF; color: #0D9467; }
.c-act { background: #F3EAFE; color: #7C4DD8; }
.c-moment { background: #FDEEE2; color: #EA580C; }
.c-photo { height: 170px; margin: 8px 0; border-radius: 10px; overflow: hidden; cursor: zoom-in; }
</style>
