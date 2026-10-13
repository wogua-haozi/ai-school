<template>
  <div class="app-page detail">
    <!-- 顶栏：返回 + 渐变头区（学生信息） -->
    <div class="app-hero hero">
      <button class="back" type="button" aria-label="返回" @click="$router.back()">
        <van-icon name="arrow-left" />
      </button>
      <img class="hero-photo" :src="asset.url('campus-bg', '/campus-bg.jpg')" alt="石实实验学校">
      <div class="stu-head">
        <span class="ava" :style="{ background: avaColor(stu.name) }">{{ stu.name?.charAt(0) }}</span>
        <div class="stu-brief">
          <h1>{{ stu.name }}</h1>
          <p>{{ className }}<template v-if="genderLabel"> · {{ genderLabel }}</template><template v-if="stu.studentNo"> · 学号 {{ stu.studentNo }}</template></p>
        </div>
      </div>
    </div>

    <!-- 功能入口：跳到各功能页（除活动外全部带该生预选，打开即是 TA 的数据） -->
    <div class="app-card overlap tl tex-12 grid">
      <button v-for="g in entries" :key="g.name" class="g-item" type="button" @click="go(g)">
        <span class="g-icon" :style="{ background: g.bg }"><van-icon :name="g.icon" /></span>
        <span>{{ g.name }}</span>
      </button>
    </div>

    <!-- TA的闪光时刻：微光照片墙（有微光才显示） -->
    <div v-if="moments.length" class="app-card tl gold tex-13 moments">
      <div class="app-sec" style="margin: 0 0 10px">TA的闪光时刻<span class="mo-cnt">{{ moments.length }}</span></div>
      <div class="mo-grid">
        <div v-for="m in moments" :key="m.id" class="mo-item">
          <template v-if="m.photoUrl">
            <MomentPhoto :url="m.photoUrl" @tap="previewMoments" />
            <span class="mo-tag">{{ m.sceneTag }}</span>
          </template>
          <!-- 加分同步微光（无照片）：文字卡 -->
          <div v-else class="mo-text">
            <span class="mo-sync">加分 +</span>
            <span class="mo-tag sync">{{ m.sceneTag }}</span>
            <p class="mo-note">{{ m.note }}</p>
          </div>
        </div>
      </div>
    </div>
    <PhotoPreview ref="photoPreview" />

    <!-- 基本信息卡（学籍卡风格） -->
    <div class="app-card tl tex-14 info">
      <div class="app-sec" style="margin: 0 0 6px">基本信息<span class="card-tag">学籍卡</span>
        <button v-if="editable" class="edit-btn" type="button" @click="openEdit">编辑资料</button>
      </div>
      <van-cell title="状态" :value="stu.status || '—'" />
      <van-cell title="家长" :value="stu.guardianName || '—'" />
      <van-cell title="联系电话" :value="stu.guardianPhone || '—'" />
      <van-cell title="宿舍" :value="dormText" />
      <div class="barcode" aria-hidden="true"><i v-for="n in 24" :key="n" :style="{ opacity: n % 3 ? .8 : .35 }"></i></div>
    </div>

    <!-- 家长账号卡（批8.5：仅管理员/领导/班主任可见；班主任可重置家长密码） -->
    <div v-if="editable" class="app-card tl tex-15 parents">
      <div class="app-sec" style="margin: 0 0 6px">家长账号<span class="mo-cnt">{{ parents.length }}</span></div>
      <van-empty v-if="!parents.length" description="暂无绑定的家长账号" image="search" />
      <div v-for="p in parents" :key="p.parentId" class="pa">
        <div class="pa-info">
          <b>{{ p.realName || '家长' }}</b>
          <span class="pa-sub">{{ p.relation || '家长' }} · {{ p.account }}<template v-if="p.status !== 1"> · 已停用</template></span>
        </div>
        <van-button size="small" plain round type="primary" @click="resetParent(p)">重置密码</van-button>
      </div>
    </div>

    <!-- 编辑资料（批8.5：班主任/级长/管理员订正基本资料；转班转出走管理端） -->
    <van-dialog v-model:show="editOpen" title="编辑资料" show-cancel-button :before-close="onEditClose">
      <div style="padding-top: 10px">
        <van-field v-model="editForm.name" label="姓名" placeholder="学生姓名" />
        <van-field v-model="editForm.genderStr" label="性别" placeholder="男 / 女 / 留空" />
        <van-field v-model="editForm.studentNo" label="学号" placeholder="7 位：标识+入学年+班号+编号" />
        <van-field v-model="editForm.guardianName" label="家长姓名" placeholder="留空=清除" />
        <van-field v-model="editForm.guardianPhone" label="家长电话" placeholder="留空=清除" />
        <van-field v-model="editForm.dormBuilding" label="宿舍楼" placeholder="留空=清除" />
        <van-field v-model="editForm.dormRoom" label="房号" placeholder="留空=清除" />
        <van-field v-model="editForm.dormBed" label="床位" placeholder="留空=清除" />
      </div>
    </van-dialog>

    <!-- 重置家长密码结果（口令一次性展示，转告家长） -->
    <van-dialog v-model:show="resetOpen" title="已重置家长密码" :show-confirm-button="false">
      <div class="reset-tip">
        <p>请将以下初始密码转告家长，首次登录会要求修改：</p>
        <p class="code">{{ resetCode }}</p>
      </div>
    </van-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showFailToast, showSuccessToast, showConfirmDialog } from 'vant'
