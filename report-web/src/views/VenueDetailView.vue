<template>
  <div class="app-page vdetail">
    <!-- 主图（无照片用纹理占位，批52③ 池） -->
    <div class="hero" :class="!imgUrl && texAt(venueId * 5)">
      <img v-if="imgUrl" :src="imgUrl" alt="" />
    </div>

    <div class="app-card base">
      <p class="b-name">{{ v?.name }}</p>
      <p class="b-meta">
        <van-icon name="location-o" /> {{ v?.location || '校内' }}
        <van-icon name="friends-o" /> {{ v?.capacity ?? '—' }} 人间
      </p>
      <!-- 设备服务（批53③；无设备不占位） -->
      <div v-if="equips.length" class="b-equip">
        <span v-for="e in equips" :key="e" class="eq"><van-icon name="checked" /> {{ e }}</span>
      </div>
    </div>

    <!-- 预定时间：周条日历（今天起 14 天）+ 半小时格（06:00~23:00 连续时段连选） -->
    <div class="app-card days-card">
      <p class="sec">预定时间 <small>{{ monthLabel }}</small></p>
      <div class="days">
        <button v-for="d in days" :key="d.iso" class="day" type="button"
          :class="{ on: d.iso === date, today: d.iso === today }" @click="pickDate(d.iso)">
          <small>{{ d.week }}</small><b>{{ d.day }}</b>
        </button>
      </div>
      <!-- 批56④ 刻度对齐：刻度行与格子行交错（各 17 列等宽，数字=整点、对齐该小时起始格左缘）。
           上行 6~13 / 下行 15~22；首末半点格 13:30/14:30/22:30 不标，起止时刻底部有精确显示 -->
      <div class="row-head hi">
        <template v-for="h in 8" :key="'hi' + h">
          <span>{{ h + 5 }}</span><i />
        </template>
        <i />
      </div>
      <div class="grid">
        <button v-for="(c, i) in cellClasses.slice(0, 17)" :key="'a' + i" type="button" class="cell" :class="c"
          @click="tap(i)">{{ selStart === i ? '起' : selEnd === i ? '止' : '' }}</button>
      </div>
      <div class="row-head lo">
        <i />
        <template v-for="h in 8" :key="'lo' + h">
          <span>{{ h + 14 }}</span><i />
        </template>
      </div>
      <div class="grid">
        <button v-for="(c, i) in cellClasses.slice(17)" :key="'b' + i" type="button" class="cell" :class="c"
          @click="tap(17 + i)">{{ selStart === 17 + i ? '起' : selEnd === 17 + i ? '止' : '' }}</button>
      </div>
      <div class="legend">
        <span><i class="lg free"></i>可预定</span><span><i class="lg pend"></i>待审批</span>
        <span><i class="lg ok"></i>已预约</span><span><i class="lg sel"></i>已选择</span>
      </div>
      <p class="tip">点击开始与结束时间，选择连续时段（两段点击，起止高亮）</p>
    </div>

    <!-- 预定须知 -->
    <div class="app-card notice">
      <p class="sec">预定须知</p>
      <p class="n-line">1. 同一场地同一时段仅放行一张申请单，请先确认时段空闲再提交；</p>
      <p class="n-line">2. 提交后需审批通过方可使用，请提前申请并留意「消息通知」的审批结果。</p>
    </div>

    <!-- 当日预订记录 -->
    <div class="app-card recs">
      <p class="sec">当日预订记录（{{ v?.bookings?.length || 0 }} 条）</p>
      <div v-for="b in v?.bookings ?? []" :key="b.formId" class="rec">
        <div class="rec-l">
          <b>{{ b.start ? `${b.start} ~ ${b.end}` : '全天' }}</b>
          <span>{{ b.reason }}</span>
          <small>{{ b.applicantName }}<template v-if="b.phone"> · {{ b.phone }}</template></small>
        </div>
        <van-tag size="small" plain :type="b.state === 'APPROVED' ? 'success' : 'warning'">
          {{ b.state === 'APPROVED' ? '已预约' : '待审批' }}
        </van-tag>
      </div>
      <p v-if="!(v?.bookings?.length)" class="rec-none">暂无预订记录</p>
    </div>

    <!-- 吸底操作条 -->
    <div class="foot">
      <div class="f-sum">
        <small>预定时长</small>
        <b>{{ selHours ? selHours + ' 小时' : '—' }}</b>
        <small v-if="selRangeText">{{ selRangeText }}</small>
      </div>
      <van-button round type="primary" class="f-btn" :disabled="!selHours" @click="openConfirm">预定</van-button>
    </div>

    <!-- 确认弹层：事由必填；联系电话=本人账号手机号（后端快照，只读） -->
    <van-popup v-model:show="confirmOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <div class="p-head"><b>确认预定</b><small>{{ v?.name }} · {{ date }} {{ selRangeText }}</small></div>
      <div class="p-body">
        <van-field v-model="reason" type="textarea" rows="2" autosize label="会议主题" placeholder="必填，例如：年级组会议"
          :rules="[{ required: true, message: '请填写会议主题' }]" />
        <van-field :model-value="myPhone || '未设置（可在「我的」页维护手机号）'" label="联系电话" readonly>
          <template #right-icon><small class="ph-hint">自动带本人账号手机号</small></template>
        </van-field>
        <van-button round block type="primary" class="submit" :loading="submitting" @click="doSubmit">提交申请</van-button>
        <p class="c-tip">提交后进入审批流（审批人由学校配置），结果经「消息通知」推送</p>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { texAt } from '../utils/tex'

