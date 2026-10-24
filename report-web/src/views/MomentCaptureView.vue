<template>
  <div class="app-page capture">
    <!-- 成功态：激励展示 -->
    <div v-if="done" class="app-card tl green done-card">
      <span class="done-icon"><van-icon name="checked" /></span>
      <img class="done-photo" :src="donePhoto" alt="">
      <h2>微光已点亮</h2>
      <p>已记入 {{ doneCount }} 位学生的成长档案，将在班级页「本周微光」与 TA 的成长档案中展示</p>
      <div class="done-acts">
        <van-button round plain type="primary" @click="reset">再拍一条</van-button>
        <van-button round type="primary"
          @click="$router.push({ path: '/moment', query: { classId: String(classId ?? '') } })">看看全部微光</van-button>
      </div>
    </div>

    <template v-else>
      <!-- ① 照片（批62 多图：拍照/相册，1~9 张） -->
      <div v-if="!photoFiles.length" class="app-card photo-card empty" @click="pickPhoto">
        <div class="empty-photo">
          <span class="cam"><van-icon name="photograph" /></span>
          <p>点击拍照 / 选择照片</p>
          <p class="tip">记录学生此刻的闪光瞬间（可多选，最多 9 张）</p>
        </div>
      </div>
      <div v-else class="app-card sec photos-card">
        <div class="app-sec" style="margin: 0 0 10px">照片（{{ photoFiles.length }}/9）</div>
        <div class="p-grid">
          <div v-for="(u, i) in previews" :key="i" class="p-cell">
            <img :src="u" alt="">
            <button class="p-del" type="button" @click.stop="removePhoto(i)">
              <van-icon name="cross" />
            </button>
          </div>
          <div v-if="photoFiles.length < 9" class="p-cell add" @click="pickPhoto">
            <van-icon name="plus" />
            <span>添加</span>
          </div>
        </div>
      </div>

      <!-- ② 场景标签（字典来自后端，可自建） -->
      <div class="app-card sec">
        <div class="app-sec" style="margin: 0 0 10px">场景标签</div>
        <div class="tags">
          <button v-for="t in sceneTags" :key="t.id" class="tag" :class="{ on: sceneTag === t.name }"
            type="button" @click="sceneTag = t.name">{{ t.name }}
            <!-- 批65：自建标签可删（建标签人本人；已被微光引用则后端拒绝） -->
            <van-icon v-if="t.mine" name="cross" class="tag-x" @click.stop="delTag(t)" />
          </button>
          <button class="tag add" :class="{ editing: tagAdding }" type="button" @click="startAddTag">
            <template v-if="!tagAdding"><van-icon name="plus" /> 新建</template>
            <template v-else>
              <input ref="tagInput" v-model="newTag" class="tag-input" maxlength="32"
                placeholder="标签名（≤32字）" @keyup.enter="confirmAddTag" @click.stop>
              <van-icon name="success" @click.stop="confirmAddTag" />
            </template>
          </button>
        </div>
      </div>

      <!-- ③ 关联学生 -->
      <div class="app-card sec">
        <van-cell title="班级" is-link :value="curClassName || '选择班级'" @click="clsOpen = true" />
        <div class="app-sec" style="margin: 8px 0 10px">闪光学生（{{ picked.size }}）</div>
        <div class="stu-search">
          <van-icon name="search" />
          <input v-model="stuKeyword" placeholder="搜索学生姓名...">
        </div>
        <div class="stu-grid">
          <button v-for="s in filteredStudents" :key="s.id" class="stu" :class="{ on: picked.has(s.id) }"
            type="button" @click="toggle(s.id)">
            <span class="ava" :style="{ background: avaColor(s.name) }">{{ s.name.charAt(0) }}</span>
            <span class="name">{{ s.name }}</span>
            <van-icon class="check" name="checked" />
          </button>
        </div>
        <div v-if="!students.length" class="stu-none">本班暂无学生</div>
        <div v-else-if="!filteredStudents.length" class="stu-none">没有匹配的学生</div>
      </div>

      <!-- ④ 备注 -->
      <div class="app-card sec">
        <van-field v-model="note" type="textarea" rows="2" maxlength="500" show-word-limit
          placeholder="写一句此刻的亮点（选填），将汇入学生的成长素材" />
      </div>

      <!-- 吸底提交：长学生列表滚动时按钮始终可达 -->
      <div class="submit-bar">
        <van-button round block type="primary" class="submit" :disabled="!canSubmit" :loading="submitting"
          loading-text="正在点亮…" @click="submit">点亮这束微光</van-button>
        <p class="submit-tip">照片存入学校服务器，仅校内教师可见</p>
      </div>
    </template>

    <!-- 浏览器形态的文件选择（App 形态走系统相机/相册；multiple 支持一次多选） -->
    <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple class="hide" @change="onFile" />

    <van-popup v-model:show="clsOpen" position="bottom" round>
      <van-picker title="选择班级" :columns="clsColumns" @confirm="onCls" @cancel="clsOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { Capacitor } from '@capacitor/core'
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera'
import { api, apiForm } from '../api/http'