import { api } from '../api/http'
import MomentPhoto from '../components/MomentPhoto.vue'
import PhotoPreview from '../components/PhotoPreview.vue'
import { useAssetStore } from '../stores/asset'
import { useAuthStore } from '../stores/auth'

const asset = useAssetStore()
const route = useRoute()
const router = useRouter()

interface Stu { name?: string; gender?: string; studentNo?: string; classId?: number; status?: string; guardianName?: string; guardianPhone?: string; dormBuilding?: string; dormRoom?: string; dormBed?: string; editable?: boolean }
const stu = ref<Stu>({})
/* 宿舍三列拼装：全空=未安排住宿（走读/漏录），部分缺格不再漏出 null 字样 */
const dormText = computed(() => {
  const b = stu.value.dormBuilding, r = stu.value.dormRoom, d = stu.value.dormBed
  if (!b && !r && !d) return '未安排住宿'
  return [b, r].filter(Boolean).join(' ') + (d ? ` / ${d}床` : '')
})
const className = ref('')
const termId = ref<number>()
const moments = ref<{ id: number; photoUrl: string | null; sceneTag: string; note?: string; source?: string }[]>([])
const photoPreview = ref<InstanceType<typeof PhotoPreview>>()

/* 批8.5：可编辑（管理员/领导/该班班主任）→ 编辑资料 + 家长账号卡 */
const editable = ref(false)
const parents = ref<{ parentId: number; relation?: string; account?: string; realName?: string; status?: number }[]>([])

const editOpen = ref(false)
const editForm = ref<any>({})
function openEdit() {
  const s = stu.value
  editForm.value = {
    name: s.name || '', studentNo: s.studentNo || '',
    genderStr: s.gender === 'M' ? '男' : s.gender === 'F' ? '女' : '',
    guardianName: s.guardianName || '', guardianPhone: s.guardianPhone || '',
    dormBuilding: s.dormBuilding || '', dormRoom: s.dormRoom || '', dormBed: s.dormBed || '',
  }
  editOpen.value = true
}

async function onEditClose(action: string) {
  if (action !== 'confirm') return true
  const f = editForm.value
  if (!f.name.trim()) { showFailToast('姓名不能为空'); return false }
  if (f.studentNo && !/^[12][0-9]{6}$/.test(f.studentNo)) { showFailToast('学号须为 7 位（标识+入学年+班号+编号）'); return false }
  try {
    await api(`/api/student/${route.params.id}/profile`, {
      method: 'PUT',
      json: {
        name: f.name, studentNo: f.studentNo,
        gender: f.genderStr === '男' ? 'M' : f.genderStr === '女' ? 'F' : '',
        guardianName: f.guardianName, guardianPhone: f.guardianPhone,
        dormBuilding: f.dormBuilding, dormRoom: f.dormRoom, dormBed: f.dormBed,
      },
    })
    showSuccessToast('已保存')
    await loadDetail()
    return true
  } catch (e: any) {
    showFailToast(e?.message || '保存失败')
    return false
  }
}

