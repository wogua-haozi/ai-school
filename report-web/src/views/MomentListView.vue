<template>
  <div class="app-page mlist" :class="{ tall: filtered.length }">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 班级行 + 标签筛选 -->
    <div class="app-card tl gold tex-34 top">
      <van-cell title="班级" is-link :value="curClassName || '选择班级'" @click="clsOpen = true" />
      <div class="chips">
        <button class="chip" :class="{ on: tag === '' }" type="button" @click="tag = ''">全部</button>
        <button v-for="t in tags" :key="t" class="chip" :class="{ on: tag === t }" type="button"
          @click="tag = t">{{ t }}</button>
      </div>
    </div>

    <!-- 照片墙：2 列竖卡，图上场景胶囊，图下学生/日期/备注 -->
    <div v-if="filtered.length" class="wall">
      <div v-for="m in filtered" :key="m.id" class="cell app-card" :data-id="m.id">
        <div class="ph">
          <MomentPhotos :urls="m.photoUrls ?? [m.photoUrl]" @tap="onPhotoTap" />
          <span class="p-tag">{{ m.sceneTag }}</span>
        </div>
        <div class="info">
          <p class="names" @click="goStudent(m)">{{ studentNames(m) }}</p>
          <p class="meta">{{ fmtDate(m.createTime) }}<template v-if="m.teacherName"> · {{ m.teacherName }}</template></p>
          <p v-if="m.note" class="note">{{ m.note }}</p>
          <!-- 批65：误操作自救——本人可删可改文字（不限时；信号差重复上传的场景） -->
          <div v-if="isMine(m)" class="ops">
            <button class="op" type="button" @click="openEdit(m)">编辑</button>
            <button class="op danger" type="button" @click="del(m)">删除</button>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="app-card empty-card">
      <van-empty image-size="88" :description="tag ? '该场景还没有微光' : '本班还没有微光记录'" />
      <van-button round type="primary" class="go-cam" @click="goCam">
        <van-icon name="photograph" /> 拍下第一束微光
      </van-button>
    </div>

    <van-popup v-model:show="clsOpen" position="bottom" round>
      <van-picker title="选择班级" :columns="clsColumns" @confirm="onCls" @cancel="clsOpen = false" />
    </van-popup>

    <!-- 批65：本人改备注 -->
    <van-popup v-model:show="editOpen" position="bottom" round>
      <div class="edit-box">
        <p class="edit-tt">修改备注</p>
        <van-field v-model="editNote" type="textarea" rows="3" maxlength="500" show-word-limit
          placeholder="想对学生说的话（可留空）" />
        <van-button round block type="primary" :loading="saving" @click="saveNote">保存</van-button>
      </div>
    </van-popup>
    </van-pull-refresh>

    <!-- 吸底拍照钮（fixed 定位须在 pull-refresh 外：track 的 transform 会牵动 fixed 元素） -->
    <button v-if="filtered.length" class="fab-cam" type="button" @click="goCam">
      <van-icon name="photograph" /> 再拍一条
    </button>
    <PhotoPreview ref="photoPreview" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { api } from '../api/http'
import MomentPhotos from '../components/MomentPhotos.vue'
import PhotoPreview from '../components/PhotoPreview.vue'

/** 场景标签字典（后端 t_moment_tag，含教师自建） */
const sceneTags = ref<string[]>([])

interface Stu { id: number; name: string }
interface MomentItem {
  id: number; photoUrl: string; photoUrls?: string[]; sceneTag: string; note: string
  createTime?: string; teacherName?: string; teacherId?: number; students?: Stu[]
}

const route = useRoute()
const router = useRouter()
const classes = ref<{ id: number; name: string }[]>([])
const classId = ref<number>()
const moments = ref<MomentItem[]>([])
const tag = ref('')
const loading = ref(true)
const clsOpen = ref(false)
const refreshing = ref(false)
const photoPreview = ref<InstanceType<typeof PhotoPreview>>()

const tags = computed(() => sceneTags.value.filter((t) => moments.value.some((m) => m.sceneTag === t)))
const curClassName = computed(() => classes.value.find((c) => c.id === classId.value)?.name)
const clsColumns = computed(() => classes.value.map((c) => ({ text: c.name, value: c.id })))
const filtered = computed(() => (tag.value ? moments.value.filter((m) => m.sceneTag === tag.value) : moments.value))

/** Vant4 confirm 载荷：{selectedValues, selectedOptions, selectedIndexes}，取值走 selectedOptions */
function onCls(ev: { selectedOptions?: { value: number }[] }) {
  const value = ev.selectedOptions?.[0]?.value
  if (classId.value !== value) {
    classId.value = value
    load()
  }
  clsOpen.value = false
}

async function load() {
  if (!classId.value) return
  loading.value = true
  try {
    moments.value = await api<MomentItem[]>(`/api/moment/class?classId=${classId.value}&limit=50`)
  } finally {
    loading.value = false
  }
}

function studentNames(m: MomentItem) {
  const names = (m.students ?? []).map((s) => s.name)
  if (!names.length) return '班级瞬间'
  return names.length <= 3 ? names.join('、') : `${names.slice(0, 3).join('、')} 等${names.length}人`
}

function fmtDate(t?: string) {
  return t ? String(t).slice(0, 10) : ''
}

