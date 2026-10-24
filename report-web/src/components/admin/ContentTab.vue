<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索标题" clearable style="width: 180px"
        @keyup.enter="load" @clear="load" />
      <el-button type="primary" @click="openCreate">新建</el-button>
    </div>

    <el-alert type="info" :closable="false" class="tip">
      面向家长：全校/指定班级（家长端「通知公告」Tab 查看）；面向教师：全体教师/指定年级（批66②
      教师也可在 App 直接发布，此处可统一编辑/下架/删除）。
    </el-alert>

    <div v-if="selected.length" class="batch-bar">
      已选 {{ selected.length }} 项：
      <el-button size="small" type="success" @click="batchStatus(1)">批量发布</el-button>
      <el-button size="small" @click="batchStatus(0)">批量下架</el-button>
      <el-button size="small" type="danger" @click="batchRemove">批量删除</el-button>
    </div>

    <el-table :data="rows" size="small" @selection-change="(rs: any[]) => (selected = rs)">
      <el-table-column type="selection" width="42" />
      <el-table-column label="封面" width="76">
        <template #default="{ row }">
          <img v-if="row.coverUrl && coverCache[row.id]" :src="coverCache[row.id]" class="thumb" />
          <span v-else class="no-cover">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <b>{{ row.title }}</b>
        </template>
      </el-table-column>
      <el-table-column label="范围" width="120">
        <template #default="{ row }">{{
          row.scope === 'ALL' ? '全校' : row.scope === 'STAFF' ? '全体教师'
            : row.scope === 'GRADE' ? (row.gradeName ?? '年级已删') : row.className ?? '班级已删'
        }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '已发布' : '未发布' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.publishTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 1" link type="success" @click="openReadStats(row)">
            回执
          </el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '下架' : '发布' }}
          </el-button>
          <el-button link type="danger" @click="removeOne(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 已读回执（批9 家长口径 / 批66② 教师口径） -->
    <el-dialog v-model="statsDlg" :title="`已读回执：${stats?.title ?? ''}`" width="440px">
      <template v-if="stats">
        <div class="stats-line">
          <b :style="{ color: stats.total && stats.read === stats.total ? '#67C23A' : '#E6A23C' }">
            {{ stats.read }} / {{ stats.total }}
          </b>
          <span>{{ statsAudience }}已读（{{ stats.total ? Math.round((stats.read / stats.total) * 100) : 0 }}%）</span>
        </div>
        <template v-if="stats.unread.length">
          <div class="sec">未读名单（{{ stats.unread.length }}）</div>
          <div class="unread">
            <el-tag v-for="(u, i) in stats.unread" :key="i" size="small" type="info" class="u-tag">
              {{ u.phone ? `${u.name}（${u.phone}）` : u.name }}
            </el-tag>
          </div>
        </template>
        <p v-else class="all-read">全部已读 🎉</p>
      </template>
    </el-dialog>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑内容' : '通知公告 · 新建'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="必填" />
        </el-form-item>
        <el-form-item label="封面图">
          <div class="cover-row">
            <input type="file" accept="image/jpeg,image/png"
              @change="(e: Event) => pickCover((e.target as HTMLInputElement).files?.[0] ?? null)" />
            <span v-if="coverUploading" class="cover-hint">上传中…</span>
            <span v-else-if="form.coverUrl" class="cover-hint ok">已上传封面</span>
            <el-button v-if="form.coverUrl" link type="danger" @click="form.coverUrl = ''">清除</el-button>
          </div>
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="支持换行的图文正文" />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="form.scope">
            <el-radio value="ALL">全校</el-radio>
            <el-radio value="CLASS">指定班级</el-radio>
            <el-radio value="STAFF">全体教师</el-radio>
            <el-radio value="GRADE">指定年级</el-radio>
          </el-radio-group>
          <el-select v-if="form.scope === 'CLASS'" v-model="form.classId" filterable
            placeholder="选择班级" style="width: 100%; margin-top: 6px">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select v-else-if="form.scope === 'GRADE'" v-model="form.gradeId" filterable
            placeholder="选择年级" style="width: 100%; margin-top: 6px">
            <el-option v-for="g in grades" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <template v-if="!editing">
          <el-button :disabled="!canSave" @click="save(0)">存为草稿</el-button>
          <el-button type="primary" :disabled="!canSave" @click="save(1)">保存并发布</el-button>
        </template>
        <el-button v-else type="primary" :disabled="!canSave" @click="save()">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'

/* 内容管理（批40a 内容型扬长课程下线，仅存通知公告；历史 PARENTING 数据不再展示） */
const type = 'NOTICE'

const rows = ref<any[]>([])
const selected = ref<any[]>([])
const keyword = ref('')
const classes = ref<{ id: number; name: string }[]>([])
const grades = ref<{ id: number; name: string }[]>([])

/* 封面缩略图：带 JWT 拉流 → objectURL（HTTP 缓存命中后不再走网络），tab 生命周期内复用 */
const coverCache = reactive<Record<number, string>>({})

const dialog = ref(false)
const editing = ref<any>(null)
const form = ref<any>({})
const coverUploading = ref(false)

