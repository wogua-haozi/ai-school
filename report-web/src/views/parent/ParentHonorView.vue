<template>
  <div class="app-page p-honor">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 多孩切换 -->
    <div v-if="children.length > 1" class="kids">
      <button v-for="c in children" :key="c.studentId" class="app-chip kid" type="button"
        :class="{ on: c.studentId === curId }" @click="switchKid(c.studentId)">{{ c.name }}</button>
    </div>

    <!-- 我家孩子的荣誉：上传（待班主任确认）+ 列表 -->
    <div class="app-sec">{{ curName }}的荣誉 · {{ rows.length }} 条</div>
    <div class="app-card tex-11 up">
      <van-button round block plain type="primary" icon="photograph" :loading="uploading"
        loading-text="正在上传…" @click="pickPhoto">上传荣誉证书</van-button>
      <p class="tip">拍下孩子在校外获得的奖状证书，班主任确认后全校可见</p>
    </div>

    <van-skeleton v-if="loading" :row="4" style="padding: 14px" />
    <div v-else-if="!rows.length" class="app-card empty">还没有荣誉记录</div>
    <div v-for="h in rows" :key="h.id" class="app-card row" :class="tex(h.id)">
      <div class="r-main" @click="view(h)">
        <p class="r-title">
          {{ h.name || '待填写奖项名称' }}
          <van-tag v-if="h.level" plain type="warning" class="lv">{{ h.level }}</van-tag>
        </p>
        <p class="r-sub">
          <van-tag :type="h.confirmStatus === '已确认' ? 'primary' : 'default'" plain class="st">
            {{ h.confirmStatus === '已确认' ? '已确认' : '待班主任确认' }}</van-tag>
          <template v-if="h.issuer"> {{ h.issuer }}</template>
          <template v-if="h.honorDate"> · {{ h.honorDate }}</template>
        </p>
      </div>
      <div class="r-acts">
        <template v-if="h.source === 'PARENT' && h.confirmStatus === '待确认'">
          <button type="button" @click="openEdit(h)">编辑</button>
          <button type="button" class="danger" @click="remove(h)">删除</button>
        </template>
        <van-icon v-else name="arrow" class="r-arrow" />
      </div>
    </div>

    <!-- 全校荣誉墙（已确认，所有老师家长可见） -->
    <div class="app-sec">全校荣誉墙 · {{ wall.length }} 条</div>
    <div v-if="!wall.length" class="app-card empty">暂无已确认荣誉</div>
    <div v-for="w in wall" :key="w.id" class="app-card wall-row" @click="viewWall(w)">
      <div class="w-medal"><van-icon name="medal-o" /></div>
      <div class="w-main">
        <p class="r-title">
          {{ w.name }}
          <van-tag v-if="w.level" plain type="warning" class="lv">{{ w.level }}</van-tag>
        </p>
        <p class="r-sub">{{ w.studentName }} · {{ w.className }}<template v-if="w.honorDate"> · {{ w.honorDate }}</template></p>
      </div>
      <van-icon name="arrow" class="r-arrow" />
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>

    <!-- 编辑弹层（上传后/编辑待确认） -->
    <van-popup v-model:show="editOpen" position="bottom" round :style="{ maxHeight: '82%' }" class="pop">
      <div class="p-head">
        <b>荣誉信息</b>
        <small>班主任确认后全校可见</small>
      </div>
      <div class="p-body">
        <van-field v-model="edit.name" label="奖项名称" placeholder="必填，如：区游泳比赛金奖" />
        <van-field :model-value="edit.level" is-link readonly label="级别" placeholder="选择级别（可选）"
          @click="levelOpen = true" />
        <van-field v-model="edit.issuer" label="主办单位" placeholder="选填" />
        <van-field :model-value="edit.honorDate" is-link readonly label="获奖日期" placeholder="选择日期（可选）"
          @click="openDate" />
        <van-button round block type="primary" class="submit" :loading="saving" :disabled="!edit.name.trim()"
          @click="save">保存</van-button>
      </div>
    </van-popup>
    <van-popup v-model:show="levelOpen" position="bottom" round>
      <van-picker title="级别" :columns="LEVELS"
        @confirm="(ev: any) => { edit.level = ev.selectedOptions?.[0]?.text || ''; levelOpen = false }"
        @cancel="levelOpen = false" />
    </van-popup>
    <van-popup v-model:show="dateOpen" position="bottom" round>
      <van-date-picker title="获奖日期" v-model="dateBuf" :columns-type="['year', 'month', 'day']"
        :min-date="minDate" :max-date="maxDate" @confirm="onDateOk" @cancel="dateOpen = false" />
    </van-popup>

    <input ref="fileInput" type="file" accept="image/jpeg,image/png" class="hide" @change="onFile" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { Capacitor } from '@capacitor/core'
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera'
import { api, apiForm, fetchBlob } from '../../api/http'
import { openFile } from '../../api/nativeShare'
import { texAt } from '../../utils/tex'

