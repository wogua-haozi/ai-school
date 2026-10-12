<template>
  <div class="app-page fp">
    <!-- 六维统计（奖项=教师风采已录荣誉聚合） -->
    <div class="app-card tex-19 stats">
      <div v-for="s in SIX" :key="s.key" class="stat" @click="filter = filter === s.key ? '' : s.key">
        <b :class="{ dim: filter && filter !== s.key }">{{ summary[s.key] ?? 0 }}</b>
        <span>{{ s.label }}</span>
      </div>
    </div>

    <!-- 录入卡 -->
    <div class="app-sec">记一笔足迹</div>
    <div class="app-card tex-20 form">
      <van-field :model-value="typeLabel" is-link readonly label="类型" placeholder="选择类型（必选）"
        @click="typeOpen = true" />
      <van-field v-model="title" label="标题" maxlength="128" placeholder="必填，如：区级公开课《背影》" />
      <van-field :model-value="footDate" is-link readonly label="日期" placeholder="必选"
        @click="openDate" />
      <van-field v-model="place" label="地点" maxlength="100" placeholder="选填" />
      <van-field v-model="note" type="textarea" rows="2" autosize label="内容" maxlength="500" show-word-limit
        placeholder="选填，收获与反思（500 字内）" />
      <van-button round block type="primary" class="submit" :loading="submitting" @click="doSubmit">保存记录</van-button>
    </div>

    <div class="app-sec fp-sec">我的足迹<template v-if="filter"> · {{ filterLabel }}</template> · {{ rows.length }} 条
      <van-button size="small" plain round type="primary" :loading="exporting" class="exp" @click="exportPdf">导出 PDF</van-button>
    </div>
    <div class="app-card list">
      <div v-if="!rows.length" class="empty">还没有足迹记录</div>
      <div v-for="r in rows" :key="r.id" class="row">
        <div class="r-body">
          <p class="r-title">
            <van-tag plain :type="tagOf(r.type)" class="t-tag">{{ labelOf(r.type) }}</van-tag>
            {{ r.title }}
          </p>
          <p class="r-sub">{{ r.footDate }}<template v-if="r.place"> · {{ r.place }}</template> · 记录于 {{ fmtTime(r.createTime) }}</p>
          <p v-if="r.note" class="r-txt">{{ r.note }}</p>
        </div>
        <button class="del" type="button" @click="remove(r)"><van-icon name="delete-o" /></button>
      </div>
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>

    <van-popup v-model:show="typeOpen" position="bottom" round>
      <van-picker title="足迹类型" :columns="columns"
        @confirm="onType" @cancel="typeOpen = false" />
    </van-popup>
    <van-popup v-model:show="dateOpen" position="bottom" round>
      <van-date-picker title="足迹日期" v-model="dateBuf" :columns-type="['year', 'month', 'day']"
        :min-date="minDate" :max-date="maxDate" @confirm="onDateOk" @cancel="dateOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { saveFile } from '../api/nativeShare'

interface Fp { id: number; type: string; title: string; footDate: string; place?: string; note?: string; createTime: string }

/* 六维：五类足迹 + 奖项（教师风采荣誉聚合） */
const SIX = [
  { key: 'OPEN_CLASS', label: '公开课' },
  { key: 'OBSERVE', label: '听课' },
  { key: 'AWARD', label: '奖项' },
  { key: 'LECTURE', label: '讲座' },
  { key: 'READING', label: '读书笔记' },
  { key: 'STUDIO', label: '工作室' },
] as const
const LABEL: Record<string, string> = { OPEN_CLASS: '公开课', OBSERVE: '听课', AWARD: '奖项', LECTURE: '讲座', READING: '读书笔记', STUDIO: '工作室' }
const TAG: Record<string, string> = { OPEN_CLASS: 'primary', OBSERVE: 'success', LECTURE: 'warning', READING: 'default', STUDIO: 'danger' }
const minDate = new Date(2020, 0, 1)
const maxDate = new Date(2030, 11, 31)

const summary = ref<Record<string, number>>({})
const rows = ref<Fp[]>([])
const filter = ref('')
const typeOpen = ref(false)
const fType = ref('')
const title = ref('')
const footDate = ref('')
const dateBuf = ref<string[]>([])
const dateOpen = ref(false)
const place = ref('')
const note = ref('')
const submitting = ref(false)
const exporting = ref(false)

