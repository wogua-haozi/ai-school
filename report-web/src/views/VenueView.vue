<template>
  <div class="app-page venue">
    <van-tabs v-model:active="tab" sticky class="v-tabs">
      <!-- ── 立即预约（参考图253）：搜索+日期条+场地卡（图/状态/容量/累计/时段条） ── -->
      <van-tab title="立即预约">
        <van-search v-model="kw" placeholder="搜索会议室" class="v-search" />
        <!-- 日期条：今天起 14 天横滚（共用会议总览），选中日 teal 圆底 -->
        <div class="app-card days">
          <button v-for="d in days" :key="d.iso" class="day" type="button"
            :class="{ on: d.iso === date, today: d.iso === today }" @click="pickDate(d.iso)">
            <small>{{ d.week }}</small><b>{{ d.day }}</b>
          </button>
        </div>
        <div v-for="v in filtered" :key="v.id" class="app-card v-card" @click="goDetail(v.id)">
          <div class="v-photo" :class="!v.imgUrl && texAt(v.id * 3)">
            <img v-if="v.imgUrl" :src="v.imgUrl" alt="" />
            <span class="v-approve">需要审批</span>
            <span class="v-state" :class="stateOf(v) === 'use' ? 'busy' : 'free'">
              {{ stateOf(v) === 'use' ? '使用中' : stateOf(v) === 'pend' ? '审批中' : '空闲中' }}
            </span>
          </div>
          <div class="v-body">
            <p class="v-name">{{ v.name }}</p>
            <p class="v-meta">
              <van-icon name="friends-o" /> {{ v.capacity ?? '—' }}人间
              <van-icon name="calendar-o" /> 累计 {{ v.totalCount ?? 0 }} 次预定
              <template v-if="v.location"><van-icon name="location-o" /> {{ v.location }}</template>
            </p>
            <!-- 时段条 06:00~23:00（半小时粒度）：青绿=已批占用 黄=待审 -->
            <div class="v-slots">
              <span v-for="(c, i) in slotClasses(v)" :key="i" class="sg" :class="c"></span>
            </div>
            <div class="v-scale"><span>6</span><span>9</span><span>12</span><span>15</span><span>18</span><span>21</span><span>23</span></div>
          </div>
        </div>
        <div v-if="loaded && !filtered.length" class="app-card empty-card">暂无匹配场地</div>
      </van-tab>

      <!-- ── 会议总览：按会议室（参考图255）/ 按日期（参考图256） ── -->
      <van-tab title="会议总览">
        <div class="app-card ov-head">
          <div class="ov-seg">
            <button class="f-chip" :class="{ on: ovMode === 'venue' }" type="button" @click="ovMode = 'venue'">按会议室</button>
            <button class="f-chip" :class="{ on: ovMode === 'day' }" type="button" @click="ovMode = 'day'">按日期</button>
          </div>
          <template v-if="ovMode === 'venue'">
            <!-- 昨/今/明三段钮 + 搜索（主题/预约人/手机号本地过滤） -->
            <div class="ov-row">
              <div class="d3">
                <button v-for="d in d3" :key="d.iso" class="d3-btn" :class="{ on: date === d.iso }" type="button"
                  @click="pickDate(d.iso)">{{ d.label }}</button>
              </div>
            </div>
            <van-search v-model="ovKw" placeholder="搜索会议主题 / 预约人 / 手机号" class="v-search flat" />
          </template>
        </div>

        <!-- 按会议室：场地分组记录卡 -->
        <template v-if="ovMode === 'venue'">
          <div v-for="v in ovFiltered" :key="v.id" class="app-card grp">
            <p class="grp-head" @click="goDetail(v.id)">
              <b class="grp-name">{{ v.name }}</b>
              <span class="grp-count">{{ ovBookings(v).length }} 个预约</span>
              <van-icon name="arrow" />
            </p>
            <div v-for="b in ovBookings(v)" :key="b.formId" class="bk">
              <span class="bk-ava" :style="{ background: avaColor(b.applicantName) }">{{ (b.applicantName || '?').charAt(0) }}</span>
              <div class="bk-body">
                <p class="l1">
                  <span class="wk">{{ weekCN(date) }}</span> {{ date.slice(5) }}
                  <b class="tm">{{ b.start || '全天' }}{{ b.end ? ` ~ ${b.end}` : '' }}</b>
                  <van-tag size="small" plain :type="b.state === 'APPROVED' ? 'success' : 'warning'">
                    {{ b.state === 'APPROVED' ? '已预约' : '待审批' }}
                  </van-tag>
                </p>
                <p class="l2">{{ b.reason }}</p>
                <p class="l3">{{ b.applicantName }}<template v-if="b.phone"> · {{ b.phone }}</template></p>
              </div>
            </div>
            <div v-if="!ovBookings(v).length" class="bk-empty">
              <van-icon name="records-o" />
              <p>暂无预约，还没有人预约这间会议室</p>
              <button class="go-book" type="button" @click="goDetail(v.id)">立即预约</button>
            </div>
          </div>
        </template>

        <!-- 按日期：昨/今/明三段钮 + 图例 + 场地行时轴（今天画当前时刻红线） -->
        <template v-else>
          <div class="app-card day-head">
            <div class="d3">
              <button v-for="d in d3" :key="d.iso" class="d3-btn" :class="{ on: date === d.iso }" type="button"
                @click="pickDate(d.iso)">{{ d.label }}</button>
            </div>
            <div class="legend">
              <span><i class="lg ok"></i>已预约</span><span><i class="lg pend"></i>待审批</span>
              <span><i class="lg free"></i>空闲</span><span v-if="date === today"><i class="lg now"></i>当前</span>
            </div>
          </div>
          <div class="app-card ax-card">
            <div v-for="v in venues" :key="v.id" class="ax-row" @click="goDetail(v.id)">
              <div class="ax-name">
                <b>{{ v.name }}</b>
                <span>{{ v.bookings?.length || 0 }} 个</span>
              </div>
              <div class="ax-track">
                <span v-for="(c, i) in slotClasses(v)" :key="i" class="sg" :class="c"></span>
                <i v-if="date === today" class="ax-now" :style="{ left: nowPct + '%' }"></i>
              </div>
              <p class="ax-date">{{ date.slice(5).replace('-', '/') }}</p>
            </div>
          </div>
        </template>
      </van-tab>
    </van-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { texAt } from '../utils/tex'

