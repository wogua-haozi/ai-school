<template>
  <div>
    <h4>考试与录入窗口（批49：成绩管理仅管理员/书记/级长可用；关闭录入窗口后级长不可录，管理员/书记不受限）</h4>
    <el-table :data="exams" size="small">
      <el-table-column prop="name" label="考试" min-width="160" />
      <el-table-column prop="termName" label="学期" width="150" />
      <el-table-column label="考试日期" width="110">
        <template #default="{ row }">{{ row.examDate ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="subjectCount" label="科目数" width="80" align="center" />
      <el-table-column label="录入窗口" width="120">
        <template #default="{ row }">
          <el-switch :model-value="row.entryOpen !== false" :loading="row._saving"
            inline-prompt active-text="开" inactive-text="关"
            @change="(v: any) => toggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="满分合计" width="90" align="center">
        <template #default="{ row }">{{ row.gradeMaxTotal ?? '—' }}</template>
      </el-table-column>
    </el-table>
    <p class="hint" style="margin-top: 10px">
      开=教师在录入窗口内可录、且仅可见自己录入的成绩（方案A）；关=教师端该考试成绩全不可见。排名对教师始终不开放。
    </p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api/http'

const exams = ref<any[]>([])

async function load() {
  exams.value = await api<any[]>('/api/score/exam/list')
}

async function toggle(row: any, open: boolean) {
  row._saving = true
  try {
    await api(`/api/score/exam/${row.id}/entry-open`, { method: 'PUT', json: { open } })
    row.entryOpen = open ? 1 : 0
    ElMessage.success(open ? '录入窗口已开启' : '录入窗口已关闭（教师端成绩不可见）')
  } finally {
    row._saving = false
  }
}

onMounted(load)
</script>
