<template>
  <div class="app-page repair">
    <!-- 发起 -->
    <div class="app-card tex-09 form">
      <van-field v-model="location" label="地点/设施" placeholder="例如：初二(3)班 教室前门"
        :maxlength="100" class="f-in" />
      <van-field v-model="description" type="textarea" rows="2" autosize label="故障描述" placeholder="描述故障情况（必填，500 字内）"
        :maxlength="500" class="f-in" />
      <div class="photo-row">
        <div class="ph-lbl">凭证照片（≤3 张，可选）</div>
        <div class="thumbs">
          <div v-for="(p, i) in photos" :key="i" class="thumb">
            <img :src="p.url" alt="凭证" />
            <button type="button" class="rm" @click="photos.splice(i, 1)"><van-icon name="cross" /></button>
          </div>
          <button v-if="photos.length < 3" type="button" class="add" @click="fileInput?.click()">
            <van-icon name="photograph" /><span>拍照/选图</span>
          </button>
        </div>
        <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple class="hide"
          @change="onPick" />
      </div>
      <van-button round block type="primary" class="submit" :loading="submitting" @click="doSubmit">提交报修</van-button>
    </div>

    <!-- 我的报修 -->
    <div class="app-sec">我的报修</div>
    <div class="app-card list">
      <div v-if="!rows.length" class="empty">还没有报修记录</div>
      <div v-for="r in rows" :key="r.id" class="row" @click="openDetail(r)">
        <div class="r-body">
          <p class="r-title">{{ r.location }}</p>
          <p class="r-sub">{{ r.description }}</p>
          <p class="r-sub">
            <van-icon name="photo-o" v-if="r.photoCount" /> {{ relTime(r.createTime) }}
            <template v-if="r.status !== 'PENDING' && r.handleNote"> · {{ r.handleNote }}</template>
          </p>
        </div>
        <span class="st" :class="stClass(r.status)">{{ stLabel(r.status) }}</span>
      </div>
    </div>

    <!-- 详情弹层 -->
    <van-popup v-model:show="detailOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <template v-if="detail">
        <div class="p-head">
          <b>报修 · {{ detail.location }}</b>
          <small>{{ relTime(detail.createTime) }} 提交</small>
        </div>
        <div class="p-body">
          <div class="app-sec" style="margin: 0 0 4px">故障描述</div>
          <p class="desc">{{ detail.description }}</p>
          <template v-if="detail.photoUrls.length">
            <div class="app-sec" style="margin: 14px 0 4px">凭证照片（{{ detail.photoUrls.length }}）</div>
            <div class="big-photos">
              <img v-for="(u, i) in detailUrls" :key="i" :src="u" alt="凭证" />
            </div>
          </template>
          <template v-if="detail.status !== 'PENDING'">
            <div class="app-sec" style="margin: 14px 0 4px">处理结果</div>
            <div class="detail-cells">
              <p><span>结果</span><b>{{ stLabel(detail.status) }}</b></p>
              <p><span>处理人</span><b>{{ detail.handlerName }}</b></p>
              <p><span>时间</span><b>{{ fmtTime(detail.handleTime) }}</b></p>
              <p v-if="detail.handleNote"><span>说明</span><b>{{ detail.handleNote }}</b></p>
            </div>
          </template>
          <template v-else>
            <p class="node-tip">待管理员处理</p>
            <!-- 批40e 撤回：发起人未处理前可撤回（凭证照片一并清除） -->
            <van-button round block size="small" plain type="danger" style="margin-top: 10px"
              @click="doCancel">撤回报修</van-button>
          </template>
        </div>
      </template>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api, apiForm, fetchBlob } from '../api/http'
import { relTime } from '../utils/fmt'

interface RepairRow {
  id: number; location: string; description: string; status: string
  reporterName: string; handlerName: string; handleNote: string
  handleTime?: string; createTime: string; photoCount: number; photoUrls: string[]
}

const location = ref('')
const description = ref('')
const photos = ref<{ file: File; url: string }[]>([])
const fileInput = ref<HTMLInputElement>()
const submitting = ref(false)
const rows = ref<RepairRow[]>([])
const detail = ref<RepairRow | null>(null)
const detailOpen = ref(false)
const detailUrls = ref<string[]>([])

