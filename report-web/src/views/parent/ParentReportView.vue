<template>
  <div class="app-page p-report">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 多孩切换（同微光时刻形态） -->
    <div v-if="children.length > 1" class="kids">
      <button v-for="c in children" :key="c.studentId" class="app-chip kid" type="button"
        :class="{ on: c.studentId === curId }" @click="switchKid(c.studentId)">{{ c.name }}</button>
    </div>

    <van-skeleton v-if="loading" :row="4" style="padding: 14px" />

    <!-- 批42 分期归档：按学期分组列往期报告（每期只显最新版；期中/期末分列） -->
    <template v-else-if="groups.length">
      <div v-for="(g, gi) in groups" :key="g.termId" class="term">
        <div class="app-sec">{{ g.termName || '往期' }}</div>
        <div v-for="(it, k) in g.items" :key="it.reportId" class="app-card tl r-card" :class="tex(gi * 8 + k)">
          <span class="r-icon"><van-icon name="description" /></span>
          <div class="r-info">
            <h1>{{ titleOf(it.scopeType, it.period) }}</h1>
            <p v-if="it.genTime">生成于 {{ fmtTime(it.genTime) }}</p>
          </div>
          <van-button round size="small" type="primary" :loading="opening === it.reportId" loading-text="打开中…"
            @click="open(it)">查看</van-button>
        </div>
      </div>
    </template>

    <!-- 空态：家长版未生成（班主任触发生成后自动出双版） -->
    <div v-else class="app-card empty">
      <van-icon name="notes-o" class="e-icon" />
      <p class="e-title">报告尚未生成</p>
      <p class="e-tip">请等待班主任生成后再来查看</p>
    </div>

    <!-- 浏览器内嵌预览（安卓 WebView 无 PDF 渲染器，native 走系统面板） -->
    <div v-if="url" class="app-sec">报告预览</div>
    <iframe v-if="url" :src="url" class="frame" title="成长报告预览" />

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { showToast } from 'vant'
import { api, fetchBlob } from '../../api/http'
import { isNative, openFile } from '../../api/nativeShare'
import { texAt } from '../../utils/tex'

// 批52③ 37 槽轮换池：本页基准 31（内层序=外层序×8+内层序，避免跨学期同页撞图）
const tex = (i: number) => texAt(31 + i)

interface Kid { studentId: number; name: string }
interface ReportItem { reportId: number; scopeType: string; period: string; genTime?: string }
interface ReportGroup { termId: number; termName: string; items: ReportItem[] }

/** 批26：学年/在校报告与学期报告同走此入口，标题随类型；批42 学期报告带期次 */
function titleOf(scopeType?: string, period?: string) {
  if (scopeType === 'YEAR') return '学年成长报告'
  if (scopeType === 'SCHOOL') return '在校成长报告'
  return period === 'MID' ? '学期成长报告 · 期中' : '学期成长报告 · 期末'
}

const children = ref<Kid[]>([])
const curId = ref<number>()
const groups = ref<ReportGroup[]>([])
const loading = ref(true)
const refreshing = ref(false)
const opening = ref<number | null>(null)
const url = ref('')
const blob = ref<Blob>()

const curName = computed(() => children.value.find((c) => c.studentId === curId.value)?.name ?? '')

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

async function load() {
  loading.value = true
  try {
    if (curId.value) {
      groups.value = await api<ReportGroup[]>(`/api/parent/children/${curId.value}/report/list`)
      if (!groups.value.length) dropPreview()
    }
  } finally {
    loading.value = false
  }
}

async function reload() {
  try { await load() } finally { refreshing.value = false }
}

function switchKid(id: number) {
  if (id === curId.value) return
  curId.value = id
  dropPreview()
  load()
}

/** 打开报告（含往期）：浏览器=页内 iframe 预览；App=系统面板（选查看器预览/保存到文件） */
async function open(it: ReportItem) {
  if (!curId.value || opening.value !== null) return
  opening.value = it.reportId
  try {
    const b = await fetchBlob(`/api/parent/children/${curId.value}/report/${it.reportId}/file`)
    const label = titleOf(it.scopeType, it.period).replace(' · ', '-')
    if (isNative) {
      blob.value = b
      await openFile(b, `${curName.value || '孩子'}-${label}.pdf`)
    } else {
      dropPreview()
      blob.value = b
      url.value = URL.createObjectURL(b)
    }
  } catch (e: any) {
    showToast(e?.message || '报告打开失败')
  } finally {
    opening.value = null
  }
}

function dropPreview() {
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
}

onMounted(async () => {
  children.value = await api<Kid[]>('/api/parent/children')
  if (children.value.length) curId.value = children.value[0].studentId
  await load()
})
onUnmounted(dropPreview)
</script>

<style scoped>
/* C 风格页面（方案C 新中式）：结构复用第一版全局类，仅覆盖点缀色为 C 令牌 */
.kids { display: flex; gap: 8px; padding: 14px 16px 0; flex-wrap: wrap; }
.kid.on { background: var(--shine-navy); color: #fff; border-color: var(--shine-navy); }

/* 报告卡 */
.term { margin-top: 4px; }
.r-card { display: flex; align-items: center; gap: 12px; margin-top: 10px; padding: 14px 16px; }
.r-icon { flex: none; display: flex; align-items: center; justify-content: center;
  width: 46px; height: 46px; border-radius: 14px;
  background: var(--shine-navy); color: var(--shine-gold); }
.r-icon .van-icon { font-size: 23px; }
.r-info { flex: 1; min-width: 0; }
.r-info h1 { margin: 0; font-size: 15px; font-weight: 700; color: var(--app-text-1); }
.r-info p { margin: 4px 0 0; font-size: 12px; color: var(--app-text-2);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 空态 */
.empty { margin-top: 14px; padding: 44px 16px; text-align: center; }
.e-icon { font-size: 44px; color: var(--app-text-3); opacity: .55; }
.e-title { margin: 12px 0 0; font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.e-tip { margin: 6px 0 0; font-size: 12px; color: var(--app-text-3); }

/* 预览（浏览器形态） */
.frame { width: 100%; height: 72dvh; border: 1px solid var(--app-card-border);
  border-radius: 12px; background: #fff; }

/* C 覆盖：区块竖条换 C 红 */
.p-report .app-sec::before { background: var(--shine-red); }
</style>
