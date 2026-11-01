<template>
  <div>
    <div class="toolbar">
      <el-select v-model="classId" placeholder="全部班级" clearable style="width: 160px" @change="load">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="姓名/学号" style="width: 180px" clearable @change="load" />
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 110px" @change="load">
        <el-option label="在读" value="在读" /><el-option label="转出" value="转出" /><el-option label="毕业" value="毕业" />
      </el-select>
      <el-button type="primary" @click="openCreate">新建学生</el-button>
      <el-button @click="openImport">导入学生</el-button>
    </div>

    <el-table :data="records" size="small">
      <el-table-column prop="studentNo" label="学号" width="110" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="性别" width="60">
        <template #default="{ row }">{{ row.gender === 'M' ? '男' : row.gender === 'F' ? '女' : '' }}</template>
      </el-table-column>
      <el-table-column label="班级" width="130">
        <template #default="{ row }">{{ className(row.classId) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.status !== '在读'" size="small" type="info">{{ row.status }}</el-tag>
          <span v-else>在读</span>
        </template>
      </el-table-column>
      <el-table-column label="照片" width="70">
        <template #default="{ row }">
          <el-image v-if="photos[row.id]" :src="photos[row.id]" fit="cover"
            style="width: 36px; height: 36px; border-radius: 4px" :preview-src-list="[photos[row.id]]"
            preview-teleported hide-on-click-modal @click.stop />
          <span v-else style="color: #c0c4cc; font-size: 12px">无</span>
        </template>
      </el-table-column>
      <el-table-column prop="guardianName" label="家长" width="90" />
      <el-table-column prop="guardianPhone" label="家长电话" width="130" />
      <el-table-column label="宿舍" width="150">
        <template #default="{ row }">
          {{ dormText(row) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" layout="prev, pager, next" :total="total" :page-size="20"
      :current-page="page" @current-change="(p: number) => { page = p; load() }" style="margin-top: 10px" />

    <el-dialog v-model="dialog" :title="form.id ? '编辑学生' : '新建学生'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="学号"><el-input v-model="form.studentNo" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.gender" style="width: 120px">
            <el-option label="男" value="M" /><el-option label="女" value="F" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级">
          <el-select v-model="form.classId" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入学日期">
          <el-date-picker v-model="form.enrollDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 120px">
            <el-option label="在读" value="在读" /><el-option label="转出" value="转出" /><el-option label="毕业" value="毕业" />
          </el-select>
        </el-form-item>
        <el-form-item label="家长姓名"><el-input v-model="form.guardianName" /></el-form-item>
        <el-form-item label="家长电话"><el-input v-model="form.guardianPhone" /></el-form-item>
        <el-form-item label="宿舍">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input v-model="form.dormBuilding" placeholder="楼（如培英楼）" />
            <el-input v-model="form.dormRoom" placeholder="房号" />
            <el-input v-model="form.dormBed" placeholder="床位" />
          </div>
        </el-form-item>
        <el-form-item v-if="form.id" label="照片">
          <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap">
            <el-image v-if="photos[form.id]" :src="photos[form.id]" fit="cover"
              style="width: 56px; height: 56px; border-radius: 4px" :preview-src-list="[photos[form.id]]"
              preview-teleported />
            <input type="file" accept="image/jpeg,image/png" @change="(e: Event) => (photoFile = (e.target as HTMLInputElement).files?.[0] ?? null)" />
          </div>
          <div class="hint">jpg/png ≤5MB，保存时上传；重复上传覆盖</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="导入学生（Excel）" width="560px">
      <div style="margin-bottom: 10px">
        <el-radio-group v-model="importMode">
          <el-radio-button value="append">仅新增（新生）</el-radio-button>
          <el-radio-button value="replace">覆盖刷新（名册更新）</el-radio-button>
        </el-radio-group>
      </div>
      <div style="margin-bottom: 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6">
        <template v-if="importMode === 'append'">
          先下载模板，从第 2 行开始填写。班级名称必须与系统完全一致（当前有：{{ classNames.join('、') || '（先去组织管理建班）' }}）。
          逐行校验：合法行入库，问题行跳过并列出原因。
        </template>
        <template v-else>
          拿到新名册整表刷一遍：按<b>学号</b>匹配——已有的更新姓名/性别/班级/家长/宿舍（状态/照片不动），没有的新增；
          表中没有的在读学生<b>保持原状</b>（不会误删，如需批量转出请用「年级与班级」全班标记）。
          班级名称必须与系统完全一致。任一行有误则整体不导入。
        </template>
      </div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap">
        <el-button size="small" @click="downloadTemplate">下载模板</el-button>
        <input type="file" accept=".xlsx"
          @change="(e: Event) => (importFile = (e.target as HTMLInputElement).files?.[0] ?? null)" />
      </div>
      <el-alert v-if="importResult" :type="importResult.failed ? 'warning' : 'success'" :closable="false">
        <template v-if="importMode === 'replace'">
          新增 {{ importResult.inserted }} 人、更新 {{ importResult.updated }} 人；
          {{ importResult.notInFile }} 名在读学生不在表中，已保持原状
        </template>
        <template v-else>成功导入 {{ importResult.inserted }} 人</template>
        <template v-if="importResult.failed">，失败 {{ importResult.failed }} 行：
          <div v-for="e in importResult.errors" :key="e.row" style="font-size: 12px">
            第 {{ e.row }} 行：{{ e.reason }}
          </div>
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

const route = useRoute()
const classes = ref<any[]>([])
const records = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const classId = ref<number>()
const keyword = ref((route.query.kw as string) || '')
const statusFilter = ref('')
const dialog = ref(false)
const form = ref<any>({})
const photos = ref<Record<number, string>>({})
const photoFile = ref<File | null>(null)
const importDialog = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
/* 宿舍三列拼装：部分缺格不过滤会漏出 null 字样 */
function dormText(row: any) {
  const b = row.dormBuilding, r = row.dormRoom, d = row.dormBed
  if (!b && !r && !d) return ''
  return [b, r].filter(Boolean).join('') + (d ? ` / ${d}床` : '')
}
const importResult = ref<{ inserted: number; updated?: number; notInFile?: number; failed: number; errors: { row: number; reason: string }[] } | null>(null)
const importMode = ref<'append' | 'replace'>('append')

function className(id: number) {
  return classes.value.find((c: any) => c.id === id)?.name ?? id
}

async function load() {
  const qs = new URLSearchParams({ page: String(page.value), size: '20' })
  if (classId.value) qs.set('classId', String(classId.value))
  if (keyword.value) qs.set('keyword', keyword.value)
  if (statusFilter.value) qs.set('status', statusFilter.value)
  const d = await api<{ total: number; records: any[] }>(`/api/admin/student/list?${qs}`)
  total.value = d.total
  records.value = d.records
  await loadPhotos()
}

/** 列表内取有照片学生的缩略图（object URL，20 条/页） */
async function loadPhotos() {
  for (const r of records.value) {
    if (r.photoUrl) {
      try {
        const blob = await fetchBlob(`/api/admin/student/${r.id}/photo`)
        photos.value[r.id] = URL.createObjectURL(blob)
      } catch { /* 无照片或未上传，忽略 */ }
    }
  }
}

function openCreate() {
  form.value = { gender: 'M', status: '在读', classId: classId.value ?? classes.value[0]?.id }
  photoFile.value = null
  dialog.value = true
}

function openEdit(row: any) {
  form.value = { ...row }
  photoFile.value = null
  dialog.value = true
}

async function save() {
  if (form.value.id) {
    await api(`/api/admin/student/${form.value.id}`, { method: 'PUT', json: form.value })
    if (photoFile.value) {
      const fd = new FormData()
      fd.append('file', photoFile.value)
      await apiForm(`/api/admin/student/${form.value.id}/photo`, fd)
    }
  } else {
    await api('/api/admin/student', { method: 'POST', json: form.value })
  }
  ElMessage.success('已保存')
  dialog.value = false
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除学生 ${row.name}？已有成长数据的学生会被拒绝`, '确认')
  await api(`/api/admin/student/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
}

const classNames = computed(() => classes.value.map((c: any) => c.name))

function openImport() {
  importFile.value = null
  importResult.value = null
  importDialog.value = true
}

async function downloadTemplate() {
  const blob = await fetchBlob('/api/admin/student/import-template')
  await saveFile(blob, '新生导入模板.xlsx')
}

async function doImport() {
  if (!importFile.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    fd.append('mode', importMode.value)
    importResult.value = await apiForm('/api/admin/student/import', fd)
    ElMessage.success(importMode.value === 'replace'
      ? `刷新完成：新增 ${importResult.value.inserted} 人 / 更新 ${importResult.value.updated} 人`
      : `导入完成：成功 ${importResult.value.inserted} 人`)
    await load()
  } finally {
    importing.value = false
  }
}

onMounted(async () => {
  classes.value = await api<any[]>('/api/admin/class/list')
  await load()
})
</script>
