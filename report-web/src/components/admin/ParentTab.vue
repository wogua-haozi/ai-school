<template>
  <div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索账号/姓名/手机" clearable style="width: 200px"
        @keyup.enter="load" @clear="load" />
      <el-button type="primary" @click="openCreate">新建家长</el-button>
      <el-button @click="genDialog = true">按班批量生成</el-button>
      <el-button @click="openInvite">邀请码</el-button>
      <el-button @click="doExport">导出</el-button>
    </div>

    <el-alert type="info" :closable="false" class="tip">
      家长用手机号登录同一 App，登录后自动进入家长端。账号名默认=学生档案里的监护人手机号；
      初始密码与教师机制相同（首登强制改密）。
    </el-alert>

    <div v-if="selected.length" class="batch-bar">
      已选 {{ selected.length }} 项：
      <el-button size="small" @click="batchStatus(1)">批量启用</el-button>
      <el-button size="small" @click="batchStatus(0)">批量停用</el-button>
      <el-button size="small" @click="batchReset">重置为初始密码</el-button>
      <el-button size="small" type="danger" @click="batchRemove">批量删除</el-button>
    </div>

    <el-table :data="parents" size="small" @selection-change="(rows: any[]) => (selected = rows)">
      <el-table-column type="selection" width="42" />
      <el-table-column prop="username" label="账号(手机号)" width="130" />
      <el-table-column prop="realName" label="姓名" width="100" />
      <el-table-column prop="phone" label="手机" width="120">
        <template #default="{ row }">{{ row.phone ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="children" label="绑定孩子" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="76">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="250">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openBindings(row)">绑定管理</el-button>
          <el-button link @click="resetPwd(row)">重置密码</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="removeOne(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建家长 -->
    <el-dialog v-model="dialog" title="新建家长账号" width="460px">
      <el-form label-width="90px">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="监护人手机号" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model="form.password" show-password />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="如 张三妈妈" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="form.phone" />
        </el-form-item>
      </el-form>
      <div class="dlg-tip">创建后可在列表「绑定管理」里绑定孩子（一号可绑多名学生）。</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑家长（批69：简单修改自助，改手机号同步登录账号） -->
    <el-dialog v-model="editDialog" title="编辑家长" width="460px">
      <el-form label-width="90px">
        <el-form-item label="姓名">
          <el-input v-model="editForm.realName" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="editForm.phone" maxlength="11" />
        </el-form-item>
      </el-form>
      <div class="dlg-tip">当前登录账号：{{ editForm.username }}。若账号即原手机号，改手机号后账号会同步改为新号码；密码不变。</div>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 按班批量生成 -->
    <el-dialog v-model="genDialog" title="按班批量生成家长账号" width="520px">
      <div class="dlg-tip" style="margin-bottom: 10px">
        读取班内每位学生的「监护人手机号」生成家长账号并绑定。同一手机号多名学生自动合并为一号多孩；
        已有账号的只补绑定；未填手机号的学生会列出。生成后初始密码统一为
        <b>{{ initialPassword }}</b>（首登强制改密）。
      </div>
      <el-select v-model="genClassId" placeholder="选择班级" style="width: 100%">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-alert v-if="genResult" :type="genResult.skipped ? 'warning' : 'success'" :closable="false" style="margin-top: 12px">
        新建 {{ genResult.created }} 个账号，补绑定 {{ genResult.bound }} 条<template v-if="genResult.skipped">，跳过 {{ genResult.skipped }} 名学生：
          <div v-for="e in genResult.errors" :key="e.student" style="font-size: 12px">{{ e.student }}：{{ e.reason }}</div>
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="genDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!genClassId" :loading="generating" @click="doGenerate">开始生成</el-button>
      </template>
    </el-dialog>

    <!-- 家长邀请码（批8.6；批57① 改班级码）：班主任在 App 生成为主路径，管理端兜底同款能力 -->
    <el-dialog v-model="inviteDialog" title="家长邀请码（自助注册）" width="640px">
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px">
        <el-select v-model="inviteClassId" placeholder="选择班级" style="width: 200px" @change="loadInvite">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button type="primary" size="small" :disabled="!inviteClassId" :loading="inviting" @click="genClassCode">生成班级码</el-button>
        <span style="font-size: 12px; color: var(--el-text-color-secondary)">家长凭「学号+孩子姓名+班级码」在登录页自助注册</span>
      </div>
      <div v-if="inviteClassCode" style="display: flex; align-items: center; gap: 10px; padding: 10px 12px; margin-bottom: 10px;
        border: 1px dashed var(--el-color-primary); border-radius: 8px; background: var(--el-color-primary-light-9)">
        <div>
          <div style="font-size: 12px; color: var(--el-text-color-secondary)">本班班级码（全班共用，可重复使用）</div>
          <b style="font-family: monospace; font-size: 18px; letter-spacing: 3px">{{ inviteClassCode }}</b>
        </div>
        <el-button link type="primary" style="margin-left: auto" @click="copyClassCode">复制班级码</el-button>
      </div>
      <el-table v-if="inviteRows.length" :data="inviteRows" size="small" max-height="380">
        <el-table-column prop="studentNo" label="学号" width="110" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column label="专属码" width="140">
          <template #default="{ row }">
            <b v-if="row.code" style="font-family: monospace; letter-spacing: 2px">{{ row.code }}</b>
            <span v-else style="color: #c0c4cc; font-size: 12px">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.registered" size="small" type="success">已注册</el-tag>
            <el-tag v-else-if="row.boundCount" size="small" type="warning">已绑 {{ row.boundCount }} 位</el-tag>
            <span v-else style="font-size: 12px; color: var(--el-text-color-secondary)">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.code" link type="primary" @click="copyInvite(row)">复制</el-button>
            <el-button v-else link type="primary" @click="genStudentCode(row)">生成专属码</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="dlg-tip" style="margin-top: 8px">先选择班级查看码况；重新生成班级码后旧码作废。每位学生自助注册上限 2 位家长，更多家长可在「新建家长/绑定管理」里兜底绑定。转班过渡期等个别学生可对其生成专属码。</div>
    </el-dialog>

    <!-- 绑定管理 -->
    <el-dialog v-model="bindDialog" :title="`绑定管理 - ${viewing?.realName ?? ''}`" width="520px">
      <el-table :data="bindings" size="small">
        <el-table-column prop="studentName" label="学生" width="100" />
        <el-table-column prop="className" label="班级" width="120">
          <template #default="{ row }">{{ row.className ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="relation" label="关系" width="80" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="unbind(row)">解绑</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="bind-add">
        <el-select v-model="addClassId" placeholder="班级" style="width: 130px" @change="loadStudents">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="addStudentId" filterable placeholder="学生" style="width: 150px">
          <el-option v-for="s in students" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-input v-model="addRelation" placeholder="关系(默认家长)" style="width: 120px" />
        <el-button type="primary" :disabled="!addStudentId" @click="bind">绑定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