interface Kid { studentId: number; name: string }
interface Honor { id: number; name: string; level?: string; issuer?: string; honorDate?: string
  source: string; confirmStatus: string }
interface WallRow extends Honor { studentName: string; className: string }

const LEVELS = ['国家级', '省级', '市级', '区级', '校级', '班级'].map((l) => ({ text: l, value: l }))
const minDate = new Date(2020, 0, 1)
const maxDate = new Date(2030, 11, 31)

const children = ref<Kid[]>([])
const curId = ref<number>()
const rows = ref<Honor[]>([])
const wall = ref<WallRow[]>([])
const loading = ref(true)
const refreshing = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()

const curName = computed(() => children.value.find((c) => c.studentId === curId.value)?.name ?? '')

// 编辑弹层
const editOpen = ref(false)
const edit = ref<Honor & { honorDate?: string }>({} as Honor)
const saving = ref(false)
const levelOpen = ref(false)
const dateOpen = ref(false)
const dateBuf = ref<string[]>([])

async function loadKid() {
  if (!curId.value) return
  loading.value = true
  try {
    rows.value = await api<Honor[]>(`/api/honor/list?studentId=${curId.value}`)
  } finally { loading.value = false }
}
async function loadWall() {
  wall.value = await api<WallRow[]>('/api/honor/wall').catch(() => [])
}
async function reload() {
  try { await Promise.all([loadKid(), loadWall()]) } finally { refreshing.value = false }
}
function switchKid(id: number) {
  if (id === curId.value) return
  curId.value = id
  loadKid()
}

// ── 上传（拍照/选图 → POST → 直接进编辑弹层手动填写） ──
async function pickPhoto() {
  if (!curId.value) return
  if (Capacitor.isNativePlatform()) {
    try {
      const p = await Camera.getPhoto({
        quality: 80, width: 1600,
        resultType: CameraResultType.DataUrl,
        source: CameraSource.Prompt, allowEditing: false,
      })
      const blob = await (await fetch(p.dataUrl!)).blob()
      await upload(new File([blob], `honor.${p.format || 'jpg'}`, { type: blob.type || 'image/jpeg' }))
    } catch { /* 用户取消 */ }
  } else {
    fileInput.value?.click()
  }
}
async function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (f) await upload(f)
}
async function upload(file: File) {
  uploading.value = true
  try {
    const form = new FormData()
    form.append('studentId', String(curId.value))
    form.append('file', file)
    const r = await apiForm<{ honorId: number; detail: string; parsed?: Record<string, string> }>(
      '/api/honor/upload', form)
    showToast(r.detail || '已上传，请填写荣誉信息')
    openEdit({
      id: r.honorId, name: r.parsed?.name || '', level: r.parsed?.level, issuer: r.parsed?.issuer,
      honorDate: r.parsed?.date, source: 'PARENT', confirmStatus: '待确认',
    })
    await loadKid()
  } catch { /* apiForm 已提示 */ } finally { uploading.value = false }
}

