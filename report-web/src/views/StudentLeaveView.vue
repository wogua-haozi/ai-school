<template>
  <div class="app-page leave">
    <!-- 状态筛选 + 范围切换（主任/领导/管理员可看全校）+ 登记入口（批32：教师代录） -->
    <div class="app-card tex-b filters">
      <div class="f-row">
        <button v-for="t in tabs" :key="t.key" type="button" class="f-chip"
          :class="{ on: status === t.key }" @click="status = t.key">{{ t.label }}</button>
      </div>
      <div class="f-row" style="margin-top: 8px">
        <template v-if="canScopeAll">
          <button type="button" class="f-chip" :class="{ on: scope === 'my' }" @click="scope = 'my'">我的范围</button>
          <button type="button" class="f-chip" :class="{ on: scope === 'all' }" @click="scope = 'all'">全校</button>
        </template>
        <span class="spacer"></span>
        <button type="button" class="add-btn" @click="openCreate">＋ 登记请假</button>
      </div>
      <p class="f-tip">家长微信/电话请假，由老师在此登记；时长自动折算并判定审批级数（超上限走纸质申请）</p>
    </div>

    <!-- 请假列表 -->
    <div class="app-card list">
      <van-skeleton v-if="!loaded" :row="6" />
      <div v-else-if="!rows.length" class="empty">{{ status === 'PENDING' ? '没有待审批的请假' : '暂无请假记录' }}</div>
      <div v-for="r in rows" :key="r.id" class="row" @click="openDetail(r)">
        <div class="r-body">
          <p class="r-title">
            <b>{{ r.studentName }}</b><span class="cls">{{ r.className }}</span>
            <span class="t-chip" :class="typeClass(r.leaveType)">{{ r.leaveType }}</span>
          </p>
          <p class="r-meta">{{ r.startTime }} ~ {{ r.endTime }} · {{ durText(r.durationDays) }}</p>
          <p class="r-sub">{{ r.reason }}</p>
          <p class="r-sub">
            {{ r.creatorName || '—' }} 登记 · {{ relTime(r.createTime) }}
            <template v-if="r.status !== 'PENDING' && r.approverName"> · {{ r.approverName }}审批</template>
          </p>
        </div>
        <div class="r-side">
          <span class="st" :class="stClass(r.status)">{{ r.stepLabel }}</span>
          <template v-if="r.status === 'PENDING' && r.canApprove">
            <button class="btn ok" type="button" @click.stop="doApprove(r)">通过</button>
            <button class="btn bad" type="button" @click.stop="askReject(r)">驳回</button>
          </template>
        </div>
      </div>
    </div>

    <!-- 登记表单（教师代录） -->
    <van-popup v-model:show="cOpen" position="bottom" round :style="{ maxHeight: '88%' }" class="pop">
      <div class="p-head"><b>登记请假</b><small>家长与审批人将自动收到通知</small></div>
      <div class="p-body">
        <van-field v-model="form.className" label="班级" placeholder="选择班级" readonly is-link
          input-align="right" @click="pickTarget = 'class'; pickerOpen = true" />
        <van-field v-model="form.studentName" label="学生" placeholder="先选班级再选学生" readonly is-link
          input-align="right" @click="openStudentPick" />
        <van-field label="类型" input-align="right">
          <template #input>
            <div class="type-chips">
              <button v-for="t in types" :key="t" type="button" class="f-chip"
                :class="{ on: form.leaveType === t }" @click="form.leaveType = t">{{ t }}</button>
            </div>
          </template>
        </van-field>
        <div class="dt-row">
          <label>开始</label>
          <input v-model="form.startTime" type="datetime-local" />
        </div>
        <div class="dt-row">
          <label>结束</label>
          <input v-model="form.endTime" type="datetime-local" />
        </div>
        <p v-if="durHint" class="dur-hint">{{ durHint }}</p>
        <van-field v-model="form.reason" label="事由" type="textarea" rows="2" autosize
          placeholder="请假事由（家长与审批人可见）" :maxlength="300" show-word-limit />
        <div class="up-row">
          <span class="up-label">凭证照片</span>
          <van-uploader v-model="files" :max-count="3" :max-size="10 * 1024 * 1024"
            accept="image/jpeg,image/png" @oversize="showToast('照片不能超过 10MB')" />
        </div>
        <button class="submit" type="button" :disabled="submitting" @click="doCreate">
          {{ submitting ? '提交中…' : '提交' }}
        </button>
      </div>
    </van-popup>

    <!-- 班级/学生选择器 -->
    <van-popup v-model:show="pickerOpen" position="bottom" round>
      <van-picker :columns="pickColumns" @cancel="pickerOpen = false" @confirm="onPick" />
    </van-popup>

    <!-- 详情弹层 -->
    <van-popup v-model:show="detailOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <template v-if="detail">
        <div class="p-head">
          <b>{{ detail.studentName }} · {{ detail.leaveType }} · {{ detail.stepLabel }}</b>
          <small>{{ detail.className }} · 学号 {{ detail.studentNo }}</small>
        </div>
        <div class="p-body">
          <div class="app-sec" style="margin: 0 0 4px">请假信息</div>
          <div class="detail-cells">
            <p><span>起止时间</span><b>{{ detail.startTime }} ~ {{ detail.endTime }}</b></p>
            <p><span>时长</span><b>{{ durText(detail.durationDays) }}</b></p>
            <p><span>登记人</span><b>{{ detail.creatorName || '—' }}</b></p>
            <p><span>登记时间</span><b>{{ fmtTime(detail.createTime) }}</b></p>
          </div>
          <div class="app-sec" style="margin: 14px 0 4px">请假事由</div>
          <p class="desc">{{ detail.reason }}</p>
          <template v-if="detailUrls.length">
            <div class="app-sec" style="margin: 14px 0 4px">凭证照片（{{ detailUrls.length }}）</div>
            <div class="big-photos"><img v-for="(u, i) in detailUrls" :key="i" :src="u" alt="凭证" /></div>
          </template>
          <div class="app-sec" style="margin: 14px 0 4px">流转记录</div>
          <div class="timeline">
            <p v-for="(lg, i) in (detail.logs || [])" :key="i">
              <b>{{ lg.action }}</b>
              <span>{{ lg.operator }} · {{ lg.time }}</span>
              <em v-if="lg.note">{{ lg.note }}</em>
            </p>
          </div>
          <template v-if="detail.status !== 'PENDING'">
            <div class="app-sec" style="margin: 14px 0 4px">审批结果</div>
            <div class="detail-cells">
              <p><span>结果</span><b>{{ detail.stepLabel }}</b></p>
              <p v-if="detail.approverName"><span>终审人</span><b>{{ detail.approverName }}</b></p>
              <p v-if="detail.approveTime"><span>时间</span><b>{{ fmtTime(detail.approveTime) }}</b></p>
              <p v-if="detail.approveNote"><span>意见</span><b>{{ detail.approveNote }}</b></p>
              <p v-if="detail.leaveTime"><span>离校登记</span><b>{{ fmtTime(detail.leaveTime) }}</b></p>
              <p v-if="detail.returnTime"><span>返校登记</span><b>{{ fmtTime(detail.returnTime) }}</b></p>
            </div>
          </template>
          <button v-if="detail.status === 'PENDING'" class="cancel-btn" type="button" @click="doCancel">撤销该请假单</button>
        </div>
      </template>
    </van-popup>

    <!-- 驳回原因 -->
    <van-dialog v-model:show="rejectOpen" title="驳回请假" show-cancel-button
      @confirm="doReject">
      <div class="rej-body">
        <van-field v-model="rejectNote" type="textarea" rows="2" autosize placeholder="请填写驳回原因（必填，家长可见）" :maxlength="200" />
      </div>
    </van-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { relTime } from '../utils/fmt'
