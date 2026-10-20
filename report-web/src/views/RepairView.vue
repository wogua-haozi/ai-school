<template>
  <div class="app-page repair">
    <!-- 批63 工作台：后勤=审批；维修工=抢单+完成（普通教师只见下方发起与我的报修） -->
    <template v-if="auth.role === 'LOGISTICS'">
      <div class="app-sec">待审批 · {{ wb.todo?.length ?? 0 }}</div>
      <div class="app-card list">
        <div v-if="!wb.todo?.length" class="empty">暂无待审批报修</div>
        <div v-for="r in wb.todo" :key="r.id" class="row" @click="openDetail(r)">
          <div class="r-body">
            <p class="r-title">{{ r.location }} <small class="by">报修人 {{ r.reporterName }}</small></p>
            <p class="r-sub">{{ r.description }}</p>
            <p class="r-sub">{{ relTime(r.createTime) }}</p>
          </div>
          <div class="w-acts">
            <button type="button" class="ok" @click.stop="act(r, 'approve')">通过</button>
            <button type="button" class="bad" @click.stop="act(r, 'reject')">拒绝</button>
          </div>
        </div>
      </div>
    </template>
    <template v-else-if="auth.role === 'REPAIRER'">
      <div class="app-sec">待接单 · {{ wb.open?.length ?? 0 }}</div>
      <div class="app-card list">
        <div v-if="!wb.open?.length" class="empty">暂无可接的报修单</div>
        <div v-for="r in wb.open" :key="r.id" class="row" @click="openDetail(r)">
          <div class="r-body">
            <p class="r-title">{{ r.location }} <small class="by">报修人 {{ r.reporterName }}</small></p>
            <p class="r-sub">{{ r.description }}</p>
            <p class="r-sub">通过于 {{ relTime(r.approveTime) }} · 先到先得</p>
          </div>
          <div class="w-acts">
            <button type="button" class="ok" @click.stop="claim(r)">接单</button>
          </div>
        </div>
      </div>
      <div class="app-sec">我的维修中 · {{ wb.mine?.length ?? 0 }}</div>
      <div class="app-card list">
        <div v-if="!wb.mine?.length" class="empty">暂无维修中工单</div>
        <div v-for="r in wb.mine" :key="r.id" class="row" @click="openDetail(r)">
          <div class="r-body">
            <p class="r-title">{{ r.location }} <small class="by">报修人 {{ r.reporterName }}</small></p>
            <p class="r-sub">{{ r.description }}</p>
            <p class="r-sub">接单于 {{ fmtTime(r.claimTime) }}</p>
          </div>
          <div class="w-acts">
            <button type="button" class="ok" @click.stop="act(r, 'complete')">完成</button>
          </div>
        </div>
      </div>
    </template>

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
            <div class="app-sec" style="margin: 14px 0 4px">处理进度</div>
            <div class="detail-cells">
              <p><span>结果</span><b>{{ stLabel(detail.status) }}</b></p>
              <p v-if="detail.approverName"><span>审批</span><b>{{ detail.approverName }} · {{ fmtTime(detail.approveTime) }}</b></p>
              <p v-if="detail.approveNote"><span>审批意见</span><b>{{ detail.approveNote }}</b></p>
              <p v-if="detail.claimerName"><span>接单</span><b>{{ detail.claimerName }} · {{ fmtTime(detail.claimTime) }}</b></p>
              <p v-if="detail.handlerName"><span>处理人</span><b>{{ detail.handlerName }}</b></p>
              <p v-if="detail.handleTime"><span>完成时间</span><b>{{ fmtTime(detail.handleTime) }}</b></p>
              <p v-if="detail.handleNote"><span>说明</span><b>{{ detail.handleNote }}</b></p>
            </div>
          </template>
          <template v-else>
            <p class="node-tip">已提交，等待后勤中心审批</p>
          </template>
          <!-- 批40e 撤回；批63 放宽：通过未接单（APPROVED）前均可撤回，接单后不可 -->
          <van-button v-if="detail.status === 'PENDING' || detail.status === 'APPROVED'" round block size="small"
            plain type="danger" style="margin-top: 10px" @click="doCancel">撤回报修</van-button>
        </div>
      </template>
    </van-popup>

    <!-- 批63 审批/完成 意见弹层 -->
    <van-popup v-model:show="actOpen" position="bottom" round class="pop">
      <div class="p-head">
        <b>{{ actMode === 'complete' ? '完成维修' : actMode === 'reject' ? '拒绝报修' : '通过报修' }}</b>
        <small>{{ actRow?.location }}</small>
      </div>
      <div class="p-body">
        <van-field v-model="actNote" type="textarea" rows="2" autosize label="意见" placeholder="选填，300 字内"
          :maxlength="300" />
        <van-button round block type="primary" class="submit" :loading="acting" @click="doAct">
          {{ actMode === 'complete' ? '确认完成' : actMode === 'reject' ? '确认拒绝' : '确认通过' }}
        </van-button>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api, apiForm, fetchBlob } from '../api/http'
