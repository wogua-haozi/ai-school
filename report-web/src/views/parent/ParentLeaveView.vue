<template>
  <div class="app-page p-leave">
    <!-- 批32：家长不再 App 提交请假（微信/电话联系班主任，由老师登记），此页只读收通知+查记录 -->
    <div class="app-card tex-14 tip">
      学生请假请直接联系班主任（微信/电话），由老师在系统登记办理；办理进度与结果会在「通知」里提醒，也可在本页查看。
    </div>

    <!-- 请假记录 -->
    <div class="app-sec">请假记录</div>
    <div class="app-card list">
      <van-skeleton v-if="!loaded" :row="4" />
      <div v-else-if="!rows.length" class="empty">还没有请假记录</div>
      <div v-for="r in rows" :key="r.id" class="row" @click="openDetail(r)">
        <div class="r-body">
          <p class="r-title">{{ r.studentName }} · {{ r.leaveType }} · {{ durText(r.durationDays) }}</p>
          <p class="r-sub">{{ r.startTime }} ~ {{ r.endTime }}</p>
          <p class="r-sub">
            {{ r.creatorName || '老师' }} 登记 · {{ relTime(r.createTime) }}<template v-if="r.status !== 'PENDING' && r.approveNote"> · {{ r.approveNote }}</template>
          </p>
        </div>
        <div class="r-side">
          <span class="st" :class="stClass(r.status)">{{ r.stepLabel }}</span>
        </div>
      </div>
    </div>

    <!-- 详情弹层 -->
    <van-popup v-model:show="detailOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <template v-if="detail">
        <div class="p-head">
          <b>{{ detail.studentName }} · {{ detail.leaveType }} · {{ detail.stepLabel }}</b>
          <small>{{ detail.startTime }} ~ {{ detail.endTime }}</small>
        </div>
        <div class="p-body">
          <div class="app-sec" style="margin: 0 0 4px">请假事由</div>
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
          <div class="app-sec" style="margin: 14px 0 4px">审批与出入</div>
          <div class="detail-cells">
            <p><span>状态</span><b>{{ detail.stepLabel }}</b></p>
            <p v-if="detail.approverName"><span>终审老师</span><b>{{ detail.approverName }}</b></p>
            <p v-if="detail.approveTime"><span>审批时间</span><b>{{ fmtTime(detail.approveTime) }}</b></p>
            <p v-if="detail.approveNote"><span>审批意见</span><b>{{ detail.approveNote }}</b></p>
            <p v-if="detail.leaveTime"><span>离校登记</span><b>{{ fmtTime(detail.leaveTime) }}</b></p>
            <p v-if="detail.returnTime"><span>返校登记</span><b>{{ fmtTime(detail.returnTime) }}</b></p>
          </div>
        </div>
      </template>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, fetchBlob } from '../../api/http'
import { relTime } from '../../utils/fmt'

interface LeaveRow {
  id: number; studentId: number; studentName: string; studentNo?: string; className?: string
  leaveType: string; startTime: string; endTime: string; durationDays?: number; reason: string
  status: string; stepLabel: string; creatorName: string
  approverName: string; approveNote: string; approveTime?: string
  leaveTime?: string; returnTime?: string; createTime: string
  photoCount: number; photoUrls: string[]
  logs?: { action: string; operator: string; note: string; time: string }[]
}

const rows = ref<LeaveRow[]>([])
const loaded = ref(false)
const detail = ref<LeaveRow | null>(null)
const detailOpen = ref(false)
const detailUrls = ref<string[]>([])

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
function durText(d?: number) {
  if (d == null) return ''
  return d < 1 ? `${Math.round(d * 8 * 2) / 2} 小时` : `${d} 天`
}
function stClass(s: string) {
  return ({ PENDING: 'pend', APPROVED: 'ok', REJECTED: 'bad', CANCELLED: 'grey' } as Record<string, string>)[s] || ''
}

onMounted(async () => {
  rows.value = await api<LeaveRow[]>('/api/student-leave/my')
  loaded.value = true
})
</script>

<style scoped>
.tip { margin-top: 12px; padding: 12px 14px; font-size: 12px; color: var(--app-text-2); line-height: 1.7;
  background: var(--shine-red-soft, #FDEEE2); }

.list { padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; cursor: pointer; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1); }
.r-sub { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-side { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.st { font-size: 12px; font-weight: 600; }
.st.pend { color: #B45309; } .st.ok { color: #0D9467; } .st.bad { color: #EF4444; } .st.grey { color: var(--app-text-3); }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }
.desc { margin: 6px 4px; font-size: 13px; color: var(--app-text-1); line-height: 1.6; }
.big-photos { display: flex; gap: 8px; flex-wrap: wrap; }
.big-photos img { width: 100%; border-radius: 10px; display: block; }
.timeline { border-left: 2px solid var(--app-card-border); margin: 6px 0 4px 6px; padding-left: 12px; }
.timeline p { margin: 0 0 10px; }
.timeline b { display: block; font-size: 13px; color: var(--app-text-1); font-weight: 600; }
.timeline span { display: block; font-size: 11px; color: var(--app-text-3); margin-top: 2px; }
.timeline em { display: block; font-style: normal; font-size: 11px; color: var(--app-text-2); margin-top: 2px; }
.detail-cells { border: 1px solid var(--app-card-border); border-radius: 12px; padding: 4px 12px; }
.detail-cells p { display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 10px 0; font-size: 13px; }
.detail-cells p + p { border-top: 1px solid var(--app-card-border); }
.detail-cells span { flex: none; color: var(--app-text-3); }
.detail-cells b { text-align: right; font-weight: 600; color: var(--app-text-1); }
</style>