/** 场景标签字典（后端 t_moment_tag，教师可自建） */
const sceneTags = ref<{ id: number; name: string; mine?: boolean }[]>([])
const tagAdding = ref(false)
const newTag = ref('')
const tagInput = ref<HTMLInputElement>()

const route = useRoute()
/* 批62 多图：photoFiles 1~9 张，previews 为对应 objectURL */
const photoFiles = ref<File[]>([])
const previews = ref<string[]>([])
const sceneTag = ref('')
const note = ref('')
const picked = ref(new Set<number>())
const submitting = ref(false)
const done = ref(false)
const donePhoto = ref('')
const doneCount = ref(0)

const classes = ref<{ id: number; name: string }[]>([])
const classId = ref<number>()
const students = ref<{ id: number; name: string }[]>([])
const clsOpen = ref(false)
const fileInput = ref<HTMLInputElement>()

const clsColumns = computed(() => classes.value.map((c) => ({ text: c.name, value: c.id })))
const curClassName = computed(() => classes.value.find((c) => c.id === classId.value)?.name)
const stuKeyword = ref('')
const filteredStudents = computed(() => {
  const k = stuKeyword.value.trim()
  return k ? students.value.filter((s) => s.name.includes(k)) : students.value
})
const canSubmit = computed(() => photoFiles.value.length > 0 && !!sceneTag.value && picked.value.size > 0)

/* App 形态走系统相机/相册弹窗（每次一张，点「添加」续拍）；浏览器形态走文件选择（可多选） */
async function pickPhoto() {
  if (Capacitor.isNativePlatform()) {
    try {
      const p = await Camera.getPhoto({
        quality: 80, width: 1600,
        resultType: CameraResultType.DataUrl,
        source: CameraSource.Prompt, allowEditing: false,
      })
      const blob = await (await fetch(p.dataUrl!)).blob()
      addPhotos([new File([blob], `photo.${p.format || 'jpg'}`, { type: blob.type || 'image/jpeg' })])
    } catch { /* 用户取消 */ }
  } else {
    fileInput.value?.click()
  }
}

function onFile(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  if (files.length) addPhotos(files)
  ;(e.target as HTMLInputElement).value = '' // 允许重选同一张
}

function addPhotos(files: File[]) {
  const room = 9 - photoFiles.value.length
  const take = files.slice(0, room)
  if (files.length > room) showToast('一次最多 9 张照片')
  photoFiles.value = [...photoFiles.value, ...take]
  previews.value = [...previews.value, ...take.map((f) => URL.createObjectURL(f))]
}

function removePhoto(i: number) {
  URL.revokeObjectURL(previews.value[i])
  photoFiles.value = photoFiles.value.filter((_, k) => k !== i)
  previews.value = previews.value.filter((_, k) => k !== i)
}