const resetOpen = ref(false)
const resetCode = ref('')
async function resetParent(p: { parentId: number; realName?: string }) {
  try {
    await showConfirmDialog({ title: '重置家长密码', message: `为 ${p.realName || '该家长'} 重置密码？重置后原密码失效。` })
  } catch { return }
  try {
    const d = await api<{ initialPassword: string }>(`/api/student/${route.params.id}/parent/${p.parentId}/reset-password`, { method: 'POST' })
    resetCode.value = d.initialPassword
    resetOpen.value = true
  } catch (e: any) {
    showFailToast(e?.message || '重置失败')
  }
}

async function loadDetail() {
  const id = Number(route.params.id)
  stu.value = await api<Stu>(`/api/student/${id}`)
  editable.value = !!stu.value.editable
  if (editable.value) {
    api<any>(`/api/student/${id}/parents`).then((d) => (parents.value = d)).catch(() => {})
  }
}

/** 闪光时刻全屏预览：收集整墙已加载照片，可左右滑动，当前张定位 */
function previewMoments(cur: string) {
  const all = [...document.querySelectorAll<HTMLElement>('.mo-item img')]
    .map((i) => i.src).filter(Boolean)
  photoPreview.value?.open(all.length ? all : [cur], Math.max(0, all.indexOf(cur)))
}

/* 入口配色（图4）：每格一色的实心圆角方底 + 白图标；noPre=目标页无按学生看数据的形态；
   批49：成绩格按成绩可见门过滤（教师/班主任零入口） */
const auth = useAuthStore()
const entries = computed(() => [
  ...(auth.scoreAccess ? [{ name: '成绩', icon: 'bar-chart-o', to: '/scores', bg: '#3E7BFA' }] : []),
  { name: '素养评价', icon: 'edit', to: '/evaluate', bg: '#10B981' },
  { name: '活动', icon: 'flag-o', to: '/activity', bg: '#F43F5E', noPre: true },
  { name: '荣誉', icon: 'medal-o', to: '/honor', bg: '#EAB308' },
  { name: '期末评语', icon: 'edit-square', to: '/final-eval', bg: '#F59E0B' },
  { name: '成长银行', icon: 'gold-coin-o', to: '/bank', bg: '#C9A227' },
  { name: '时间轴', icon: 'clock-o', to: '/timeline', bg: '#6366F1' },
])

/** 除「活动」外全部带该生预选（目标页 init 读 query 自动选中班级+学生） */
function go(g: { name: string; icon: string; to: string; bg: string; noPre?: boolean }) {
  const studentId = Number(route.params.id)
  if (!g.noPre && studentId && termId.value) {
    router.push({ path: g.to, query: { studentId: String(studentId), termId: String(termId.value) } })
  } else {
    router.push(g.to)
  }
}

const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
const genderLabel = computed(() =>
  stu.value.gender === 'M' ? '男' : stu.value.gender === 'F' ? '女' : stu.value.gender || '')