/** 教师足迹 PDF（批26）：同步渲染约 10s，落系统下载 */
async function exportPdf() {
  exporting.value = true
  try {
    const blob = await fetchBlob('/api/footprint/report')
    await saveFile(blob, '教师足迹.pdf')
  } catch (e: any) {
    showToast(e?.message || '导出失败')
  } finally { exporting.value = false }
}

const columns = SIX.filter((s) => s.key !== 'AWARD').map((s) => ({ text: s.label, value: s.key }))
const typeLabel = computed(() => LABEL[fType.value] || '')
const filterLabel = computed(() => LABEL[filter.value] || '')

function labelOf(t: string) { return LABEL[t] || t }
function tagOf(t: string) { return TAG[t] || 'primary' }
function onType(ev: any) {
  fType.value = ev.selectedOptions?.[0]?.value || ''
  typeOpen.value = false
}
function openDate() {
  if (footDate.value) {
    dateBuf.value = footDate.value.split('-')
  } else { // 空 model 的 van-date-picker 会落 min-date（2020），须预置今天
    const now = new Date()
    dateBuf.value = [String(now.getFullYear()), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')]
  }
  dateOpen.value = true
}
function onDateOk() {
  footDate.value = dateBuf.value.join('-')
  dateOpen.value = false
}

async function doSubmit() {
  if (!fType.value) { showToast('请选择类型'); return }
  if (!title.value.trim()) { showToast('请填写标题'); return }
  if (!footDate.value) { showToast('请选择日期'); return }
  submitting.value = true
  try {
    await api('/api/footprint', {
      method: 'POST',
      json: { type: fType.value, title: title.value.trim(), footDate: footDate.value, place: place.value.trim() || null, note: note.value.trim() || null },
    })
    showSuccessToast('已保存')
    title.value = ''
    place.value = ''
    note.value = ''
    await load()
  } catch (e: any) {
    showToast(e?.message || '保存失败')
  } finally { submitting.value = false }
}

async function remove(r: Fp) {
  try {
    await showConfirmDialog({ title: '删除足迹', message: `删除「${r.title}」？` })
  } catch { return }
  await api(`/api/footprint/${r.id}`, { method: 'DELETE' })
  showToast('已删除')
  load()
}

async function load() {
  const [s, list] = await Promise.all([
    api<Record<string, number>>('/api/footprint/summary').catch(() => ({})),
    api<Fp[]>(`/api/footprint/my${filter.value ? '?type=' + filter.value : ''}`).catch(() => []),
  ])
  summary.value = s
  rows.value = list
}

function fmtTime(t: string) {
  return t.slice(0, 16).replace('T', ' ')
}

// 点统计格切换筛选维度：标题变了，列表也要跟着过滤
watch(filter, () => load())

onMounted(load)
</script>

<style scoped>
/* 六维统计卡：上浮卡，六格横排（窄屏横滑） */
.stats { display: flex; padding: 14px 6px; margin-top: 12px; overflow-x: auto; }
.stat { flex: 1; min-width: 52px; display: flex; flex-direction: column; align-items: center; gap: 2px; cursor: pointer; }
.stat b { font-size: 20px; color: var(--app-text-1); }
.stat b.dim { color: var(--app-text-3); font-weight: 400; }
.stat span { font-size: 11px; color: var(--app-text-3); white-space: nowrap; }

.form { padding: 14px 12px; margin-top: 12px; }
.fp-sec { display: flex; align-items: center; }
.fp-sec .exp { margin-left: auto; flex: none; }
.submit { margin-top: 14px; }

.list { margin-top: 12px; padding: 6px 14px; }
.row { display: flex; align-items: flex-start; gap: 8px; padding: 12px 0; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.t-tag { margin-right: 6px; }
.r-sub { margin: 4px 0 0; font-size: 11px; color: var(--app-text-3); }
.r-txt { margin: 6px 0 0; font-size: 13px; color: var(--app-text-2); line-height: 1.55; white-space: pre-wrap; }
.del { flex: none; border: none; background: none; padding: 4px; color: var(--app-text-3); }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }
</style>
