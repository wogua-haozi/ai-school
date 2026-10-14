<template>
  <div>
    <div class="toolbar">
      <el-select v-model="roleFilter" placeholder="角色" clearable style="width: 150px" @change="loadUsers">
        <el-option label="管理员" value="ADMIN" />
        <el-option label="领导" value="LEADER" />
        <el-option label="班主任" value="HEAD_TEACHER" />
        <el-option label="任课教师" value="TEACHER" />
        <el-option label="级长" value="GRADE_LEADER" />
        <el-option label="学成中心主任" value="DIRECTOR" />
        <el-option label="书记（请假终审）" value="SECRETARY" />
        <el-option label="生活老师" value="DORM" />
        <el-option label="招采" value="PROCUREMENT" />
        <el-option label="门卫" value="GUARD" />
      </el-select>
      <el-input v-model="keyword" placeholder="账号/姓名搜索" style="width: 180px" clearable @change="loadUsers" />
      <el-button type="primary" @click="openCreate">新建账号</el-button>
      <el-button @click="openImport">批量导入</el-button>
      <el-button @click="openTeachImport">导入任课</el-button>
    </div>

    <!-- 批量操作（批15：批量操作全面覆盖；删除逐个走护栏，失败名单回显） -->
    <div v-if="selected.length" class="batch-bar">
      已选 {{ selected.length }} 项：
      <el-button size="small" @click="batchStatus(1)">批量启用</el-button>
      <el-button size="small" @click="batchStatus(0)">批量停用</el-button>
      <el-button size="small" @click="batchReset">重置为初始密码</el-button>
      <el-button size="small" type="danger" @click="batchRemove">批量删除</el-button>
    </div>

    <el-table :data="users" size="small" @selection-change="(rows: any[]) => (selected = rows)">
      <el-table-column type="selection" width="42" />
      <el-table-column prop="username" label="登录名" width="120" />
      <el-table-column prop="realName" label="姓名" width="110" />
      <el-table-column label="角色" width="150">
        <template #default="{ row }">
          {{ roleName(row.role) }}
          <el-tag v-if="row.role === 'LEADER' && row.stageScope" type="success" size="small" style="margin-left: 4px">{{ row.stageScope === 'PRIMARY' ? '小学部' : '初中部' }}</el-tag>
          <el-tag v-if="row.pendingUpgradeTo" type="warning" size="small">升{{ roleName(row.pendingUpgradeTo) }}审批中</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="管理权限" width="92">
        <template #default="{ row }">
          <el-tooltip v-if="row.role === 'LEADER'" content="开启后该领导可使用管理端全部功能（网页端操作）" placement="top">
            <el-switch v-model="row.adminAccess" size="small" @change="(v: any) => togglePerm(row, v, 'ADMIN_ACCESS')" />
          </el-tooltip>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <!-- 批49：全校成绩查看（SCORE_ALL 权限点）——教学处主任/副主任等按人授予，只读 -->
      <el-table-column label="全校成绩" width="86">
        <template #default="{ row }">
          <el-tooltip v-if="canScoreAll(row)" content="开启后该账号可查看全校成绩汇总（只读，录入权限不受影响）" placement="top">
            <el-switch v-model="row.scoreAll" size="small" @change="(v: any) => togglePerm(row, v, 'SCORE_ALL')" />
          </el-tooltip>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="employeeNo" label="工号" width="100">
        <template #default="{ row }">{{ row.employeeNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="subjectName" label="任教学科" width="100">
        <template #default="{ row }">{{ row.subjectName ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="title" label="职称" width="100">
        <template #default="{ row }">{{ row.title ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机" width="130" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.pendingCreate" type="warning" size="small">待审批</el-tag>
          <el-tag v-else :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="320">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link @click="openProfile(row)">档案</el-button>
          <el-button link @click="resetPwd(row)">重置密码</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="removeUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 教师档案查看（只读；本人可在 App「我的-教师档案」维护） -->
    <el-dialog v-model="profileDlg" :title="`${viewing?.realName ?? ''} 的教师档案`" width="440px">
      <div v-if="viewing" class="pf">
        <img v-if="pfPhoto" class="pf-photo" :src="pfPhoto" alt="教师照片">
        <div v-else class="pf-photo pf-none">{{ viewing.realName?.charAt(0) }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="工号">{{ viewing.employeeNo ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="性别">{{ viewing.gender ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="任教学科">{{ viewing.subjectName ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="职称">{{ viewing.title ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="职务">{{ viewing.duty ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="教龄">{{ viewing.teachingYears != null ? viewing.teachingYears + ' 年' : '—' }}</el-descriptions-item>
          <el-descriptions-item label="入职年月">{{ viewing.hireDate ? viewing.hireDate.slice(0, 7) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="简介">{{ viewing.intro ?? '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>

    <h4>任课关系</h4>
    <div class="toolbar">
      <el-select v-model="teach.teacherId" filterable placeholder="教师" style="width: 150px">
        <el-option v-for="u in users" :key="u.id" :label="u.realName" :value="u.id" />
      </el-select>
      <el-select v-model="teach.classId" placeholder="班级" style="width: 150px">
        <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="teach.subjectId" placeholder="学科" style="width: 150px">
        <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-button type="primary" :disabled="!teach.teacherId || !teach.classId || !teach.subjectId" @click="addTeach">添加任课</el-button>
    </div>
    <el-table :data="teaches" size="small">
      <el-table-column prop="teacherName" label="教师" width="110" />
      <el-table-column prop="className" label="班级" width="130" />
      <el-table-column prop="subjectName" label="学科" width="110" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" @click="removeTeach(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="importDialog" title="批量导入教师（Excel）" width="560px">
      <div style="margin-bottom: 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6">
        先下载模板，从第 2 行开始填写。账号/姓名/角色必填；班主任可填「班主任所带班级」（该班已有班主任的行会跳过）。
        任教学科须与系统一致（当前有：{{ subjects.map((s) => s.name).join('、') }}）。逐行校验：合法行入库，问题行列出原因。
      </div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap">
        <el-button size="small" @click="downloadTemplate">下载模板</el-button>
        <input type="file" accept=".xlsx"
          @change="(e: Event) => (importFile = (e.target as HTMLInputElement).files?.[0] ?? null)" />
      </div>
      <el-alert v-if="importResult" :type="importResult.failed ? 'warning' : 'success'" :closable="false">
        成功导入 {{ importResult.inserted }} 人<template v-if="importResult.failed">，失败 {{ importResult.failed }} 行：
          <div v-for="e in importResult.errors" :key="e.row" style="font-size: 12px">
            第 {{ e.row }} 行：{{ e.reason }}
          </div>
        </template>
        <div style="font-size: 12px; margin-top: 4px">
          初始密码统一为 <b>{{ importResult.initialPassword }}</b>，请通知教师登录后在「我的-修改密码」自行更换。
          <template v-if="importResult.pendingApprovals">
            其中管理员/领导账号共 {{ importResult.pendingApprovals }} 个待审批，通过后方可登录。
          </template>
        </div>
      </el-alert>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="teachImportDialog" title="导入任课关系（Excel）" width="560px">
      <div style="margin-bottom: 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6">
        先下载模板，从第 2 行开始填写。教师列填登录账号或姓名（重名须改填账号）；班级名称与学科名须和管理端完全一致。
        逐行校验：合法行入库，已存在的任课关系自动跳过，问题行列出原因。
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 12px; font-size: 13px">
        <el-switch v-model="teachReplace" />
        <span>覆盖模式（换课表用）：清空现有全部任课关系后，按本表全量导入；任一行有误则整体不导入</span>
      </div>
      <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 12px">
        <el-button size="small" @click="downloadTeachTemplate">下载模板</el-button>
        <input type="file" accept=".xlsx"
          @change="(e: Event) => (teachImportFile = (e.target as HTMLInputElement).files?.[0] ?? null)" />
      </div>
      <el-alert v-if="teachImportResult" :type="teachImportResult.failed ? 'warning' : 'success'" :closable="false">
        <template v-if="teachImportResult.mode === 'replace'">
          已清空旧任课 {{ teachImportResult.removedOld }} 条，导入 {{ teachImportResult.inserted }} 条<template v-if="teachImportResult.skipped">，表内重复跳过 {{ teachImportResult.skipped }} 条</template>
        </template>
        <template v-else>
          新增 {{ teachImportResult.inserted }} 条<template v-if="teachImportResult.skipped">，已存在跳过 {{ teachImportResult.skipped }} 条</template><template v-if="teachImportResult.failed">，失败 {{ teachImportResult.failed }} 行：
            <div v-for="e in teachImportResult.errors" :key="e.row" style="font-size: 12px">
              第 {{ e.row }} 行：{{ e.reason }}
            </div>
          </template>
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="teachImportDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!teachImportFile" :loading="teachImporting" @click="doTeachImport">开始导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialog" :title="editing ? '编辑账号' : '新建账号'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="登录名">
          <el-input v-model="form.username" />
          <div v-if="editing" class="hint">修改即换绑登录名（临时号转正等）；重置为统一初始密码后请通知本人</div>
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码">
          <el-input v-model="form.password" show-password />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="领导" value="LEADER" />
            <el-option label="班主任" value="HEAD_TEACHER" />
            <el-option label="任课教师" value="TEACHER" />
            <el-option label="级长（需在组织架构绑年级）" value="GRADE_LEADER" />
            <el-option label="学成中心主任" value="DIRECTOR" />
            <el-option label="书记（请假终审，批39）" value="SECRETARY" />
            <el-option label="生活老师" value="DORM" />
            <el-option label="招采（物资核销/库存）" value="PROCUREMENT" />
            <el-option label="门卫（仅请假核验）" value="GUARD" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.role === 'LEADER'" label="分管学段">
          <el-select v-model="form.stageScope" clearable placeholder="全部学段" style="width: 100%">
            <el-option label="小学部" value="PRIMARY" />
            <el-option label="初中部" value="JUNIOR" />
          </el-select>
          <div class="hint">留空=全部学段；设置后该领导的成绩查询/汇总/导出仅限该学段（批39③）</div>
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="form.phone" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiForm, fetchBlob } from '../../api/http'
import { saveFile } from '../../api/nativeShare'

const route = useRoute()
const users = ref<any[]>([])
const classes = ref<{ id: number; name: string }[]>([])
const subjects = ref<{ id: number; name: string }[]>([])
const teaches = ref<any[]>([])
const roleFilter = ref('')
const keyword = ref((route.query.kw as string) || '')
const dialog = ref(false)
const editing = ref<any>(null)
const form = ref<any>({})
const teach = ref<{ teacherId?: number; classId?: number; subjectId?: number }>({})

function roleName(r: string) {
  return {
    ADMIN: '管理员', LEADER: '领导', HEAD_TEACHER: '班主任', TEACHER: '任课教师',
    GRADE_LEADER: '级长', DIRECTOR: '学成中心主任', SECRETARY: '书记', DORM: '生活老师', PROCUREMENT: '招采', GUARD: '门卫',
  }[r] ?? r
}

async function loadUsers() {
  // 全校教职工 500+：size=100 且无翻页会静默截断，id 靠后的生活老师/门卫整段看不到（后端钳值上限 1000）
  const qs = new URLSearchParams({ page: '1', size: '1000' })
  if (roleFilter.value) qs.set('role', roleFilter.value)
  if (keyword.value.trim()) qs.set('keyword', keyword.value.trim())
  // 批49：adminAccess/scoreAll 权限点标志由 user/list 批量下发（免逐行 N+1）
  const d = await api<{ records: any[] }>(`/api/admin/user/list?${qs}`)
  users.value = d.records
  await loadTeaches()
  // 教师档案列（工号/学科/职称）：按 userId 合并进用户表
  try {
    const pfs = await api<any[]>('/api/profile/admin/list')
    const byUser = new Map(pfs.map((p) => [p.userId, p]))
    users.value = users.value.map((u) => ({ ...u, ...(byUser.get(u.id) ?? {}) }))
  } catch { /* 档案接口失败仅少几列 */ }
}

/** 批49 权限点开关列是否显示：SCORE_ALL 授予教学角色（管理员/书记/级长天生有成绩权不占开关） */
function canScoreAll(row: any) {
  return ['TEACHER', 'HEAD_TEACHER', 'LEADER', 'DIRECTOR', 'GRADE_LEADER'].includes(row.role)
}

/** 权限点授予/收回（批1 ADMIN_ACCESS 领导管理员级；批49 SCORE_ALL 全校成绩只读） */
async function togglePerm(row: any, granted: boolean, code: string) {
  const label = code === 'SCORE_ALL' ? '全校成绩查看' : '管理员级'
  try {
    await api(`/api/admin/user/${row.id}/perm`, { method: 'PUT', json: { granted, code } })
    ElMessage.success(granted ? `已授予${label}权限` : `已收回${label}权限`)
  } catch (e) {
    if (code === 'SCORE_ALL') row.scoreAll = !granted // 失败回滚开关状态
    else row.adminAccess = !granted
    throw e
  }
}

const profileDlg = ref(false)
const viewing = ref<any>(null)
const pfPhoto = ref('')
async function openProfile(row: any) {
  viewing.value = row
  pfPhoto.value = ''
  profileDlg.value = true
  if (row.photoUrl) {
    try {
      const blob = await fetchBlob(row.photoUrl)
      pfPhoto.value = URL.createObjectURL(blob)
    } catch { /* 照片加载失败显示首字 */ }
  }
}

async function loadTeaches() {
  teaches.value = await api<any[]>(`/api/admin/teach/list${teach.value.teacherId ? '?teacherId=' + teach.value.teacherId : ''}`)
}

function openCreate() {
  editing.value = null
  form.value = { role: 'TEACHER', stageScope: '' }
  dialog.value = true
}

const importDialog = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const importResult = ref<any>(null)

function openImport() {
  importFile.value = null
  importResult.value = null
  importDialog.value = true
}

async function downloadTemplate() {
  const blob = await fetchBlob('/api/admin/teacher/import-template')
  await saveFile(blob, '教师导入模板.xlsx')
}

async function doImport() {
  if (!importFile.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    importResult.value = await apiForm<any>('/api/admin/teacher/import', fd)
    await loadUsers()
  } finally {
    importing.value = false
  }
}

const teachImportDialog = ref(false)
const teachImportFile = ref<File | null>(null)
const teachImporting = ref(false)
const teachImportResult = ref<any>(null)
const teachReplace = ref(false)

function openTeachImport() {
  teachImportFile.value = null
  teachImportResult.value = null
  teachImportDialog.value = true
}

async function downloadTeachTemplate() {
  const blob = await fetchBlob('/api/admin/teach/import-template')
  await saveFile(blob, '任课导入模板.xlsx')
}

async function doTeachImport() {
  if (!teachImportFile.value) return
  teachImporting.value = true
  try {
    const fd = new FormData()
    fd.append('file', teachImportFile.value)
    fd.append('mode', teachReplace.value ? 'replace' : 'append')
    teachImportResult.value = await apiForm<any>('/api/admin/teach/import', fd)
    await loadTeaches()
  } finally {
    teachImporting.value = false
  }
}

function openEdit(row: any) {
  editing.value = row
  form.value = { username: row.username, realName: row.realName, role: row.role, phone: row.phone,
    stageScope: row.stageScope ?? '' }
  dialog.value = true
}

async function saveUser() {
  if (editing.value) {
    const d = await api<{ pendingApproval?: boolean }>(`/api/admin/user/${editing.value.id}`, { method: 'PUT', json: form.value })
    // 升入管理员/领导走双人审批（批2-5）：角色暂不变，另一名管理员/领导通过后生效
    ElMessage.success(d?.pendingApproval ? '已提交，待另一名管理员/领导审批后生效' : '已保存')
  } else {
    const d = await api<{ pendingApproval?: boolean }>('/api/admin/user', { method: 'POST', json: form.value })
    ElMessage.success(d?.pendingApproval ? '账号已创建，待另一名管理员/领导审批后启用' : '已保存')
  }
  dialog.value = false
  await loadUsers()
}

async function resetPwd(row: any) {
  const { value } = await ElMessageBox.prompt(`为 ${row.realName} 设置新密码`, '重置密码', { inputValue: 'aischool123' })
  await api(`/api/admin/user/${row.id}/password`, { method: 'PUT', json: { password: value } })
  ElMessage.success('密码已重置')
}

async function toggleStatus(row: any) {
  await api(`/api/admin/user/${row.id}/status`, { method: 'PUT', json: { status: row.status === 1 ? 0 : 1 } })
  ElMessage.success(row.status === 1 ? '已停用' : '已启用')
  await loadUsers()
}

async function removeUser(row: any) {
  await ElMessageBox.confirm(`删除账号 ${row.realName}？`, '确认')
  await api(`/api/admin/user/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await loadUsers()
}

// ── 批量操作（批15） ──
const selected = ref<any[]>([])

async function batchStatus(status: number) {
  await ElMessageBox.confirm(`${status === 1 ? '启用' : '停用'}选中的 ${selected.value.length} 个账号？`, '确认')
  await api('/api/admin/user/batch/status', {
    method: 'PUT', json: { ids: selected.value.map((r) => r.id), status },
  })
  ElMessage.success('已批量操作')
  await loadUsers()
}

async function batchReset() {
  await ElMessageBox.confirm(
    `将选中的 ${selected.value.length} 个账号重置为初始密码 Shishi@2026？重置后首登强制改密。`, '确认')
  const d = await api<{ initialPassword: string }>('/api/admin/user/batch/reset-password', {
    method: 'PUT', json: { ids: selected.value.map((r) => r.id) },
  })
  ElMessage.success(`已重置，初始密码 ${d.initialPassword}`)
}

async function batchRemove() {
  await ElMessageBox.confirm(
    `删除选中的 ${selected.value.length} 个账号？有任课/班主任关系的会跳过并在结果中说明。`, '确认')
  const d = await api<{ deleted: number; failed: { name: string; reason: string }[] }>('/api/admin/user/batch', {
    method: 'DELETE', json: { ids: selected.value.map((r) => r.id) },
  })
  const skipped = d.failed?.map((f) => `${f.name || f.id}：${f.reason}`).join('；')
  if (skipped) {
    ElMessageBox.alert(`成功删除 ${d.deleted} 个；跳过 ${d.failed.length} 个 —— ${skipped}`, '部分成功')
  } else {
    ElMessage.success(`已删除 ${d.deleted} 个账号`)
  }
  await loadUsers()
}

async function addTeach() {
  await api('/api/admin/teach', { method: 'POST', json: teach.value })
  ElMessage.success('任课关系已添加')
  teach.value = {}
  await loadTeaches()
}

async function removeTeach(row: any) {
  await api(`/api/admin/teach/${row.id}`, { method: 'DELETE' })
  ElMessage.success('已删除')
  await loadTeaches()
}

onMounted(async () => {
  await loadUsers()
  classes.value = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
  subjects.value = await api<{ id: number; name: string }[]>('/api/meta/subjects')
})
</script>

<style scoped>
/* 操作列 5 个按钮收一行：缩小按钮间距防换行 */
.el-table :deep(.el-button + .el-button) { margin-left: 8px; }
.batch-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
  font-size: 13px; color: var(--el-text-color-regular); }
.hint { font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.5; }
.pf { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.pf-photo { width: 84px; height: 84px; border-radius: 50%; object-fit: cover; }
.pf-none { display: flex; align-items: center; justify-content: center;
  background: #f0f2f8; color: #909399; font-size: 30px; font-weight: 600; }
.pf :deep(.el-descriptions) { width: 100%; }
</style>