const parents = ref<any[]>([])
const classes = ref<{ id: number; name: string }[]>([])
const selected = ref<any[]>([])
const keyword = ref('')
const initialPassword = 'Shishi@2026'

const dialog = ref(false)
const form = ref<any>({})

const genDialog = ref(false)

/* 编辑家长（批69）：姓名/手机号简单修改自助 */
const editDialog = ref(false)
const editForm = ref<any>({})

function openEdit(row: any) {
  editForm.value = { id: row.id, username: row.username, realName: row.realName, phone: row.phone ?? '' }
  editDialog.value = true
}

async function saveEdit() {
  const d = await api<{ usernameSynced: boolean }>(`/api/admin/parent/${editForm.value.id}`, {
    method: 'PUT', json: { realName: editForm.value.realName, phone: editForm.value.phone },
  })
  ElMessage.success(d.usernameSynced ? '已保存，登录账号已同步改为新手机号' : '已保存')
  editDialog.value = false
  await load()
}
const genClassId = ref<number>()
const genResult = ref<any>(null)
const generating = ref(false)

/* 家长邀请码（批8.6；批57① 改班级码：全班共用一码；专属码=个别学生） */
const inviteDialog = ref(false)
const inviteClassId = ref<number>()
const inviteRows = ref<any[]>([])
const inviting = ref(false)
const inviteClassCode = computed(() => inviteRows.value[0]?.classCode ?? '')

function openInvite() {
  inviteDialog.value = true
  if (inviteClassId.value) loadInvite()
}

async function loadInvite() {
  if (!inviteClassId.value) return
  inviteRows.value = await api<any[]>(`/api/invite/list?classId=${inviteClassId.value}`)
}

async function genClassCode() {
  inviting.value = true
  try {
    await api('/api/invite/generate', { method: 'POST', json: { classId: inviteClassId.value } })
    await loadInvite()
  } finally {
    inviting.value = false
  }
}

async function genStudentCode(row: any) {
  await api('/api/invite/generate', { method: 'POST', json: { studentId: row.studentId } })
  await loadInvite()
}

async function copyClassCode() {
  try {
    await navigator.clipboard.writeText(inviteClassCode.value)
    ElMessage.success('已复制班级邀请码')
  } catch {
    // 非 HTTPS 环境无剪贴板权限：直接展示码兜底
    ElMessage.info(`班级邀请码：${inviteClassCode.value}`)
  }
}

async function copyInvite(row: any) {
  try {
    await navigator.clipboard.writeText(row.code)
    ElMessage.success(`已复制 ${row.name} 的专属码`)
  } catch {
    // 非 HTTPS 环境无剪贴板权限：直接展示码兜底
    ElMessage.info(`${row.name} 的专属码：${row.code}`)
  }
}