/** 批53③ 场地详情：半小时格连选起止时段 → OA 场地申请单（时段冲突后端硬校验兜底） */
interface Bk { formId: number; start: string | null; end: string | null; state: string; applicantName: string; reason: string; phone: string }
interface VenueDetail {
  id: number; name: string; location: string; capacity: number | null
  photoUrl: string | null; equip: string; totalCount: number; bookings: Bk[]
}

const route = useRoute()
const router = useRouter()
const venueId = Number(route.params.id)
const v = ref<VenueDetail | null>(null)
const imgUrl = ref('')
const myPhone = ref('')

function iso(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = iso(new Date())
const qd = String(route.query.date || '')
const date = ref(/^\d{4}-\d{2}-\d{2}$/.test(qd) ? qd : today)
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const days = Array.from({ length: 14 }, (_, i) => {
  const d = new Date(); d.setDate(d.getDate() + i)
  return { iso: iso(d), week: i === 0 ? '今天' : WEEK[d.getDay()], day: d.getDate() }
})
const monthLabel = `${date.value.slice(5, 7)} 月`
function pickDate(d: string) {
  if (d === date.value) return
  date.value = d
  selStart.value = null; selEnd.value = null
  load()
}

const equips = computed(() => (v.value?.equip || '').split(/[、,，]/).map((s) => s.trim()).filter(Boolean))

// ── 半小时格 06:00~23:00（34 格）：占用=待审(黄)/已批(青绿)，连选区间不可跨占用 ──
const SLOTS = 34, OPEN_MIN = 6 * 60
const selStart = ref<number | null>(null)
const selEnd = ref<number | null>(null)

function bkCells(): { s: number; e: number; state: string }[] {
  return (v.value?.bookings ?? []).map((b) => {
    if (!b.start || !b.end) return { s: 0, e: SLOTS - 1, state: b.state }
    const toIdx = (t: string) => Math.round((Number(t.slice(0, 2)) * 60 + Number(t.slice(3)) - OPEN_MIN) / 30)
    return { s: Math.max(0, toIdx(b.start)), e: Math.min(SLOTS - 1, toIdx(b.end) - 1), state: b.state }
  })
}
const usedOf = computed(() => {
  const arr = new Array<string>(SLOTS).fill('')
  for (const b of bkCells()) {
    for (let i = b.s; i <= b.e; i++) arr[i] = arr[i] === 'ok' ? 'ok' : b.state === 'APPROVED' ? 'ok' : 'pend'
  }
  return arr
})
const cellClasses = computed(() =>
  usedOf.value.map((u, i) => {
    if (u === 'ok') return 'ok'
    if (u === 'pend') return 'pend'
    if (selStart.value !== null && selEnd.value !== null && i >= selStart.value && i <= selEnd.value) return 'sel'
    if (i === selStart.value) return 'sel'
    return ''
  }))

function tap(i: number) {
  if (usedOf.value[i]) { showToast(usedOf.value[i] === 'ok' ? '该时段已被预约' : '该时段审批中，暂不可选'); return }
  if (selStart.value === null || (selStart.value !== null && selEnd.value !== null)) {
    selStart.value = i; selEnd.value = null  // 起点（或选齐后重新开始）
    return
  }
  if (i === selStart.value) { selStart.value = null; return } // 再点起点=取消
  if (i < selStart.value) { selStart.value = i; return }      // 往前点=重置起点
  for (let k = selStart.value; k <= i; k++) {
    if (usedOf.value[k]) { showToast('不能跨占用的时段，请重新选择'); return }
  }
  selEnd.value = i
}

const fmtSlot = (i: number) => {
  const m = OPEN_MIN + i * 30
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
}
const selRangeText = computed(() => selStart.value !== null && selEnd.value !== null
  ? `${fmtSlot(selStart.value)} ~ ${fmtSlot(selEnd.value + 1)}` : '')
const selHours = computed(() => selStart.value !== null && selEnd.value !== null
  ? (selEnd.value - selStart.value + 1) * 0.5 : 0)

// ── 提交（OA 场地申请单；联系电话后端自动快照本人手机号） ──
const confirmOpen = ref(false)
const reason = ref('')
const submitting = ref(false)
function openConfirm() {
  if (!selHours.value) { showToast('请先选择时段'); return }
  confirmOpen.value = true
}
async function doSubmit() {
  if (!reason.value.trim()) { showToast('请填写会议主题'); return }
  submitting.value = true
  try {
    await api('/api/oa/submit', {
      method: 'POST',
      json: {
        formType: 'VENUE', title: reason.value.trim(), venueId,
        useDate: date.value, startTime: fmtSlot(selStart.value!), endTime: fmtSlot(selEnd.value! + 1),
      },
    })
  } finally { submitting.value = false }
  confirmOpen.value = false
  showSuccessToast('已提交，等待审批')
  router.replace(`/venue?date=${date.value}`)
}

async function load() {
  v.value = await api<VenueDetail>(`/api/venue/${venueId}?date=${date.value}`).catch(() => null)
  if (v.value?.photoUrl && !imgUrl.value) {
    try { imgUrl.value = URL.createObjectURL(await fetchBlob(v.value.photoUrl)) } catch { /* 占位纹理兜底 */ }
  }
}

onMounted(async () => {
  await load()
  api<{ phone: string }>('/api/auth/me').then((d) => (myPhone.value = d.phone || '')).catch(() => {})
})
</script>

<style scoped>
.hero { height: 180px; background-size: cover; background-position: center; background-color: #E8EDF2; }
.hero img { width: 100%; height: 100%; object-fit: cover; display: block; }

.base { margin-top: 12px; padding: 12px 14px; }
.b-name { margin: 0; font-size: 16px; font-weight: 700; color: var(--app-text-1); }
.b-meta { margin: 6px 0 0; font-size: 12px; color: var(--app-text-3); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.b-meta .van-icon { margin-left: 8px; } .b-meta .van-icon:first-child { margin-left: 0; }
.b-equip { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.eq { font-size: 11px; color: var(--app-text-2); background: #F1F5F9; border-radius: 999px; padding: 3px 10px;
  display: inline-flex; align-items: center; gap: 3px; }
.eq .van-icon { color: #0D9467; }

.sec { margin: 2px 0 8px; font-size: 13px; font-weight: 700; color: var(--app-text-1); }
.sec small { font-weight: 400; color: var(--app-text-3); margin-left: 6px; }

.days-card { margin-top: 12px; padding: 12px 14px; }
.days { display: flex; gap: 4px; overflow-x: auto; margin-top: 2px; }
.days .day { flex: none; width: 44px; border: none; background: none; border-radius: 12px;
  padding: 7px 0 6px; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.days .day small { font-size: 10px; color: var(--app-text-3); }
.days .day b { font-size: 16px; color: var(--app-text-1); font-weight: 600; }
.days .day.today b { color: #0D9467; }
.days .day.on { background: #0D9467; }
.days .day.on small, .days .day.on b { color: #fff; }

/* 批56④ 刻度与格子同列等宽交错排布：数字落在本行整点格左缘 */
.row-head { display: grid; grid-template-columns: repeat(17, 1fr); gap: 2px; margin-bottom: 4px;
  font-size: 9px; color: var(--app-text-3); }
.row-head.hi { margin-top: 12px; }
.row-head.lo { margin-top: 6px; }
.row-head i { display: block; }
.grid { display: grid; grid-template-columns: repeat(17, 1fr); gap: 2px; }
.cell { height: 26px; border: none; border-radius: 4px; background: #EDF1F5;
  font-size: 9px; color: #fff; font-weight: 700; }
.cell.ok { background: #10B981; }
.cell.pend { background: #FBBF24; }
.cell.sel { background: var(--app-blue, #2563EB); }
.legend { display: flex; gap: 12px; margin-top: 10px; font-size: 11px; color: var(--app-text-3); }
.legend span { display: flex; align-items: center; gap: 4px; }
.lg { width: 12px; height: 8px; border-radius: 2px; }
.lg.free { background: #EDF1F5; } .lg.pend { background: #FBBF24; }
.lg.ok { background: #10B981; } .lg.sel { background: var(--app-blue, #2563EB); }
.tip { margin: 8px 0 0; font-size: 10px; color: var(--app-text-3); }

.notice { margin-top: 12px; padding: 12px 14px; }
.n-line { margin: 4px 0 0; font-size: 12px; color: var(--app-text-2); line-height: 1.7; }

.recs { margin-top: 12px; padding: 12px 14px; margin-bottom: 76px; }
.rec { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-top: 1px solid var(--app-card-border); }
.rec:first-of-type { border-top: none; }
.rec-l { flex: 1; min-width: 0; }
.rec-l b { display: block; font-size: 13px; color: var(--app-text-1); }
.rec-l span { display: block; margin-top: 2px; font-size: 12px; color: var(--app-text-2);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec-l small { display: block; margin-top: 2px; font-size: 11px; color: var(--app-text-3); }
.rec-none { margin: 4px 0 2px; text-align: center; font-size: 12px; color: var(--app-text-3); padding: 12px 0; }

.foot { position: fixed; left: 0; right: 0; bottom: 0; z-index: 10; display: flex; align-items: center; gap: 12px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: #fff; box-shadow: 0 -4px 14px rgba(15, 23, 42, 0.06); }
.f-sum { flex: 1; display: flex; align-items: baseline; gap: 8px; }
.f-sum small { font-size: 11px; color: var(--app-text-3); }
.f-sum b { font-size: 16px; color: var(--app-text-1); }
.f-btn { width: 128px; }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }
.ph-hint { font-size: 10px; color: var(--app-text-3); }
.submit { margin: 14px 0 4px; }
.c-tip { margin: 6px 2px 0; font-size: 11px; color: var(--app-text-3); }
</style>
