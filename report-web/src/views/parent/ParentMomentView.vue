<template>
  <div class="app-page p-moment">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 多孩切换 -->
    <div v-if="children.length > 1" class="kids">
      <button v-for="c in children" :key="c.studentId" class="app-chip kid" type="button"
        :class="{ on: c.studentId === curId }" @click="switchKid(c.studentId)">{{ c.name }}</button>
    </div>

    <!-- 上传卡：拍照/选照片 + 备注（进孩子成长档案，家长+班主任可见） -->
    <div class="app-card sec tex-20 upload">
      <div class="u-photo" :class="{ empty: !photoUrl }" @click="pickPhoto">
        <img v-if="photoUrl" class="preview" :src="photoUrl" alt="">
        <div v-else class="hint">
          <span class="cam"><van-icon name="photograph" /></span>
          <p>记录孩子的闪光时刻</p>
          <p class="tip">拍照或选择照片上传</p>
        </div>
        <button v-if="photoUrl" class="retake" type="button" @click.stop="pickPhoto">
          <van-icon name="replay" /> 重拍
        </button>
      </div>
      <van-field v-model="note" type="textarea" rows="2" maxlength="500" show-word-limit
        placeholder="写一句此刻的亮点（选填），将记入孩子的成长档案" />
      <van-button round block type="primary" class="submit" :disabled="!photoUrl" :loading="submitting"
        loading-text="正在上传…" @click="submit">上传微光</van-button>
      <p class="u-privacy">照片进入孩子成长档案，本人与班主任可见，不进入班级公开墙</p>
    </div>

    <div class="app-sec">{{ curName }}的微光 · {{ rows.length }} 条</div>

    <van-skeleton v-if="loading" :row="6" style="padding: 14px" />
    <div v-else-if="!rows.length" class="app-card empty">还没有微光记录，拍下第一条吧</div>

    <!-- 微光流：照片 + 标签 + 记录人 + 备注；自己上传的可删 -->
    <div v-for="m in rows" :key="m.id" class="app-card item" :class="tex(m.id)">
      <MomentPhotos v-if="m.photoUrls?.length" :urls="m.photoUrls" @tap="onPhotoTap" />
      <div class="i-meta">
        <span v-if="m.source === 'EVAL_SYNC'" class="app-chip tag sync">加分 · {{ m.sceneTag }}</span>
        <template v-else>
          <span v-for="tg in m.sceneTag.split('/')" :key="tg" class="app-chip tag"
            :class="m.source === 'PARENT' ? 'mine' : 'tch'">{{ tg }}</span>
        </template>
        <span class="by">{{ m.teacherName }}</span>
        <span class="time">{{ fmtTime(m.createTime) }}</span>
        <!-- 批72e：删除收紧为发布 30 分钟内（后端同校验兜底） -->
        <button v-if="m.own && within30(m)" class="del" type="button" @click="remove(m)">删除</button>
      </div>
      <p v-if="m.note" class="note">{{ m.note }}</p>
    </div>

    <p class="app-foot">石实实验学校 · 石实SHINE</p>
    </van-pull-refresh>

    <!-- 浏览器形态的文件选择（App 形态走系统相机/相册） -->
    <input ref="fileInput" type="file" accept="image/jpeg,image/png" class="hide" @change="onFile" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showConfirmDialog, showImagePreview, showToast } from 'vant'
import { Capacitor } from '@capacitor/core'
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera'
import { api, apiForm } from '../../api/http'
import MomentPhotos from '../../components/MomentPhotos.vue'
import { texAt } from '../../utils/tex'

interface Kid { studentId: number; name: string }
interface Mom { id: number; note: string; sceneTag: string; source: string; own: boolean
  createTime: string; teacherName: string; photoUrls?: string[] }

const children = ref<Kid[]>([])
const curId = ref<number>()
const rows = ref<Mom[]>([])
const loading = ref(true)
const refreshing = ref(false)
const note = ref('')
const photoFile = ref<File>()
const photoUrl = ref('')
const submitting = ref(false)
const fileInput = ref<HTMLInputElement>()

const curName = computed(() => children.value.find((c) => c.studentId === curId.value)?.name ?? '')

// 批52③ 37 槽轮换池：本页基准 28、静态位 1（上传卡），轮换续在 29
const tex = (id: number) => texAt(29 + id)
function fmtTime(t: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : ''
}

/** 批72e：本人删除限发布 30 分钟内（超时按钮隐藏；后端同校验兜底） */
function within30(m: Mom) {
  return !!m.createTime && Date.now() - new Date(m.createTime).getTime() <= 30 * 60 * 1000
}

async function loadKids() {
  children.value = await api<Kid[]>('/api/parent/children')
  if (!curId.value && children.value.length) curId.value = children.value[0].studentId
}

async function load() {
  loading.value = true
  try {
    if (curId.value) {
      rows.value = await api<Mom[]>(`/api/parent/children/${curId.value}/moments?limit=50`)
    }
  } finally {
    loading.value = false
  }
}