const canSave = computed(() => form.value.title?.trim() &&
  (form.value.scope !== 'CLASS' || form.value.classId) &&
  (form.value.scope !== 'GRADE' || form.value.gradeId))

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}

async function load() {
  const qs = new URLSearchParams({ type, page: '1', size: '100' })
  if (keyword.value) qs.set('keyword', keyword.value)
  const d = await api<{ records: any[] }>(`/api/admin/content/list?${qs}`)
  rows.value = d.records
  loadCovers(d.records)
}

async function loadCovers(list: any[]) {
  for (const r of list) {
    if (r.coverUrl && !coverCache[r.id]) {
      try {
        const blob = await fetchBlob(`/api/content/file/${r.id}`)
        coverCache[r.id] = URL.createObjectURL(blob)
      } catch { /* 封面缺失不阻塞列表 */ }
    }
  }
}

function openCreate() {
  editing.value = null
  form.value = { type, title: '', coverUrl: '', videoUrl: '', content: '', scope: 'ALL' }
  dialog.value = true
  if (!grades.value.length) {
    api<{ id: number; name: string }[]>('/api/meta/grades').then((gs) => (grades.value = gs)).catch(() => {})
  }
}

function openEdit(row: any) {
  editing.value = row
  form.value = { ...row }
  dialog.value = true
}

async function pickCover(file: File | null) {
  if (!file) return
  coverUploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const r = await apiForm<{ coverUrl: string }>('/api/admin/content/upload', fd)
    form.value.coverUrl = r.coverUrl
  } finally {
    coverUploading.value = false
  }
}

/** status：新建时 0=草稿 1=发布；编辑时不传（发布态走列表按钮） */
async function save(status?: number) {
  if (editing.value) {
    await api(`/api/admin/content/${editing.value.id}`, { method: 'PUT', json: form.value })
    ElMessage.success('已保存')
  } else {
    await api('/api/admin/content/create', { method: 'POST', json: { ...form.value, status } })
    ElMessage.success(status === 1 ? '已发布' : '已存为草稿')
  }
  dialog.value = false
  await load()
}

async function toggleStatus(row: any) {
  if (row.status === 1) {
    await ElMessageBox.confirm(`下架「${row.title}」？家长端将立即不可见。`, '确认下架')
  }
  await api(`/api/admin/content/${row.id}/status`, { method: 'PUT', json: { status: row.status === 1 ? 0 : 1 } })
  ElMessage.success(row.status === 1 ? '已下架' : '已发布')
  await load()
}

async function removeOne(row: any) {
  await ElMessageBox.confirm(`删除「${row.title}」？封面文件一并删除，不可恢复。`, '确认')
  await api(`/api/admin/content/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
}

async function batchStatus(status: number) {
  await ElMessageBox.confirm(`${status === 1 ? '发布' : '下架'}选中的 ${selected.value.length} 条？`, '确认')
  await api('/api/admin/content/batch/status', {
    method: 'PUT', json: { ids: selected.value.map((r) => r.id), status },
  })
  ElMessage.success('已批量操作')
  await load()
}

async function batchRemove() {
  await ElMessageBox.confirm(`删除选中的 ${selected.value.length} 条？不可恢复。`, '确认')
  await api('/api/admin/content/batch', { method: 'DELETE', json: { ids: selected.value.map((r) => r.id) } })
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await load()
  classes.value = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
})

/* 已读回执（批9 家长口径；批66② STAFF/GRADE 走教师口径新端点） */
const statsDlg = ref(false)
const stats = ref<{ title: string; total: number; read: number; unread: { name: string; phone?: string }[] } | null>(null)
const statsAudience = ref('家长')

async function openReadStats(row: any) {
  if (row.scope === 'STAFF' || row.scope === 'GRADE') {
    statsAudience.value = '老师'
    stats.value = await api(`/api/content/notice/${row.id}/read-stats`)
  } else {
    statsAudience.value = '家长'
    stats.value = await api(`/api/admin/notice/${row.id}/read-stats`)
  }
  statsDlg.value = true
}
</script>

<style scoped>
.tip { margin-bottom: 12px; }
.batch-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
  padding: 8px 12px; background: var(--el-color-primary-light-9); border-radius: 6px;
  font-size: 13px; }
.thumb { width: 52px; height: 36px; object-fit: cover; border-radius: 4px; display: block; }
.no-cover { color: var(--el-text-color-placeholder); }
.cover-row { display: flex; align-items: center; gap: 10px; }
.cover-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.cover-hint.ok { color: var(--el-color-success); }
.stats-line { display: flex; align-items: baseline; gap: 10px; }
.stats-line b { font-size: 26px; }
.stats-line span { font-size: 13px; color: var(--el-text-color-secondary); }
.sec { margin: 12px 0 6px; font-size: 13px; font-weight: 600; }
.unread { display: flex; flex-wrap: wrap; gap: 6px; max-height: 260px; overflow-y: auto; }
.u-tag { font-weight: 400; }
.all-read { margin: 14px 0 4px; text-align: center; color: var(--el-color-success); font-size: 14px; }
</style>
