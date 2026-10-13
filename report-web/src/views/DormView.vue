<template>
  <div class="app-page dorm">
    <!-- 宿管查询（全体教师：按宿舍楼/房/床查学生，应急找人场景） -->
    <div class="app-card tex-10 query">
      <div class="f-row">
        <van-field v-model="building" is-link readonly label="宿舍楼" :placeholder="building || '全部'"
          class="q-in" right-icon="arrow" @click="bOpen = true" />
        <van-field v-model="room" label="房号" placeholder="如 108" class="q-in room" type="digit" maxlength="6" />
        <van-field v-model="bed" label="床号" placeholder="如 9" class="q-in bed" type="digit" maxlength="3" />
      </div>
      <van-field v-model="q" label="姓名/学号" placeholder="支持任一关键字" class="q-in"
        @update:model-value="debouncedLoad" />
      <van-button round block type="primary" size="small" class="go" @click="load">查询</van-button>
      <p class="tip">共 {{ total }} 名学生<template v-if="!building && !room && !bed && !q">（未加条件默认全量，建议先选宿舍楼）</template></p>
    </div>

    <!-- 学生列表 -->
    <div class="app-card tex-11 list">
      <van-skeleton v-if="!loaded" :row="6" />
      <div v-else-if="!rows.length" class="empty">没有匹配的学生（宿舍信息由名册导入，个别缺失可联系管理员订正）</div>
      <div v-for="s in rows" :key="s.id" class="row">
        <div class="avatar">{{ s.name?.charAt(0) }}</div>
        <div class="r-body">
          <p class="r-title"><b>{{ s.name }}</b><span class="cls">{{ s.className }}</span>
            <span class="gen">{{ s.gender === 'M' ? '男' : s.gender === 'F' ? '女' : '' }}</span></p>
          <p class="r-meta">{{ s.studentNo }} · {{ dormText(s) }}</p>
        </div>
        <div class="r-side">
          <a v-if="isDorm" class="tel" @click.stop="router.push(`/student/${s.id}`)">
            <van-icon name="edit" /><span>订正</span>
          </a>
          <a v-if="s.guardianPhone" :href="'tel:' + s.guardianPhone" class="tel" @click.stop>
            <van-icon name="phone-o" /><span>家长</span>
          </a>
        </div>
      </div>
      <div v-if="rows.length && rows.length < total" class="more">仅显示前 {{ rows.length }} 条，请加条件缩小范围</div>
    </div>

    <!-- 宿舍楼下拉（数据源=在读学生去重楼名） -->
    <van-popup v-model:show="bOpen" position="bottom" round>
      <van-picker title="宿舍楼" :columns="buildingCols" @confirm="onBuilding" @cancel="bOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
/* 批60 宿管自治：生活老师可直达学生详情订正宿舍（其他教师数据权限不含外班学生，不放开） */
const isDorm = computed(() => auth.role === 'DORM')

/* 宿舍三列拼装：部分缺格不漏「室/床」空壳，全空=未安排住宿（与详情页同款口径） */
function dormText(s: DormRow) {
  const b = s.dormBuilding, r = s.dormRoom, d = s.dormBed
  if (!b && !r && !d) return '未安排住宿'
  return [b, r].filter(Boolean).join(' ') + (d ? ` / ${d}床` : '')
}

interface DormRow {
  id: number; studentNo: string; name: string; gender?: string; className?: string
  dormBuilding?: string; dormRoom?: string; dormBed?: string; guardianPhone?: string
}

const building = ref('')
const bOpen = ref(false)
const buildings = ref<string[]>([])
const room = ref('')
const bed = ref('')
const q = ref('')
const rows = ref<DormRow[]>([])
const total = ref(0)
const loaded = ref(false)

/* Vant4 picker 列须对象数组（批8.6 踩坑）；空时占位「全部」避免空列 */
const buildingCols = computed(() => [
  { text: '全部', value: '' },
  ...buildings.value.map((b) => ({ text: b, value: b })),
])

async function loadBuildings() {
  buildings.value = await api<string[]>('/api/student/dorm/buildings')
}

async function load() {
  const qs = new URLSearchParams()
  if (building.value) qs.set('building', building.value)
  if (room.value.trim()) qs.set('room', room.value.trim())
  if (bed.value.trim()) qs.set('bed', bed.value.trim())
  if (q.value.trim()) qs.set('q', q.value.trim())
  const d = await api<{ total: number; records: DormRow[] }>(`/api/student/dorm?${qs}&size=100`)
  total.value = d.total
  rows.value = d.records
  loaded.value = true
}

let timer: ReturnType<typeof setTimeout> | undefined
function debouncedLoad() {
  clearTimeout(timer)
  timer = setTimeout(load, 350)
}

function onBuilding({ selectedOptions }: { selectedOptions: { text: string; value: string }[] }) {
  building.value = selectedOptions[0]?.value ?? ''
  bOpen.value = false
  load()
}

onMounted(async () => {
  await loadBuildings()
  load()
})
</script>

<style scoped>
.query { margin-top: 12px; padding: 8px 14px 12px; }
.f-row { display: flex; gap: 6px; }
.f-row .q-in { flex: 1; min-width: 0; padding: 6px 0; }
.f-row .room, .f-row .bed { flex: none; width: 96px; }
.q-in { padding: 6px 0; }
.go { margin-top: 8px; }
.tip { margin: 8px 0 0; font-size: 11px; color: var(--app-text-3); }

.list { padding: 6px 14px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 0; cursor: pointer; }
.row + .row { border-top: 1px solid var(--app-card-border); }
.avatar { flex: none; width: 40px; height: 40px; border-radius: 50%; display: flex;
  align-items: center; justify-content: center; background: var(--app-blue); color: #fff;
  font-size: 16px; font-weight: 700; }
.r-body { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; color: var(--app-text-1); display: flex; align-items: baseline; gap: 6px; }
.r-title b { font-weight: 600; }
.cls { font-size: 11px; color: var(--app-text-3); }
.gen { font-size: 11px; color: var(--app-text-3); }
.r-meta { margin: 3px 0 0; font-size: 12px; color: var(--app-text-2); }
.r-side { flex: none; display: flex; gap: 14px; }
.tel { display: flex; flex-direction: column; align-items: center; gap: 2px; text-decoration: none;
  font-size: 10px; color: var(--app-blue); cursor: pointer; }
.tel .van-icon { font-size: 18px; }
.empty { padding: 26px 0; text-align: center; font-size: 13px; color: var(--app-text-3); line-height: 1.6; }
.more { padding: 10px 0 12px; font-size: 11px; color: var(--app-text-3); text-align: center; }
</style>