async function load() {
  rows.value = await api<RepairRow[]>('/api/repair/my')
}

function onPick(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  for (const f of files) {
    if (photos.value.length >= 3) break
    photos.value.push({ file: f, url: URL.createObjectURL(f) })
  }
  ;(e.target as HTMLInputElement).value = ''
}

async function doSubmit() {
  if (!location.value.trim()) { showToast('请填写故障地点或设施'); return }
  if (!description.value.trim()) { showToast('请填写故障描述'); return }
  submitting.value = true
  try {
    const fd = new FormData()
    fd.append('location', location.value)
    fd.append('description', description.value)
    photos.value.forEach((p) => fd.append('photos', p.file))
    await apiForm('/api/repair', fd)
  } finally { submitting.value = false }
  showSuccessToast('已提交')
  location.value = ''
  description.value = ''
  photos.value.forEach((p) => URL.revokeObjectURL(p.url))
  photos.value = []
  load()
}

async function openDetail(r: RepairRow) {
  detail.value = await api<RepairRow>(`/api/repair/${r.id}`)
  detailUrls.value = []
  detailOpen.value = true
  for (const u of detail.value.photoUrls) {
    try {
      const blob = await fetchBlob(u)
      detailUrls.value.push(URL.createObjectURL(blob))
    } catch { /* 单张缺失不阻塞 */ }
  }
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}
function stLabel(s: string) {
  return ({ PENDING: '待处理', DONE: '已完成', REJECTED: '不予受理', CANCELED: '已撤回' } as Record<string, string>)[s] || s
}
function stClass(s: string) {
  return ({ PENDING: 'pend', DONE: 'ok', REJECTED: 'bad', CANCELED: 'cxl' } as Record<string, string>)[s] || ''
}

/** 批40e 撤回：发起人撤回未处理的报修单 */
async function doCancel() {
  if (!detail.value) return
  try { await showConfirmDialog({ title: '撤回报修', message: '撤回后该报修单作废，凭证照片一并清除' }) }
  catch { return }
  await api(`/api/repair/${detail.value.id}/cancel`, { method: 'PUT' })
  showSuccessToast('已撤回')
  detailOpen.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.form { margin-top: 12px; padding: 6px 14px 14px; }
.f-in { padding: 8px 0; }
.photo-row { padding: 4px 0 2px; }
.ph-lbl { font-size: 12px; color: var(--app-text-3); margin: 4px 0 8px; }
.thumbs { display: flex; gap: 8px; flex-wrap: wrap; }
.thumb { position: relative; }
.thumb img { width: 72px; height: 72px; object-fit: cover; border-radius: 10px; display: block; }
.thumb .rm { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; border: none;
  background: rgba(0, 0, 0, 0.6); color: #fff; border-radius: 50%; display: flex;
  align-items: center; justify-content: center; font-size: 12px; }
.add { width: 72px; height: 72px; border: 1px dashed var(--app-card-border); border-radius: 10px;
  background: none; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4px; color: var(--app-text-3); font-size: 10px; }
.add .van-icon { font-size: 20px; color: var(--app-blue); }
.hide { display: none; }
.submit { margin-top: 12px; }

.list { padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; cursor: pointer; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.st { flex: none; font-size: 12px; font-weight: 600; }
.st.pend { color: #B45309; } .st.ok { color: #0D9467; } .st.bad { color: #EF4444; } .st.cxl { color: var(--app-text-3); }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }
.desc { margin: 6px 4px; font-size: 13px; color: var(--app-text-1); line-height: 1.6; }
.big-photos { display: flex; gap: 8px; flex-wrap: wrap; }
.big-photos img { width: 100%; border-radius: 10px; display: block; }
.detail-cells { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 4px 12px; }
.detail-cells p { display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 10px 0; font-size: 13px; }
.detail-cells p + p { border-top: 1px solid var(--app-card-border); }
.detail-cells span { flex: none; color: var(--app-text-3); }
.detail-cells b { text-align: right; font-weight: 600; color: var(--app-text-1); }
.node-tip { margin: 10px 4px 4px; font-size: 11px; color: var(--app-text-3); }
</style>
