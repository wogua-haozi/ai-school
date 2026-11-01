<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openImport">导入走班分班表</el-button>
      <el-button @click="downloadTemplate">下载模板</el-button>
    </div>

    <el-table :data="walks" size="small">
      <el-table-column prop="gradeName" label="年级" width="90" />
      <el-table-column prop="subjectName" label="学科" width="90" />
      <el-table-column prop="name" label="走班名称" width="120" />
      <el-table-column prop="teacherName" label="教师" width="110" />
      <el-table-column prop="studentCount" label="人数" width="80" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRoster(row)">名册</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="rosterDlg" :title="`${viewing?.name ?? ''}（${viewing?.teacherName ?? ''}，${roster.length} 人）`" width="440px">
      <el-table :data="roster" size="small" max-height="420">
        <el-table-column prop="studentNo" label="学号" width="110" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="className" label="行政班" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="importDialog" title="导入走班分班表（Excel）" width="620px">
      <div style="margin-bottom: 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6">
        换班/调整都在这一个通道：先下载模板，两个工作表——「学生分班」每生一行（走班名称/学号/姓名），
        「老师分工」每个走班一行（年级名称/学科/走班名称/教师）。学年/学科名须与系统一致，教师填登录账号或姓名（重名改填账号）。
        导入按「年级+学科」整体替换：表内覆盖的走班与分班全部重建，其他学科不受影响；不在表内的学生视为退出走班。
      </div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 12px">
        <el-button size="small" @click="downloadTemplate">下载模板</el-button>
        <input type="file" accept=".xlsx"
          @change="(e: Event) => { importFile = (e.target as HTMLInputElement).files?.[0] ?? null; preview = null }" />
      </div>
      <el-alert v-if="preview && !preview.ok" type="error" :closable="false">
        校验未通过，共 {{ preview.errors.length }} 行问题，请修正后重新上传：
        <div v-for="(e, i) in preview.errors" :key="i" style="font-size: 12px">
          [{{ e.sheet }}] 第 {{ e.row }} 行：{{ e.reason }}
        </div>
      </el-alert>
      <el-alert v-else-if="preview" type="info" :closable="false">
        <div v-for="(s, i) in preview.scopes" :key="i" style="line-height: 1.8">
          <b>{{ gradeName(s.gradeId) }}·{{ subjectName(s.subjectId) }}</b>：
          {{ s.walkCount }} 个走班 / {{ s.studentCount }} 名学生
          <template v-if="s.newWalks.length">，新增走班 {{ s.newWalks.join('、') }}</template>
          <template v-if="s.removedWalks.length">，删除走班 {{ s.removedWalks.join('、') }}</template>
          <template v-if="s.teacherChanged">，{{ s.teacherChanged }} 个走班换教师</template>
          <template v-if="s.studentMoved">，{{ s.studentMoved }} 人换班</template>
          <template v-if="s.studentNewlyAssigned">，{{ s.studentNewlyAssigned }} 人新进</template>
          <template v-if="s.studentUnassigned">，{{ s.studentUnassigned }} 人退出</template>
        </div>
        <div style="margin-top: 4px">确认后将按上述差异整体替换。</div>
      </el-alert>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="doPreview">校验预览</el-button>
        <el-button type="danger" :disabled="!importFile || !preview?.ok" :loading="committing" @click="doCommit">
          确认导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

const walks = ref<any[]>([])
const grades = ref<{ id: number; name: string }[]>([])
const subjects = ref<{ id: number; name: string }[]>([])

function gradeName(id: number) { return grades.value.find((g) => g.id === id)?.name ?? id }
function subjectName(id: number) { return subjects.value.find((s) => s.id === id)?.name ?? id }

const rosterDlg = ref(false)
const viewing = ref<any>(null)
const roster = ref<any[]>([])
async function openRoster(row: any) {
  viewing.value = row
  roster.value = await api<any[]>(`/api/admin/walk-class/${row.id}/students`)
  rosterDlg.value = true
}

const importDialog = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const committing = ref(false)
const preview = ref<any>(null)

function openImport() {
  importFile.value = null
  preview.value = null
  importDialog.value = true
}

async function downloadTemplate() {
  const blob = await fetchBlob('/api/admin/walk-class/import-template')
  await saveFile(blob, '走班导入模板.xlsx')
}

async function doPreview() {
  if (!importFile.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    preview.value = await apiForm<any>('/api/admin/walk-class/import', fd)
  } finally {
    importing.value = false
  }
}

async function doCommit() {
  if (!importFile.value) return
  await ElMessageBox.confirm('按预览差异整体替换走班分班？替换后走班名册以本表为准。', '确认导入')
  committing.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    const d = await apiForm<any>('/api/admin/walk-class/import/commit', fd)
    ElMessage.success(`已导入 ${d.insertedWalks} 个走班 / ${d.insertedStudents} 名学生（清旧 ${d.removedOldWalks} 个）`)
    importDialog.value = false
    await load()
  } finally {
    committing.value = false
  }
}

async function load() {
  walks.value = await api<any[]>('/api/admin/walk-class/list')
}

onMounted(async () => {
  await load()
  grades.value = await api<{ id: number; name: string }[]>('/api/meta/grades')
  subjects.value = await api<{ id: number; name: string }[]>('/api/meta/subjects')
})
</script>