async function reload() {
  try { await load() } finally { refreshing.value = false }
}

function switchKid(id: number) {
  if (id === curId.value) return
  curId.value = id
  load()
}

/* App 形态走系统相机/相册弹窗；浏览器形态走文件选择（同教师随手拍） */
async function pickPhoto() {
  if (Capacitor.isNativePlatform()) {
    try {
      const p = await Camera.getPhoto({
        quality: 80, width: 1600,
        resultType: CameraResultType.DataUrl,
        source: CameraSource.Prompt, allowEditing: false,
      })
      const blob = await (await fetch(p.dataUrl!)).blob()
      setPhoto(new File([blob], `photo.${p.format || 'jpg'}`, { type: blob.type || 'image/jpeg' }))
    } catch { /* 用户取消 */ }
  } else {
    fileInput.value?.click()
  }
}

function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) setPhoto(f)
  ;(e.target as HTMLInputElement).value = ''
}

function setPhoto(f: File) {
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoFile.value = f
  photoUrl.value = URL.createObjectURL(f)
}

async function submit() {
  if (!photoFile.value || !curId.value || submitting.value) return
  submitting.value = true
  try {
    const form = new FormData()
    form.append('studentId', String(curId.value))
    if (note.value.trim()) form.append('note', note.value.trim())
    form.append('photo', photoFile.value)
    await apiForm('/api/parent/moment', form)
    showToast('已记入孩子的成长档案')
    if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
    photoFile.value = undefined
    photoUrl.value = ''
    note.value = ''
    await load()
  } finally {
    submitting.value = false
  }
}

async function remove(m: Mom) {
  try {
    await showConfirmDialog({ title: '删除微光', message: '删除后不可恢复，确定删除这条记录吗？' })
  } catch { return }
  await api(`/api/parent/moment/${m.id}`, { method: 'DELETE' })
  showToast('已删除')
  await load()
}

/** 批62 多图：宫格点击 → 全屏轮播（MomentPhotos 已拉好 objectURL） */
function onPhotoTap(srcs: string[], idx: number) {
  showImagePreview({ images: srcs, startPosition: idx })
}

onMounted(async () => {
  await loadKids()
  await load()
})
</script>

<style scoped>
/* C 风格页面：结构复用第一版全局类，点缀色 C 令牌 */
.kids { display: flex; gap: 8px; padding: 14px 2px 0; flex-wrap: wrap; }
.kid { border: none; cursor: pointer; padding: 5px 14px; }
.kid.on { background: var(--shine-red-soft); color: var(--shine-red); font-weight: 600; }

.upload { padding: 12px; }
.u-photo { position: relative; border-radius: 12px; overflow: hidden; cursor: pointer; }
.u-photo.empty { border: 1px dashed var(--shine-line); background: var(--shine-bg); }
.u-photo .hint { padding: 26px 0; text-align: center; }
.hint .cam { display: inline-flex; align-items: center; justify-content: center;
  width: 46px; height: 46px; border-radius: 50%; background: var(--shine-gradient);
  color: var(--shine-gold); font-size: 22px; }
.hint p { margin: 8px 0 0; font-size: 13px; color: var(--app-text-2); }
.hint .tip { font-size: 11px; color: var(--app-text-3); }
.preview { display: block; width: 100%; max-height: 300px; object-fit: cover; }
.retake { position: absolute; right: 10px; bottom: 10px; display: flex; align-items: center; gap: 4px;
  border: none; border-radius: 999px; padding: 5px 12px; font-size: 12px;
  background: rgba(20, 27, 46, .72); color: #fff; cursor: pointer; }
.submit { margin-top: 10px; }
.u-privacy { margin: 8px 0 0; font-size: 11px; color: var(--app-text-3); text-align: center; }

.empty { text-align: center; color: var(--app-text-2); font-size: 13px; padding: 26px 0; }

.item { padding: 0; overflow: hidden; margin-bottom: 12px; }
.i-meta { display: flex; align-items: center; gap: 8px; padding: 10px 14px 0; }
.tag { flex: none; }
.tag.mine { color: var(--shine-red); background: #fff; border: 1px solid var(--shine-red-soft); }
.tag.tch { color: var(--shine-navy); background: #fff; border: 1px solid var(--shine-line); }
.tag.sync { color: var(--shine-navy); background: var(--shine-gold-soft); border: 1px solid transparent; font-weight: 600; }
.by { font-size: 12px; color: var(--app-text-2); }
.time { font-size: 11px; color: var(--app-text-3); }
.del { margin-left: auto; border: none; background: none; font-size: 12px;
  color: var(--app-text-3); cursor: pointer; padding: 0; }
.note { margin: 8px 14px 12px; padding: 8px 10px; font-size: 13px; line-height: 1.6;
  color: var(--app-text-1); background: var(--shine-bg); border-radius: 8px; }
.hide { display: none; }
</style>
