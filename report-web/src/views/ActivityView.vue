<template>
  <div class="page">
    <motion.h2 class="page-title" :initial="{ opacity: 0, x: -16 }" :animate="{ opacity: 1, x: 0 }"
      :transition="{ type: 'spring', stiffness: 400, damping: 32 }"><el-icon><Flag /></el-icon>扬长课程</motion.h2>
    <!-- 批40e：七类课程筛选（德育课程/扬长选修/体艺特训队/跨学科学习/做中学/社会实践/其他） -->
    <div class="cats">
      <el-radio-group v-model="cat" size="small">
        <el-radio-button v-for="c in ['全部', ...CATS]" :key="c" :value="c">{{ c }}</el-radio-button>
      </el-radio-group>
      <el-tag class="stat-chip">共 {{ filtered.length }} 个</el-tag>
    </div>
    <el-alert v-if="cat === '其他'" type="info" :closable="false" show-icon
      title="临时性活动请统一归入「其他」类（此处保留原活动管理用法）" style="margin-bottom: 10px" />

    <div class="toolbar">
      <el-button v-if="isAdmin" type="primary" @click="openEdit()">新建课程活动</el-button>
    </div>

    <el-table :data="filtered" highlight-current-row @row-click="select">
      <el-table-column label="封面" width="80">
        <template #default="{ row }">
          <el-image v-if="row.coverUrl" :src="covers[row.id]" fit="cover"
            style="width: 56px; height: 40px; border-radius: 4px" :preview-src-list="[covers[row.id]]"
            preview-teleported hide-on-click-modal @click.stop />
          <span v-else style="color: #c0c4cc; font-size: 12px">无</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="名称" min-width="150" />
      <el-table-column prop="type" label="课程类别" width="110" />
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmt(row.startTime) }}</template>
      </el-table-column>
      <el-table-column prop="place" label="地点" width="110" />
      <!-- 批72d：课程视频外链（在线播放对服务器负担太重，存外链点击跳转观看） -->
      <el-table-column label="视频" width="110">
        <template #default="{ row }">
          <el-link v-if="row.videoUrl" type="primary" :underline="false" :href="row.videoUrl" target="_blank"
            style="margin-right: 8px" @click.stop>观看</el-link>
          <el-button v-if="!isAdmin" link size="small" @click.stop="setVideo(row)">
            {{ row.videoUrl ? '改' : '设置' }}
          </el-button>
          <span v-if="isAdmin && !row.videoUrl" style="color: #c0c4cc; font-size: 12px">无</span>
        </template>
      </el-table-column>
      <el-table-column v-if="isAdmin" label="操作" width="120">
        <template #default="{ row }">
          <el-button link size="small" @click.stop="openEdit(row)">编辑</el-button>
          <el-button link size="small" @click.stop="del(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无{{ cat === '全部' ? '课程活动' : cat + '类记录' }}{{ isAdmin ? '，点击上方「新建课程活动」创建' : '' }}</template>
    </el-table>

    <el-card v-if="current" style="margin-top: 16px">
      <template #header>
        参与记录 — {{ current.title }}
        <div style="float: right; display: flex; gap: 8px">
          <el-button size="small" @click="openImport">Excel 导入</el-button>
          <el-button type="primary" size="small" @click="openSignup()">录参与</el-button>
        </div>
      </template>
      <el-table :data="signups">
        <el-table-column prop="studentName" label="学生" width="100" />
        <el-table-column label="签到" width="80">
          <template #default="{ row }">
            <el-tag :type="row.checkinTime ? 'success' : 'info'" size="small">
              {{ row.checkinTime ? '已签到' : '未签到' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="award" label="奖项" width="120" />
        <el-table-column prop="performance" label="表现" min-width="140" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link size="small" @click="openSignup(row)">编辑</el-button>
            <!-- 批40e 撤回：误录可删，扬长币入账自动冲正 -->
            <el-button link size="small" type="danger" @click.stop="delSignup(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>尚无参与记录</template>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑课程活动' : '新建课程活动'" width="480px">
      <el-form label-width="70px">
        <el-form-item label="名称" required><el-input v-model="editForm.title" /></el-form-item>
        <el-form-item label="课程类别" required>
          <el-select v-model="editForm.type" style="width: 100%">
            <el-option v-for="c in CATS" :key="c" :label="c" :value="c" />
          </el-select>
          <div v-if="editForm.type === '其他'" class="hint">临时性活动请归入此类</div>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="editForm.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="地点"><el-input v-model="editForm.place" /></el-form-item>
        <el-form-item label="视频链接">
          <el-input v-model="editForm.videoUrl" placeholder="https://（腾讯视频/B站等，选填）" clearable />
          <div class="hint">填课程视频的外链地址，保存后家长/老师点击「观看」跳转播放</div>
        </el-form-item>
        <el-form-item label="简介"><el-input v-model="editForm.intro" type="textarea" :rows="2" /></el-form-item>
        <el-form-item v-if="editForm.id" label="封面">
          <div style="display: flex; align-items: center; gap: 10px">
            <el-image v-if="covers[editForm.id]" :src="covers[editForm.id]" fit="cover"
              style="width: 84px; height: 56px; border-radius: 4px" :preview-src-list="[covers[editForm.id]]"
              preview-teleported />
            <input type="file" accept="image/jpeg,image/png" @change="pickCover" />
          </div>
          <div class="hint">jpg/png ≤5MB，保存时上传；重复上传覆盖</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!editForm.title || !editForm.type" @click="saveActivity">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="signupVisible" :title="signupForm.signupId ? '编辑参与' : '录参与'" width="480px">
      <el-form label-width="70px">
        <template v-if="!signupForm.signupId">
          <el-form-item label="学生" required>
            <el-select v-model="signupForm.studentId" filterable remote :remote-method="searchStudents"
              :loading="searching" clearable style="width: 100%" placeholder="输入姓名或学号搜索（全校）">
              <el-option v-for="s in students" :key="s.id" :label="`${s.name}（${s.className}）`" :value="s.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="签到"><el-switch v-model="signupForm.checkin" /></el-form-item>
        <el-form-item label="奖项"><el-input v-model="signupForm.award" placeholder="如：一等奖（附扬长币必填）" /></el-form-item>
        <el-form-item label="表现"><el-input v-model="signupForm.performance" /></el-form-item>
        <el-form-item v-if="!signupForm.signupId" label="扬长币">
          <el-input-number v-model="signupForm.coin" :min="0" :disabled="!signupForm.award" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="signupVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!signupForm.signupId && !signupForm.studentId" @click="saveSignup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批72：Excel 批量导入参与（先选课，单班一表，模板预填名册） -->
    <el-dialog v-model="importVisible" title="Excel 导入参与" width="520px">
      <el-form label-width="70px">
        <el-form-item label="班级">
          <el-select v-model="importClassId" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板">
          <el-button size="small" :disabled="!importClassId" @click="downloadTpl">下载模板（预填本班名册）</el-button>
        </el-form-item>
        <el-form-item label="文件">
          <input type="file" accept=".xlsx"
            @change="(e: Event) => (importFile = (e.target as HTMLInputElement).files?.[0] ?? null)" />
          <div class="hint">每行一名学生：签到（是/否）、奖项、表现、扬长币（>0 须填奖项）；学号须为本校在读学号（可跨班），不匹配整单拒绝，已录过的行跳过</div>
        </el-form-item>
      </el-form>
      <el-alert v-if="importResult" :type="importResult.skipped.length ? 'warning' : 'success'" :closable="false"
        style="margin-top: 4px">
        <template #title>
          已导入 {{ importResult.saved }} 条<template v-if="importResult.skipped.length">，{{ importResult.skipped.length }} 条跳过：</template>
        </template>
        <div v-for="(s, i) in importResult.skipped" :key="i" style="font-size: 12px; line-height: 1.8">
          第{{ s.row }}行 {{ s.name || s.studentNo }}：{{ s.reason }}
        </div>
      </el-alert>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { motion } from 'motion-v'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../api/http'
import { saveFile } from '../api/nativeShare'
import { useAuthStore } from '../stores/auth'

/** 批40e：扬长课程七类（与后端 ActivityController.CATEGORIES 一致；「其他」=临时性活动） */
const CATS = ['德育课程', '扬长选修', '体艺特训队', '跨学科学习', '做中学', '社会实践', '其他']

interface Activity { id: number; title: string; type?: string; startTime?: string; place?: string; intro?: string; coverUrl?: string; videoUrl?: string }
interface Signup { signupId: number; studentId: number; studentName: string; checkinTime?: string; award?: string; performance?: string }

const auth = useAuthStore()
const isAdmin = auth.role === 'ADMIN'

const activities = ref<Activity[]>([])
const cat = ref('全部')
const filtered = computed(() =>
  cat.value === '全部' ? activities.value : activities.value.filter((a) => a.type === cat.value),
)
const current = ref<Activity>()
const signups = ref<Signup[]>([])
const classes = ref<{ id: number; name: string }[]>([])
const students = ref<{ id: number; name: string; studentNo: string; className: string }[]>([])
const searching = ref(false)
const covers = ref<Record<number, string>>({})
const coverFile = ref<File | null>(null)

const editVisible = ref(false)
const editForm = ref<Partial<Activity>>({})
const signupVisible = ref(false)
const signupForm = ref<{ signupId?: number; studentId?: number; checkin: boolean; award: string; performance: string; coin: number }>(
  { checkin: false, award: '', performance: '', coin: 0 },
)

function fmt(s?: string) {
  return (s || '').replace('T', ' ').slice(0, 16)
}

async function loadActivities() {
  activities.value = await api('/api/activity/list')
  await loadCovers()
  if (current.value) {
    current.value = activities.value.find((a) => a.id === current.value!.id)
    await loadSignups()
  }
}

/** 封面走带 JWT 的二进制接口，转 object URL 供缩略图/预览 */
async function loadCovers() {
  for (const a of activities.value) {
    if (a.coverUrl && !covers.value[a.id]) {
      try {
        const blob = await fetchBlob(`/api/activity/${a.id}/cover`)
        covers.value[a.id] = URL.createObjectURL(blob)
      } catch {
        /* 已删除的封面对象忽略 */
      }
    }
  }
}

async function select(row: Activity) {
  current.value = row
  await loadSignups()
}

async function loadSignups() {
  if (!current.value) return
  signups.value = await api(`/api/activity/${current.value.id}/signups`)
}

function openEdit(a?: Activity) {
  editForm.value = a ? { ...a } : {}
  coverFile.value = null
  editVisible.value = true
}

/** 批72e：封面超 5MB 当场提示（后端同校验，这里老师立即知道原因） */
function pickCover(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  if (f && f.size > 5 * 1024 * 1024) {
    ElMessage.warning('封面图片不能超过 5MB，请压缩后重选')
    ;(e.target as HTMLInputElement).value = ''
    coverFile.value = null
    return
  }
  coverFile.value = f
}

async function saveActivity() {
  const f = editForm.value
  if (f.id) {
    await api(`/api/activity/${f.id}`, { method: 'PUT', json: f })
    if (coverFile.value) {
      const form = new FormData()
      form.append('file', coverFile.value)
      const r = await apiForm<{ coverUrl: string }>(`/api/activity/${f.id}/cover`, form)
      ElMessage.success(`已保存，封面上传 ${r.coverUrl}`)
    } else {
      ElMessage.success('已保存')
    }
  } else {
    await api('/api/activity', { method: 'POST', json: f })
    ElMessage.success('已保存（封面请编辑时上传）')
  }
  editVisible.value = false
  await loadActivities()
}

async function del(a: Activity) {
  await ElMessageBox.confirm(`删除活动「${a.title}」？`, '提示', { type: 'warning' })
  await api(`/api/activity/${a.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  if (current.value?.id === a.id) current.value = undefined
  await loadActivities()
}

/** 批40e 撤回：删除参与记录（带扬长币的自动冲正扣回） */
async function delSignup(s: Signup) {
  if (!current.value) return
  await ElMessageBox.confirm(`删除 ${s.studentName} 的参与记录？已入账的扬长币将自动冲销`, '撤回参与', { type: 'warning' })
  await api(`/api/activity/${current.value.id}/signup/${s.signupId}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await loadSignups()
}

/** 批72d：非管理员教师/领导层维护课程视频外链（清空提交=移除；管理员走编辑表单） */
async function setVideo(a: Activity) {
  let url: string
  try {
    const r = await ElMessageBox.prompt('腾讯视频/B站等课程视频地址（http:// 或 https:// 开头，留空=移除）',
      '课程视频链接', { inputValue: a.videoUrl || '', inputPlaceholder: 'https://',
        inputPattern: /^$|^https?:\/\/.{3,490}$/, inputErrorMessage: '须以 http:// 或 https:// 开头' })
    url = r.value.trim()
  } catch { return }
  await api(`/api/activity/${a.id}/video`, { method: 'PUT', json: { videoUrl: url || undefined } })
  ElMessage.success('已保存')
  await loadActivities()
}

async function openSignup(s?: Signup) {
  signupForm.value = s
    ? { signupId: s.signupId, checkin: !!s.checkinTime, award: s.award || '', performance: s.performance || '', coin: 0 }
    : { checkin: false, award: '', performance: '', coin: 0 }
  if (!s) students.value = []
  signupVisible.value = true
}

/** 批73b 跨班选人：全校在读学生搜索（选修课面向全体学生，不再限本班） */
async function searchStudents(kw: string) {
  if (!current.value || !kw.trim()) {
    students.value = []
    return
  }
  searching.value = true
  try {
    students.value = await api(
      `/api/activity/${current.value.id}/students?keyword=${encodeURIComponent(kw.trim())}`,
    )
  } finally {
    searching.value = false
  }
}

/* ── 批72：Excel 批量导入参与（模板预填名册，结果逐行明细） ── */
const importVisible = ref(false)
const importClassId = ref<number>()
const importFile = ref<File | null>(null)
const importing = ref(false)
const importResult = ref<{ saved: number; skipped: { row: number; studentNo: string; name: string; reason: string }[] }>()

async function openImport() {
  if (!classes.value.length) {
    classes.value = await api('/api/meta/my-classes')
  }
  importClassId.value = classes.value[0]?.id
  importFile.value = null
  importResult.value = undefined
  importVisible.value = true
}

async function downloadTpl() {
  if (!current.value || !importClassId.value) return
  const blob = await fetchBlob(`/api/activity/${current.value.id}/import-template?classId=${importClassId.value}`)
  const cls = classes.value.find((c) => c.id === importClassId.value)?.name || ''
  await saveFile(blob, `参与导入模板_${current.value.title}_${cls}.xlsx`)
}

async function doImport() {
  if (!current.value || !importFile.value || importing.value) return
  importing.value = true
  try {
    const form = new FormData()
    form.append('file', importFile.value)
    importResult.value = await apiForm(`/api/activity/${current.value.id}/import`, form)
    await loadSignups()
  } catch { /* 整单拒绝等原因已由 http 层弹出 */ } finally {
    importing.value = false
  }
}

async function saveSignup() {
  if (!current.value) return
  const f = signupForm.value
  if (f.signupId) {
    await api(`/api/activity/${current.value.id}/signup/${f.signupId}`, {
      method: 'PUT',
      json: { award: f.award, performance: f.performance, checkin: f.checkin },
    })
  } else {
    const r = await api<{ termId: number | null }>(`/api/activity/${current.value.id}/signup`, {
      method: 'POST',
      json: {
        studentId: f.studentId, checkin: f.checkin, award: f.award,
        performance: f.performance, coin: f.coin || undefined,
      },
    })
    if (r.termId) ElMessage.success(`已记录，扬长币已入账（学期 #${r.termId}）`)
  }
  signupVisible.value = false
  await loadSignups()
}

onMounted(loadActivities)
</script>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.cats { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
</style>