// ── 编辑待确认 ──
function openEdit(h: Honor) {
  edit.value = { ...h }
  editOpen.value = true
}
function openDate() {
  if (edit.value.honorDate) {
    dateBuf.value = edit.value.honorDate.split('-')
  } else { // 空 model 的 van-date-picker 会落 min-date（2020），须预置今天
    const now = new Date()
    dateBuf.value = [String(now.getFullYear()), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')]
  }
  dateOpen.value = true
}
function onDateOk() {
  edit.value.honorDate = dateBuf.value.join('-')
  dateOpen.value = false
}
async function save() {
  saving.value = true
  try {
    await api(`/api/honor/${edit.value.id}`, {
      method: 'PUT',
      json: { name: edit.value.name.trim(), level: edit.value.level || null, issuer: edit.value.issuer || null, honorDate: edit.value.honorDate || null },
    })
    showEditDone()
    editOpen.value = false
    await loadKid()
  } catch { /* api 已提示 */ } finally { saving.value = false }
}
function showEditDone() {
  showToast('已保存，待班主任确认后全校可见')
}
async function remove(h: Honor) {
  try {
    await showConfirmDialog({ title: '删除荣誉', message: `删除「${h.name || '未命名'}」？` })
  } catch { return }
  await api(`/api/honor/${h.id}`, { method: 'DELETE' })
  showToast('已删除')
  loadKid()
}

// ── 证书原件预览（图片/PDF 同教师端 openFile） ──
async function view(h: Honor) {
  try {
    const blob = await fetchBlob(`/api/honor/file/${h.id}`)
    await openFile(blob, h.name || `荣誉_${h.id}`)
  } catch { /* 已提示 */ }
}
async function viewWall(w: WallRow) {
  try {
    const blob = await fetchBlob(`/api/honor/file/${w.id}`)
    await openFile(blob, w.name || `荣誉_${w.id}`)
  } catch { /* 已提示 */ }
}

// 批52③ 37 槽轮换池：本页基准 19、静态位 1（上传卡=tex-20），轮换续在 21 起避撞
const tex = (id: number) => texAt(20 + id)

onMounted(async () => {
  children.value = await api<Kid[]>('/api/parent/children').catch(() => [])
  if (children.value.length) curId.value = children.value[0].studentId
  await reload()
})
</script>

<style scoped>
.kids { display: flex; gap: 8px; margin: 12px 0 0; }
.kid.on { background: var(--shine-navy); color: #fff; }

.up { padding: 14px 12px; margin-top: 12px; }
.tip { margin: 10px 2px 0; font-size: 11px; color: var(--app-text-3); }

.empty { padding: 26px 0; text-align: center; color: var(--app-text-3); font-size: 13px; }

.row, .wall-row { display: flex; align-items: center; gap: 10px; padding: 12px 14px; margin-top: 10px; }
.r-main, .w-main { flex: 1; min-width: 0; }
.r-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lv { margin-left: 6px; flex: none; }
.r-sub { margin: 5px 0 0; font-size: 11px; color: var(--app-text-3); }
.st { margin-right: 6px; }
.r-acts { flex: none; display: flex; gap: 10px; }
.r-acts button { border: none; background: none; font-size: 13px; color: var(--app-blue); padding: 4px 0; }
.r-acts button.danger { color: #EF4444; }
.r-arrow { color: var(--app-text-3); }

.w-medal { flex: none; width: 38px; height: 38px; border-radius: 12px; display: flex;
  align-items: center; justify-content: center; background: #FBF3DF; }
.w-medal .van-icon { font-size: 20px; color: #B07A1C; }

.pop { padding-bottom: 14px; }
.p-head { padding: 16px 18px 6px; }
.p-head b { font-size: 16px; display: block; }
.p-head small { display: block; margin-top: 3px; font-size: 11px; color: var(--app-text-3); }
.p-body { padding: 0 12px; }
.submit { margin: 14px 0 4px; }
</style>