/** 点学生名进详情；仅一名学生时才跳（多人卡跳转歧义） */
function goStudent(m: MomentItem) {
  if (m.students?.length === 1) router.push(`/student/${m.students[0].id}`)
}

/** 批62 多图：宫格点击 → 全屏轮播（MomentPhotos 已带鉴权拉好 objectURL） */
function onPhotoTap(srcs: string[], idx: number) {
  photoPreview.value?.open(srcs, idx)
}

/** 下拉刷新：重拉当前班级微光 */
async function reload() {
  try { await load() } finally { refreshing.value = false }
}

function goCam() {
  router.push({ path: '/moment/new', query: { classId: String(classId.value ?? '') } })
}

/** 批65：误操作自救（本人判定用 /api/auth/me 的 id，比 teacherId） */
const myId = ref<number>()
function isMine(m: MomentItem) {
  return m.teacherId != null && m.teacherId === myId.value
}

const editOpen = ref(false)
const editNote = ref('')
const editId = ref<number>()
const saving = ref(false)
function openEdit(m: MomentItem) {
  editId.value = m.id
  editNote.value = m.note || ''
  editOpen.value = true
}
async function saveNote() {
  saving.value = true
  try {
    await api(`/api/moment/${editId.value}`, { method: 'PUT', json: { note: editNote.value } })
    editOpen.value = false
    showSuccessToast('已修改')
    await load()
  } finally {
    saving.value = false
  }
}
async function del(m: MomentItem) {
  try {
    await showConfirmDialog({ title: '删除微光', message: '删除这条微光？照片与已发的扬长币将一并撤销，不可恢复。' })
  } catch { return } // 取消删除
  await api(`/api/moment/${m.id}`, { method: 'DELETE' })
  showSuccessToast('已删除')
  await load()
}

onMounted(async () => {
  api<{ id: number }>('/api/auth/me').then((d) => { myId.value = d.id }).catch(() => { /* 拿不到则不显本人按钮 */ })
  api<{ id: number; name: string }[]>('/api/moment/tags')
    .then((ts) => { sceneTags.value = ts.map((t) => t.name) })
    .catch(() => { /* 标签加载失败不阻塞列表 */ })
  classes.value = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
  const fromQuery = Number(route.query.classId)
  classId.value = classes.value.find((c) => c.id === fromQuery)?.id ?? classes.value[0]?.id
  await load()
})
</script>

<style scoped>
.top { padding: 0 0 12px; }
/* 悬浮拍照钮遮挡最后一行 → 有数据时底部留白 */
.mlist.tall { padding-bottom: 88px; }
.top :deep(.van-cell) { padding: 12px 14px 8px; }
.chips { display: flex; gap: 8px; margin: 4px 14px 0; overflow-x: auto; scrollbar-width: none; }
.chips::-webkit-scrollbar { display: none; }
.chip { flex: none; padding: 6px 14px; border: 1px solid var(--app-card-border); border-radius: 999px;
  background: #fff; color: var(--app-text-2); font-size: 12px; cursor: pointer;
  -webkit-tap-highlight-color: transparent; }
.chip.on { border-color: #EA580C; background: #FDEEE2; color: #EA580C; font-weight: 600; }

/* 2 列照片墙 */
.wall { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.cell { overflow: hidden; cursor: default; }
/* 批62 多图：去掉固定 3/4 高度，宫格按片数自适应（1=大图 ≤340，4+=三列方格） */
.ph { position: relative; background: #F2F5FB; }
.p-tag { position: absolute; left: 8px; bottom: 8px; padding: 2px 9px; border-radius: 999px;
  background: rgba(13,22,50,.55); color: #fff; font-size: 10px; backdrop-filter: blur(4px); }
.info { padding: 9px 11px 10px; }
.names { margin: 0; font-size: 13px; font-weight: 600; color: var(--app-text-1);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; }
.meta { margin: 3px 0 0; font-size: 11px; color: var(--app-text-3); }
.note { margin: 4px 0 0; font-size: 12px; line-height: 1.5; color: var(--app-text-2);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.ops { display: flex; gap: 8px; margin-top: 7px; }
.op { flex: none; padding: 3px 13px; border: 1px solid var(--app-card-border); border-radius: 999px;
  background: #fff; color: var(--app-text-3); font-size: 11px; cursor: pointer;
  -webkit-tap-highlight-color: transparent; }
.op.danger { border-color: #FCA5A5; color: #DC2626; }
.edit-box { padding: 18px 16px calc(20px + var(--sab)); display: flex; flex-direction: column; gap: 12px; }
.edit-tt { margin: 0; font-size: 15px; font-weight: 600; color: var(--app-text-1); text-align: center; }

.empty-card { display: flex; flex-direction: column; align-items: center; gap: 6px;
  margin-top: 12px; padding-bottom: 18px; }
.go-cam { height: 40px; padding: 0 22px; border: none; font-size: 14px;
  background: linear-gradient(150deg, #F97316, #EA580C); }

/* 吸底拍照 */
.fab-cam { position: fixed; right: 16px; bottom: calc(20px + var(--sab));
  display: flex; align-items: center; gap: 5px; padding: 12px 18px; border: none;
  border-radius: 999px; background: linear-gradient(150deg, #F97316, #EA580C);
  color: #fff; font-size: 14px; font-weight: 600; cursor: pointer;
  box-shadow: 0 6px 18px rgba(234,88,12,.35); }
</style>