import { useAuthStore } from '../stores/auth'

interface LeaveRow {
  id: number; studentId: number; studentName: string; studentNo?: string; className?: string
  leaveType: string; startTime: string; endTime: string; durationDays?: number; reason: string
  status: string; stepLabel: string; totalStep?: number; currentStep?: number
  creatorName: string; approverName: string; approveNote: string; approveTime?: string
  leaveTime?: string; returnTime?: string; createTime: string
  photoCount: number; photoUrls: string[]; canApprove?: boolean
  logs?: { action: string; operator: string; note: string; time: string }[]
}
interface PickItem { id: number; name: string; studentNo?: string }

const auth = useAuthStore()
const canScopeAll = ['DIRECTOR', 'LEADER', 'ADMIN'].includes(auth.role)
const types = ['病假', '事假', '其他']

const tabs = [
  { key: 'PENDING', label: '待审批' },
  { key: 'APPROVED', label: '已通过' },
  { key: 'REJECTED', label: '已驳回' },
  { key: '', label: '全部' },
]
const status = ref('PENDING')
const scope = ref('my')
const rows = ref<LeaveRow[]>([])
const loaded = ref(false)

// ── 详情 ──
const detail = ref<LeaveRow | null>(null)
const detailOpen = ref(false)
const detailUrls = ref<string[]>([])
const rejectOpen = ref(false)
const rejectNote = ref('')
const rejecting = ref<LeaveRow | null>(null)