function toggle(id: number) {
  const s = new Set(picked.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  picked.value = s
}

/** Vant4 confirm 载荷：{selectedValues, selectedOptions, selectedIndexes}，取值走 selectedOptions */
function onCls(ev: { selectedOptions?: { value: number }[] }) {
  const value = ev.selectedOptions?.[0]?.value
  if (classId.value !== value) {
    classId.value = value
    picked.value = new Set()
    loadStudents()
  }
  clsOpen.value = false
}

async function loadStudents() {
  if (!classId.value) return
  const d = await api<{ records: { id: number; name: string }[] }>(
    `/api/student/list?classId=${classId.value}&page=1&size=100`)
  students.value = d.records
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const form = new FormData()
    for (const f of photoFiles.value) form.append('photos', f)
    form.append('classId', String(classId.value))
    form.append('sceneTag', sceneTag.value)
    if (note.value.trim()) form.append('note', note.value.trim())
    for (const id of picked.value) form.append('studentIds', String(id))
    await apiForm('/api/moment', form)
    donePhoto.value = previews.value[0]
    doneCount.value = picked.value.size
    done.value = true
  } catch (e) {
    showToast((e as Error).message || '提交失败')
  } finally {
    submitting.value = false
  }
}

/** 再拍一条：清空全部选择（班级保留；成功态照片由 done 卡展示，不回收） */
function reset() {
  done.value = false
  previews.value.forEach((u) => { if (u !== donePhoto.value) URL.revokeObjectURL(u) })
  photoFiles.value = []
  previews.value = []
  sceneTag.value = ''
  note.value = ''
  stuKeyword.value = ''
  picked.value = new Set()
}