function avaColor(name?: string) {
  if (!name) return palette[0]
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

onMounted(async () => {
  const id = Number(route.params.id)
  await loadDetail()
  const classes = await api<{ id: number; name: string }[]>('/api/meta/my-classes')
  className.value = classes.find((c) => c.id === stu.value.classId)?.name ?? ''
  const terms = await api<{ id: number; name: string; isCurrent?: number }[]>('/api/meta/terms')
  termId.value = terms.find((t) => t.isCurrent === 1)?.id ?? terms[0]?.id
  api<{ id: number; photoUrl: string; sceneTag: string }[]>(`/api/moment/student?studentId=${id}`)
    .then((d) => (moments.value = d)).catch(() => {})
})
</script>

<style scoped>
.hero { padding-bottom: 58px; }
.back { display: flex; align-items: center; justify-content: center; width: 34px; height: 34px;
  margin-bottom: 8px; border: none; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
  cursor: pointer; }
.stu-head { display: flex; align-items: center; gap: 14px; }
.ava { display: flex; align-items: center; justify-content: center; width: 58px; height: 58px;
  border-radius: 50%; border: 2px solid rgba(255,255,255,.4); color: #fff; font-size: 22px; font-weight: 700; }
.stu-brief h1 { margin: 0; font-size: 21px; font-weight: 800; }
.stu-brief p { margin: 4px 0 0; font-size: 12px; color: rgba(255,255,255,.72); }

.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px 0; padding: 12px 6px; }
@media (min-width: 600px) { .grid { grid-template-columns: repeat(4, 1fr); } }
.g-item { display: flex; flex-direction: column; align-items: center; gap: 7px;
  padding: 8px 2px; background: none; border: none; color: var(--app-text-1);
  font-size: 12px; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.g-item:active { opacity: .7; }
.g-icon { display: flex; align-items: center; justify-content: center; width: 44px; height: 44px;
  border-radius: 14px; box-shadow: 0 3px 8px rgba(23,43,99,.14); }
.g-icon .van-icon { font-size: 22px; color: #fff; }

.moments { margin-top: 12px; padding: 14px 14px 12px; }
.mo-cnt { margin-left: 6px; padding: 0 8px; border-radius: 999px; background: #FDEEE2;
  color: #EA580C; font-size: 11px; font-weight: 600; }
.mo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.mo-item { position: relative; aspect-ratio: 1/1; border-radius: 10px; overflow: hidden; }
.mo-tag { position: absolute; left: 4px; bottom: 4px; padding: 1px 7px;
  border-radius: 999px; background: rgba(13,22,50,.55); color: #fff;
  font-size: 10px; backdrop-filter: blur(4px); }
/* 加分同步微光（无照片文字卡） */
.mo-text { display: flex; flex-direction: column; justify-content: flex-end; gap: 4px;
  width: 100%; height: 100%; padding: 8px;
  background: linear-gradient(150deg, #1F2A44 0%, #3A4664 100%); }
.mo-sync { align-self: flex-start; padding: 1px 7px; border-radius: 999px;
  background: var(--shine-gold-soft, rgba(201,162,39,.35)); color: var(--shine-gold, #C9A227);
  font-size: 10px; font-weight: 600; }
.mo-tag.sync { position: static; align-self: flex-start; }
.mo-note { margin: 0; font-size: 11px; line-height: 1.5; color: rgba(255,255,255,.92);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }

.info { margin-top: 12px; padding: 14px 4px 12px; }
.info :deep(.van-cell) { font-size: 14px; }
.card-tag { margin-left: 8px; padding: 1px 8px; border: 1px solid #E3DCCB; border-radius: 4px;
  font-size: 10px; font-weight: 500; color: var(--app-text-3); letter-spacing: 2px; }
.edit-btn { margin-left: auto; padding: 3px 12px; border: 1px solid var(--app-card-border);
  border-radius: 999px; background: none; color: var(--app-blue); font-size: 12px; cursor: pointer; }
.app-sec { display: flex; align-items: center; }
.parents { margin-top: 12px; padding: 14px 14px 8px; }
.pa { display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 2px; border-bottom: 1px dashed var(--app-card-border); }
.pa:last-child { border-bottom: none; }
.pa-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.pa-info b { font-size: 14px; color: var(--app-text-1); }
.pa-sub { font-size: 12px; color: var(--app-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.reset-tip { padding: 14px 20px 20px; }
.reset-tip p { margin: 0 0 8px; font-size: 13px; line-height: 1.7; color: var(--app-text-2); }
.reset-tip .code { margin: 4px 0 8px; padding: 10px; border-radius: 8px; background: #F6F7FA;
  text-align: center; font-size: 18px; font-weight: 800; letter-spacing: 2px; color: var(--app-blue-deep, #1F2A44); }
.barcode { display: flex; align-items: center; justify-content: center; gap: 3px; height: 26px;
  margin-top: 8px; }
.barcode i { width: 2px; height: 100%; background: #1F2A44; border-radius: 1px; }
.barcode i:nth-child(2n) { width: 1px; }
</style>