const bindDialog = ref(false)
const viewing = ref<any>(null)
const bindings = ref<any[]>([])
const students = ref<any[]>([])
const addClassId = ref<number>()
const addStudentId = ref<number>()
const addRelation = ref('')

function fmtTime(t?: string) {
  return t ? t.slice(0, 16).replace('T', ' ') : '—'
}

async function load() {
  const qs = new URLSearchParams({ page: '1', size: '100' })
  if (keyword.value) qs.set('keyword', keyword.value)
  const d = await api<{ records: any[] }>(`/api/admin/parent/list?${qs}`)
  parents.value = d.records
}

function openCreate() {
  form.value = { password: initialPassword }
  dialog.value = true
}

async function save() {
  await api('/api/admin/parent/create', { method: 'POST', json: form.value })
  ElMessage.success('已创建')
  dialog.value = false
  await load()
}

async function doGenerate() {
  if (!genClassId.value) return
  generating.value = true
  try {
    genResult.value = await api<any>('/api/admin/parent/generate', {
      method: 'POST', json: { classId: genClassId.value },
    })
    await load()
  } finally {
    generating.value = false
  }
}

async function openBindings(row: any) {
  viewing.value = row
  bindings.value = await api<any[]>(`/api/admin/parent/${row.id}/bindings`)
  addClassId.value = undefined
  addStudentId.value = undefined
  addRelation.value = ''
  bindDialog.value = true
}

async function loadStudents() {
  addStudentId.value = undefined
  if (!addClassId.value) { students.value = []; return }
  const d = await api<{ records: any[] }>(
    `/api/admin/student/list?classId=${addClassId.value}&status=${encodeURIComponent('在读')}&size=100`)
  students.value = d.records
}

async function bind() {
  if (!addStudentId.value) return
  await api(`/api/admin/parent/${viewing.value.id}/binding`, {
    method: 'POST',
    json: { studentId: addStudentId.value, relation: addRelation.value || undefined },
  })
  ElMessage.success('已绑定')
  addStudentId.value = undefined
  bindings.value = await api<any[]>(`/api/admin/parent/${viewing.value.id}/bindings`)
  await load()
}

async function unbind(row: any) {
  await api(`/api/admin/parent/${viewing.value.id}/binding/${row.studentId}`, { method: 'DELETE' })
  ElMessage.success('已解绑')
  bindings.value = await api<any[]>(`/api/admin/parent/${viewing.value.id}/bindings`)
  await load()
}

async function resetPwd(row: any) {
  const { value } = await ElMessageBox.prompt(`为 ${row.realName} 设置新密码`, '重置密码', { inputValue: 'aischool123' })
  await api(`/api/admin/parent/${row.id}/password`, { method: 'PUT', json: { password: value } })
  ElMessage.success('密码已重置')
}

async function toggleStatus(row: any) {
  await api(`/api/admin/parent/${row.id}/status`, { method: 'PUT', json: { status: row.status === 1 ? 0 : 1 } })
  ElMessage.success(row.status === 1 ? '已停用' : '已启用')
  await load()
}

async function removeOne(row: any) {
  await ElMessageBox.confirm(`删除家长账号 ${row.realName}？其绑定关系一并删除。`, '确认')
  await api(`/api/admin/parent/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await load()
}

async function batchStatus(status: number) {
  await ElMessageBox.confirm(`${status === 1 ? '启用' : '停用'}选中的 ${selected.value.length} 个账号？`, '确认')
  await api('/api/admin/parent/batch/status', {
    method: 'PUT', json: { ids: selected.value.map((r) => r.id), status },
  })
  ElMessage.success('已批量操作')
  await load()
}

async function batchReset() {
  await ElMessageBox.confirm(
    `将选中的 ${selected.value.length} 个账号重置为初始密码 ${initialPassword}？`, '确认')
  await api('/api/admin/parent/batch/reset-password', {
    method: 'PUT', json: { ids: selected.value.map((r) => r.id) },
  })
  ElMessage.success(`已重置，初始密码 ${initialPassword}`)
}

async function batchRemove() {
  await ElMessageBox.confirm(`删除选中的 ${selected.value.length} 个账号？绑定关系一并删除。`, '确认')
  await api('/api/admin/parent/batch', {
    method: 'DELETE', json: { ids: selected.value.map((r) => r.id) },
  })
  ElMessage.success('已删除')
  await load()
}

async function doExport() {
  const qs = keyword.value ? `?keyword=${encodeURIComponent(keyword.value)}` : ''
  const blob = await fetchBlob(`/api/admin/parent/export${qs}`)
  await saveFile(blob, '家长账号.xlsx')
}

onMounted(async () => {
  await load()
  classes.value = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
})
</script>

<style scoped>
.tip { margin-bottom: 12px; }
.batch-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
  padding: 8px 12px; background: var(--el-color-primary-light-9); border-radius: 6px;
  font-size: 13px; }
.dlg-tip { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
.bind-add { display: flex; align-items: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
</style>