const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
function avaColor(name: string) {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

async function loadTags() {
  sceneTags.value = await api<{ id: number; name: string; mine?: boolean }[]>('/api/moment/tags')
}

/** 批65：删自建标签（本人或管理员；已被微光引用则后端拒绝并提示条数） */
async function delTag(t: { id: number; name: string }) {
  try {
    await showConfirmDialog({ title: '删除标签', message: `删除自建标签「${t.name}」？未被微光使用才可删。` })
  } catch { return }
  try {
    await api(`/api/moment/tags/${t.id}`, { method: 'DELETE' })
    if (sceneTag.value === t.name) sceneTag.value = ''
    await loadTags()
    showToast('标签已删除')
  } catch { /* 后端 message 已由 http 层透出 */ }
}

function startAddTag() {
  tagAdding.value = true
  newTag.value = ''
  nextTick(() => tagInput.value?.focus())
}

async function confirmAddTag() {
  const name = newTag.value.trim()
  if (!name) { tagAdding.value = false; return }
  try {
    await api('/api/moment/tags', { method: 'POST', json: { name } })
    await loadTags()
    sceneTag.value = name          // 建好即选中
    tagAdding.value = false
    showToast('标签已创建')
  } catch { /* 后端 message 已由 http 层透出 */ }
}

onMounted(async () => {
  loadTags()
  classes.value = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
  const fromQuery = Number(route.query.classId)
  classId.value = classes.value.find((c) => c.id === fromQuery)?.id ?? classes.value[0]?.id
  await loadStudents()
})
</script>

<style scoped>
/* 照片卡：空态虚线引导；批62 多图=宫格预览 + 添加/删除 */
.photo-card { position: relative; overflow: hidden; min-height: 220px;
  display: flex; align-items: center; justify-content: center; cursor: pointer; }
.photo-card.empty { border: 1.5px dashed #B9C8E4; background: #F7F9FD; }
.empty-photo { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 40px 0; }
.cam { display: flex; align-items: center; justify-content: center; width: 56px; height: 56px;
  border-radius: 50%; background: linear-gradient(150deg, #8C1D23, #A8232B);
  color: #fff; font-size: 26px; box-shadow: 0 6px 16px rgba(30,58,138,.3); }
.empty-photo p { margin: 0; font-size: 14px; color: var(--app-text-2); }
.empty-photo .tip { font-size: 12px; color: var(--app-text-3); }

/* 多图宫格（采集页） */
.photos-card { padding: 14px 14px 12px; }
.p-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.p-cell { position: relative; aspect-ratio: 1; overflow: hidden; border-radius: 8px; }
.p-cell img { width: 100%; height: 100%; object-fit: cover; display: block; }
.p-cell.add { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
  border: 1.5px dashed #B9C8E4; background: #F7F9FD; color: var(--app-text-3);
  font-size: 12px; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.p-cell.add .van-icon { font-size: 22px; }
.p-del { position: absolute; top: 4px; right: 4px; display: flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border: none; border-radius: 50%; background: rgba(13,22,50,.55);
  color: #fff; font-size: 13px; cursor: pointer; }

.sec { margin-top: 12px; padding: 14px 14px 12px; }

/* 场景标签 chips */
.tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tag { display: inline-flex; align-items: center; gap: 3px; padding: 7px 16px;
  border: 1px solid var(--app-card-border); border-radius: 999px;
  background: #fff; color: var(--app-text-2); font-size: 13px; cursor: pointer;
  -webkit-tap-highlight-color: transparent; }
.tag-x { margin-right: -6px; padding: 4px; color: #C0C8D6; font-size: 13px; }
.tag-x:active { color: var(--shine-red); }
.tag.on { border-color: var(--app-blue); background: rgba(168,35,43,.08); color: var(--app-blue); font-weight: 600; }
.tag.add { display: inline-flex; align-items: center; gap: 4px; color: var(--app-text-2); border-style: dashed; }
.tag.add.editing { padding: 0 10px 0 12px; }
.tag-input { width: 110px; border: none; outline: none; background: transparent; font-size: 13px; padding: 0; }

/* 学生多选宫格 */
.stu-grid { display: flex; flex-wrap: wrap; gap: 10px 8px; }
.stu { position: relative; display: flex; align-items: center; gap: 6px; padding: 6px 12px 6px 6px;
  border: 1.5px solid var(--app-card-border); border-radius: 999px; background: #fff;
  cursor: pointer; -webkit-tap-highlight-color: transparent; }
.stu .ava { display: flex; align-items: center; justify-content: center; width: 26px; height: 26px;
  border-radius: 50%; color: #fff; font-size: 12px; font-weight: 600; }
.stu .name { font-size: 13px; color: var(--app-text-1); }
.stu .check { display: none; font-size: 14px; color: var(--app-blue); }
.stu.on { border-color: var(--app-blue); background: rgba(168,35,43,.08); }
.stu.on .check { display: block; }
.stu-none { padding: 14px 0 6px; font-size: 13px; color: var(--app-text-3); }
.stu-search { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; padding: 7px 12px;
  border-radius: 999px; background: #F2F5FB; color: var(--app-text-3); }
.stu-search input { flex: 1; border: none; outline: none; background: transparent;
  font-size: 13px; color: var(--app-text-1); }
.stu-search input::placeholder { color: var(--app-text-3); }

/* 吸底提交：渐隐承接页面背景，长列表滚动时按钮始终可达 */
.submit-bar { position: sticky; bottom: 0; z-index: 5; margin-top: 16px;
  padding: 14px 0 calc(8px + var(--sab));
  background: linear-gradient(180deg, rgba(244,246,251,0), #F4F6FB 42%); }
.submit { height: 46px; font-size: 16px; font-weight: 600; border: none;
  background: linear-gradient(150deg, #8C1D23, #A8232B); }
.submit-tip { margin: 8px 0 0; text-align: center; font-size: 11px; color: var(--app-text-3); }

/* 成功激励卡 */
.done-card { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 30px 22px; }
.done-icon { display: flex; align-items: center; justify-content: center; width: 56px; height: 56px;
  border-radius: 50%; background: #E8F6EF; color: #0D9467; font-size: 28px; }
.done-photo { width: 100%; height: 180px; object-fit: cover; border-radius: 12px; }
.done-card h2 { margin: 4px 0 0; font-size: 19px; font-weight: 800; color: var(--app-text-1); }
.done-card p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--app-text-2); text-align: center; }
.done-acts { display: flex; gap: 12px; margin-top: 10px; }
.done-acts .van-button { height: 40px; padding: 0 22px; }
.hide { display: none; }
</style>