import { relTime } from '../utils/fmt'
import { useAuthStore } from '../stores/auth'

interface RepairRow {
  id: number; location: string; description: string; status: string
  reporterName: string; handlerName: string; handleNote: string
  approverName?: string; approveTime?: string; approveNote?: string
  claimerName?: string; claimTime?: string
  handleTime?: string; createTime: string; photoCount: number; photoUrls: string[]
}

const auth = useAuthStore()
const location = ref('')
const description = ref('')
const photos = ref<{ file: File; url: string }[]>([])
const fileInput = ref<HTMLInputElement>()
const submitting = ref(false)
const rows = ref<RepairRow[]>([])
const wb = ref<{ todo?: RepairRow[]; open?: RepairRow[]; mine?: RepairRow[] }>({})
const detail = ref<RepairRow | null>(null)
const detailOpen = ref(false)
const detailUrls = ref<string[]>([])
const actOpen = ref(false)
const actMode = ref<'approve' | 'reject' | 'complete'>('approve')
const actRow = ref<RepairRow | null>(null)
const actNote = ref('')
const acting = ref(false)

async function load() {
  rows.value = await api<RepairRow[]>('/api/repair/my')
  if (auth.role === 'LOGISTICS' || auth.role === 'REPAIRER') {
    wb.value = await api('/api/repair/workbench').catch(() => ({}))
  }
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
  return ({ PENDING: '待审批', APPROVED: '待接单', CLAIMED: '维修中', DONE: '已完成',
    REJECTED: '不予受理', CANCELED: '已撤回' } as Record<string, string>)[s] || s
}
function stClass(s: string) {
  return ({ PENDING: 'pend', APPROVED: 'pend', CLAIMED: 'fix', DONE: 'ok',
    REJECTED: 'bad', CANCELED: 'cxl' } as Record<string, string>)[s] || ''
}

/** 批63 后勤审批/维修工完成：弹层填意见后提交 */
function act(r: RepairRow, mode: 'approve' | 'reject' | 'complete') {
  actRow.value = r
  actMode.value = mode
  actNote.value = ''
  actOpen.value = true
}
async function doAct() {
  if (!actRow.value) return
  acting.value = true
  try {
    if (actMode.value === 'complete') {
      await api(`/api/repair/${actRow.value.id}/complete`, { method: 'PUT', json: { note: actNote.value } })
      showSuccessToast('已完成并通知报修人')
    } else {
      await api(`/api/repair/${actRow.value.id}/approve`, {
        method: 'PUT', json: { approved: actMode.value === 'approve', note: actNote.value } })
      showSuccessToast(actMode.value === 'approve' ? '已通过，维修工可接单' : '已拒绝')
    }
    actOpen.value = false
    await load()
  } finally { acting.value = false }
}

/** 批63 维修工抢单（条件更新防双抢，被抢先则后端 400 提示） */
async function claim(r: RepairRow) {
  try { await showConfirmDialog({ title: '接单', message: `接单后「${r.location}」由您负责维修？` }) }
  catch { return }
  await api(`/api/repair/${r.id}/claim`, { method: 'PUT' })
  showSuccessToast('已接单')
  await load()
}

/** 批40e 撤回普适（批63 放宽）：发起人撤回未接单的报修单 */
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
.r-title .by { font-size: 10px; font-weight: 400; color: var(--app-text-3); margin-left: 6px; }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.st { flex: none; font-size: 12px; font-weight: 600; }
.st.pend { color: #B45309; } .st.fix { color: #3E7BFA; } .st.ok { color: #0D9467; }
.st.bad { color: #EF4444; } .st.cxl { color: var(--app-text-3); }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

.w-acts { flex: none; display: flex; flex-direction: column; gap: 6px; }
.w-acts button { border: 1px solid; border-radius: 999px; background: none; font-size: 12px;
  padding: 4px 14px; }
.w-acts button.ok { color: #0D9467; border-color: #0D9467; }
.w-acts button.bad { color: #EF4444; border-color: #EF4444; }

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