// ── 登记表单 ──
const cOpen = ref(false)
const submitting = ref(false)
const classes = ref<PickItem[]>([])
const students = ref<PickItem[]>([])
const files = ref<{ file?: File }[]>([])
const form = ref({ classId: 0, className: '', studentId: 0, studentName: '', leaveType: '病假', startTime: '', endTime: '', reason: '' })
const pickerOpen = ref(false)
const pickTarget = ref<'class' | 'student'>('class')

async function load() {
  const qs = new URLSearchParams()
  if (status.value) qs.set('status', status.value)
  qs.set('scope', scope.value)
  rows.value = await api<LeaveRow[]>(`/api/student-leave/list?${qs}`)
  loaded.value = true
}

watch(status, () => { loaded.value = false; load() })
watch(scope, () => { loaded.value = false; load() })

// 时长折算（前端仅展示；审批级数由后端按配置阈值判定）
const durHint = computed(() => {
  const s = new Date(form.value.startTime).getTime()
  const e = new Date(form.value.endTime).getTime()
  if (!s || !e) return ''
  if (e <= s) return '结束时间需晚于开始时间'
  return `时长 ${durText(Math.round((e - s) / 864000) / 10)}，审批级数以提交结果为准（超上限须走纸质申请）`
})

function durText(d?: number) {
  if (d == null) return '—'
  return d < 1 ? `${Math.round(d * 8 * 2) / 2} 小时` : `${d} 天`
}

async function openCreate() {
  if (!classes.value.length) {
    classes.value = await api<PickItem[]>('/api/student-leave/classes')
  }
  form.value = { classId: 0, className: '', studentId: 0, studentName: '', leaveType: '病假', startTime: '', endTime: '', reason: '' }
  files.value = []
  students.value = []
  cOpen.value = true
}

function openStudentPick() {
  if (!form.value.classId) { showToast('请先选择班级'); return }
  pickTarget.value = 'student'
  pickerOpen.value = true
}

const pickColumns = computed(() => {
  const list = pickTarget.value === 'class' ? classes.value : students.value
  return list.map(x => ({ text: x.studentNo ? `${x.name}（${x.studentNo}）` : x.name, value: x.id }))
})

function onPick(ev: { selectedOptions: { text: string; value: number }[] }) {
  const opt = ev.selectedOptions?.[0]
  if (opt) {
    if (pickTarget.value === 'class') {
      form.value.classId = opt.value
      form.value.className = opt.text
      form.value.studentId = 0
      form.value.studentName = ''
      api<PickItem[]>(`/api/student-leave/students?classId=${opt.value}`).then(list => { students.value = list })
    } else {
      form.value.studentId = opt.value
      form.value.studentName = opt.text
    }
  }
  pickerOpen.value = false
}