/** 批53③ 场地预约主页：立即预约（场地卡+时段条） / 会议总览（按会议室记录卡 + 按日期时轴），全体教师互见 */
interface Bk { formId: number; start: string | null; end: string | null; state: string; applicantName: string; reason: string; phone: string }
interface VenueRow {
  id: number; name: string; location: string; capacity: number | null
  photoUrl: string | null; equip: string; totalCount: number; bookings: Bk[]; imgUrl?: string
}

const router = useRouter()
const route = useRoute()
const tab = ref(0)
const kw = ref('')
const ovKw = ref('')
const ovMode = ref<'venue' | 'day'>('venue')
const venues = ref<VenueRow[]>([])
const loaded = ref(false)

// ── 日期（本地时区，勿用 toISOString） ──
function iso(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = iso(new Date())
const date = ref(today)
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const days = Array.from({ length: 14 }, (_, i) => {
  const d = new Date(); d.setDate(d.getDate() + i)
  return { iso: iso(d), week: i === 0 ? '今天' : WEEK[d.getDay()], day: d.getDate() }
})
const d3 = computed(() => [-1, 0, 1].map((n) => {
  const d = new Date(); d.setDate(d.getDate() + n)
  return { iso: iso(d), label: n === -1 ? '昨天' : n === 0 ? '今天' : '明天' }
}))
function weekCN(ds: string) { return WEEK[new Date(ds + 'T12:00:00').getDay()] }
function pickDate(d: string) { date.value = d; load() }

async function load() {
  venues.value = await api<VenueRow[]>(`/api/venue/list?date=${date.value}`).catch(() => [])
  loaded.value = true
  // 照片鉴权流式直取（同微光/采购附件模式）；无照片用纹理占位
  await Promise.all(venues.value.filter((v) => v.photoUrl && !v.imgUrl).map(async (v) => {
    try { v.imgUrl = URL.createObjectURL(await fetchBlob(v.photoUrl!)) } catch { /* 单张缺失不阻塞 */ }
  }))
}

const filtered = computed(() => {
  const k = kw.value.trim()
  return k ? venues.value.filter((v) => v.name.includes(k) || v.location.includes(k)) : venues.value
})
/** 按会议室视图的搜索（主题/预约人/手机号）：命中预约的场地整卡保留 */
const ovFiltered = computed(() => {
  const k = ovKw.value.trim()
  if (!k) return venues.value
  return venues.value.filter((v) => v.name.includes(k) || v.bookings.some((b) =>
    b.reason.includes(k) || b.applicantName.includes(k) || (b.phone || '').includes(k)))
})
function ovBookings(v: VenueRow): Bk[] {
  const k = ovKw.value.trim()
  if (!k) return v.bookings
  return v.bookings.filter((b) => b.reason.includes(k) || b.applicantName.includes(k) || (b.phone || '').includes(k))
}

// ── 时段条（06:00~23:00 共 34 个半小时格） ──
const SLOTS = 34, OPEN_MIN = 6 * 60
function bkRange(b: Bk): [number, number] {
  // 旧单无起止时间=全天
  if (!b.start || !b.end) return [0, SLOTS - 1]
  const toIdx = (t: string) => Math.round((Number(t.slice(0, 2)) * 60 + Number(t.slice(3)) - OPEN_MIN) / 30)
  return [Math.max(0, toIdx(b.start)), Math.min(SLOTS - 1, toIdx(b.end) - 1)]
}
/** 每格状态类：ok=已批(青绿) pend=待审(黄) 空=空闲 */
function slotClasses(v: VenueRow): string[] {
  const arr = new Array<string>(SLOTS).fill('')
  for (const b of v.bookings ?? []) {
    const [s, e] = bkRange(b)
    for (let i = s; i <= e; i++) arr[i] = b.state === 'APPROVED' ? 'ok' : arr[i] === 'ok' ? 'ok' : 'pend'
  }
  return arr
}
/** 场地状态标签：有已批=使用中 / 仅待审=审批中 / 空=空闲中 */
function stateOf(v: VenueRow) {
  const bs = v.bookings ?? []
  if (bs.some((b) => b.state === 'APPROVED')) return 'use'
  return bs.length ? 'pend' : 'free'
}
const nowPct = computed(() => {
  const n = new Date()
  return Math.min(100, Math.max(0, ((n.getHours() * 60 + n.getMinutes() - OPEN_MIN) / (17 * 60)) * 100))
})

function avaColor(name: string) {
  const cs = ['#0D9467', '#2563EB', '#D97706', '#7C3AED', '#DB2777', '#0891B2']
  let h = 0; for (const c of name || '?') h = (h * 31 + c.charCodeAt(0)) % 997
  return cs[h % cs.length]
}
function goDetail(id: number) { router.push(`/venue/${id}?date=${date.value}`) }

onMounted(() => {
  // 从详情页提交返回带 ?date= 恢复所选日期
  const qd = String(route.query.date || '')
  if (/^\d{4}-\d{2}-\d{2}$/.test(qd)) date.value = qd
  load()
})
</script>

<style scoped>
.v-tabs { margin-top: 4px; }
.v-search { padding: 8px 0; }
.v-search.flat { padding-top: 0; }

/* 日期条 */
.days { display: flex; gap: 4px; padding: 10px 12px; overflow-x: auto; }
.days .day { flex: none; width: 44px; border: none; background: none; border-radius: 12px;
  padding: 7px 0 6px; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.days .day small { font-size: 10px; color: var(--app-text-3); }
.days .day b { font-size: 16px; color: var(--app-text-1); font-weight: 600; }
.days .day.on { background: #0D9467; }
.days .day.on small, .days .day.on b { color: #fff; }
.days .day.today b { color: #0D9467; }
.days .day.on.today b { color: #fff; }

/* 场地卡 */
.v-card { margin-top: 12px; padding: 0; overflow: hidden; }
.v-photo { position: relative; height: 130px; background-size: cover; background-position: center; background-color: #E8EDF2; }
.v-photo img { width: 100%; height: 100%; object-fit: cover; display: block; }
.v-approve { position: absolute; left: 10px; bottom: 10px; font-size: 11px; font-weight: 600;
  color: #D97706; background: #FFFBEB; border: 1px solid #FDE68A; border-radius: 999px; padding: 2px 10px; }
.v-state { position: absolute; right: 10px; bottom: 10px; font-size: 11px; font-weight: 600;
  border-radius: 999px; padding: 2px 10px; }
.v-state.free { color: #0D9467; background: #ECFDF5; border: 1px solid #A7F3D0; }
.v-state.busy { color: #DC2626; background: #FEF2F2; border: 1px solid #FECACA; }
.v-body { padding: 10px 14px 12px; }
.v-name { margin: 0; font-size: 15px; font-weight: 700; color: var(--app-text-1); }
.v-meta { margin: 5px 0 0; font-size: 11px; color: var(--app-text-3); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.v-meta .van-icon { margin-left: 6px; }
.v-meta .van-icon:first-child { margin-left: 0; }

/* 时段条：34 格 = 06:00~23:00 */
.v-slots { display: flex; gap: 1px; margin-top: 10px; border-radius: 4px; overflow: hidden; }
.sg { flex: 1; height: 12px; background: #EDF1F5; }
.sg.ok { background: #10B981; }
.sg.pend { background: #FBBF24; }
.v-scale { display: flex; justify-content: space-between; margin-top: 3px; font-size: 9px; color: var(--app-text-3); }
.empty-card { margin-top: 12px; padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); }

/* 会议总览 */
.ov-head { margin-top: 10px; padding: 10px 12px 0; }
.ov-seg { display: flex; gap: 8px; }
.f-chip { border: 1px solid var(--app-card-border); background: none; border-radius: 999px;
  padding: 5px 20px; font-size: 13px; color: var(--app-text-2); }
.f-chip.on { border-color: #0D9467; color: #fff; background: #0D9467; font-weight: 600; }
.ov-row { display: flex; }
.d3 { display: flex; gap: 8px; margin: 10px 0 2px; }
.d3-btn { border: 1px solid var(--app-card-border); background: none; border-radius: 999px;
  padding: 4px 18px; font-size: 12px; color: var(--app-text-2); }
.d3-btn.on { border-color: #0D9467; color: #fff; background: #0D9467; font-weight: 600; }

.grp { margin-top: 10px; padding: 4px 14px; }
.grp-head { display: flex; align-items: center; gap: 8px; margin: 0; padding: 10px 0; cursor: pointer; }
.grp-name { font-size: 14px; font-weight: 700; color: #2563EB; }
.grp-count { font-size: 10px; font-weight: 600; color: #D97706; background: #FFFBEB;
  border: 1px solid #FDE68A; border-radius: 999px; padding: 1px 8px; }
.grp-head .van-icon { margin-left: auto; color: var(--app-text-3); font-size: 14px; }
.bk { display: flex; gap: 10px; padding: 10px 0; border-top: 1px solid var(--app-card-border); }
.bk-ava { flex: none; width: 38px; height: 38px; border-radius: 10px; color: #fff; font-size: 15px; font-weight: 600;
  display: flex; align-items: center; justify-content: center; }
.bk-body { flex: 1; min-width: 0; }
.bk .l1 { margin: 0; font-size: 12px; color: var(--app-text-3); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.bk .wk { background: #F1F5F9; border-radius: 999px; padding: 1px 8px; font-size: 10px; color: var(--app-text-2); }
.bk .tm { color: var(--app-text-1); font-size: 13px; }
.bk .l2 { margin: 4px 0 0; font-size: 13px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bk .l3 { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.bk-empty { padding: 22px 0; text-align: center; border-top: 1px solid var(--app-card-border); }
.bk-empty .van-icon { font-size: 30px; color: var(--app-text-3); }
.bk-empty p { margin: 6px 0 10px; font-size: 12px; color: var(--app-text-3); }
.go-book { border: 1px solid #0D9467; color: #0D9467; background: none; border-radius: 999px;
  padding: 6px 22px; font-size: 13px; font-weight: 600; }

/* 按日期时轴 */
.day-head { margin-top: 10px; padding: 12px 14px; }
.legend { display: flex; gap: 14px; margin-top: 10px; font-size: 11px; color: var(--app-text-3); }
.legend span { display: flex; align-items: center; gap: 4px; }
.lg { width: 12px; height: 8px; border-radius: 2px; display: inline-block; }
.lg.ok { background: #10B981; } .lg.pend { background: #FBBF24; }
.lg.free { background: #EDF1F5; border: 1px solid #E2E8F0; }
.lg.now { width: 2px; height: 12px; background: #EF4444; border-radius: 0; }
.ax-card { margin-top: 10px; padding: 4px 14px; }
.ax-row { display: grid; grid-template-columns: 92px 1fr 44px; align-items: center; gap: 10px;
  padding: 11px 0; cursor: pointer; }
.ax-row + .ax-row { border-top: 1px solid var(--app-card-border); }
.ax-name b { display: block; font-size: 13px; font-weight: 700; color: #0D9467; }
.ax-name span { font-size: 10px; color: #D97706; }
.ax-track { position: relative; display: flex; gap: 1px; border-radius: 4px; overflow: hidden; }
.ax-track .sg { height: 14px; }
.ax-now { position: absolute; top: -2px; bottom: -2px; width: 2px; background: #EF4444; }
.ax-date { margin: 0; font-size: 10px; color: var(--app-text-3); text-align: right; }
</style>