async function doCreate() {
  const f = form.value
  if (!f.studentId) { showToast('请选择学生'); return }
  if (!f.startTime || !f.endTime) { showToast('请选择起止时间'); return }
  if (!f.reason.trim()) { showToast('请填写请假事由'); return }
  const fd = new FormData()
  fd.set('studentId', String(f.studentId))
  fd.set('leaveType', f.leaveType)
  fd.set('startTime', f.startTime.replace('T', ' '))
  fd.set('endTime', f.endTime.replace('T', ' '))
  fd.set('reason', f.reason.trim())
  for (const item of files.value) {
    if (item.file) fd.append('photos', item.file)
  }
  submitting.value = true
  try {
    const r = await api<{ totalStep: number; status: string }>('/api/student-leave', { method: 'POST', body: fd })
    showSuccessToast(r.totalStep === 1 ? '已提交，待级长/级部主任审批'
      : r.totalStep === 2 ? '已提交，待级长/级部主任+学成中心审批'
        : '已提交，待级长/级部主任+学成中心+书记审批')
    cOpen.value = false
    status.value = 'PENDING'
    loaded.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function doApprove(r: LeaveRow) {
  await api(`/api/student-leave/${r.id}/approve`, { method: 'PUT' })
  showSuccessToast(r.currentStep === r.totalStep ? '终审通过，已同步家长/门卫' : '本级已通过')
  load()
}

function askReject(r: LeaveRow) {
  rejecting.value = r
  rejectNote.value = ''
  rejectOpen.value = true
}

async function doReject() {
  if (!rejectNote.value.trim()) { showToast('请填写驳回原因'); return }
  if (!rejecting.value) return
  await api(`/api/student-leave/${rejecting.value.id}/reject?note=${encodeURIComponent(rejectNote.value.trim())}`, { method: 'PUT' })
  showSuccessToast('已驳回，家长已收到通知')
  load()
}

async function doCancel() {
  if (!detail.value) return
  await showConfirmDialog({ title: '撤销请假', message: '确认撤销该请假单？家长将收到通知。' })
  await api(`/api/student-leave/${detail.value.id}/cancel`, { method: 'PUT' })
  showSuccessToast('已撤销')
  detailOpen.value = false
  load()
}

async function openDetail(r: LeaveRow) {
  detail.value = await api<LeaveRow>(`/api/student-leave/${r.id}`)
  detailUrls.value = []
  detailOpen.value = true
  for (const u of detail.value.photoUrls) {
    try { detailUrls.value.push(URL.createObjectURL(await fetchBlob(u))) } catch { /* 单张缺失不阻塞 */ }
  }
}

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}
function stClass(s: string) {
  return ({ PENDING: 'pend', APPROVED: 'ok', REJECTED: 'bad', CANCELLED: 'grey' } as Record<string, string>)[s] || ''
}
function typeClass(t: string) {
  return ({ 病假: 'sick', 事假: 'aff', 其他: 'oth' } as Record<string, string>)[t] || 'oth'
}

onMounted(load)
</script>

<style scoped>
.filters { margin-top: 12px; padding: 10px 14px; }
.f-row { display: flex; align-items: center; gap: 8px; }
.f-chip { flex: none; border: 1px solid var(--app-card-border); background: none; border-radius: 999px;
  padding: 5px 16px; font-size: 13px; color: var(--app-text-2); }
.f-chip.on { border-color: var(--app-blue); color: #fff; background: var(--app-blue); font-weight: 600; }
.spacer { flex: 1; }
.f-tip { margin: 8px 0 0; font-size: 11px; color: var(--app-text-3); }
.add-btn { flex: none; border: none; border-radius: 999px; padding: 5px 16px; font-size: 13px; font-weight: 600;
  background: var(--app-blue); color: #fff; }

.list { padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; cursor: pointer; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; color: var(--app-text-1); display: flex; align-items: center; gap: 6px; }
.r-title b { font-weight: 600; }
.cls { font-size: 11px; color: var(--app-text-3); }
.t-chip { flex: none; font-size: 10px; border-radius: 6px; padding: 1px 6px; }
.t-chip.sick { background: #FDEEE2; color: #EA580C; }
.t-chip.aff { background: #E8F6EF; color: #0D9467; }
.t-chip.oth { background: #F3EAFE; color: #7C4DD8; }
.r-meta { margin: 3px 0 0; font-size: 12px; color: var(--app-text-2); }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-side { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.st { font-size: 12px; font-weight: 600; }
.st.pend { color: #B45309; } .st.ok { color: #0D9467; } .st.bad { color: #EF4444; } .st.grey { color: var(--app-text-3); }
.btn { border: none; border-radius: 999px; font-size: 12px; padding: 3px 14px; }
.btn.ok { background: var(--app-blue); color: #fff; }
.btn.bad { background: none; border: 1px solid #EF4444; color: #EF4444; }
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

.timeline { border-left: 2px solid var(--app-card-border); margin: 6px 0 4px 6px; padding-left: 12px; }
.timeline p { margin: 0 0 10px; }
.timeline b { display: block; font-size: 13px; color: var(--app-text-1); font-weight: 600; }
.timeline span { display: block; font-size: 11px; color: var(--app-text-3); margin-top: 2px; }
.timeline em { display: block; font-style: normal; font-size: 11px; color: var(--app-text-2); margin-top: 2px; }

.type-chips { display: flex; gap: 6px; }
.dt-row { display: flex; align-items: center; gap: 12px; padding: 10px 16px; font-size: 14px; }
.dt-row label { flex: none; width: 3.2em; color: var(--app-text-2); }
.dt-row input { flex: 1; border: 1px solid var(--app-card-border); border-radius: 8px; padding: 6px 10px;
  font-size: 13px; background: none; color: var(--app-text-1); }
.dur-hint { margin: 0 16px 8px; font-size: 11px; color: var(--app-text-3); }
.up-row { display: flex; align-items: flex-start; gap: 12px; padding: 10px 16px; }
.up-label { flex: none; width: 3.2em; font-size: 14px; color: var(--app-text-2); padding-top: 6px; }
.submit { display: block; width: calc(100% - 32px); margin: 12px 16px 4px; border: none; border-radius: 999px;
  padding: 10px 0; font-size: 15px; font-weight: 600; background: var(--app-blue); color: #fff; }
.submit:disabled { opacity: .6; }
.cancel-btn { display: block; width: calc(100% - 32px); margin: 14px 16px 4px; border: 1px solid #EF4444;
  border-radius: 999px; padding: 9px 0; font-size: 14px; color: #EF4444; background: none; }

.rej-body { padding: 10px 16px; }
</style>
