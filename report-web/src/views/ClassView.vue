<template>
  <div class="app-page classview">
    <van-pull-refresh v-model="refreshing" @refresh="reload" success-text="已刷新">
    <!-- 头区（图4）：标题 + 校园全景照片带 + 班级切换 -->
    <div class="app-hero hero">
      <div class="hero-row">
        <div>
          <h1>成长档案</h1>
          <p>记录学生成长的每一时刻</p>
        </div>
        <div class="hero-btns">
          <button v-if="canInvite" class="class-pick invite-pick" type="button" @click="openInvite">
            <van-icon name="envelope" /> 邀请码
          </button>
          <button class="class-pick" type="button" @click="pickOpen = true">
            {{ curClassName }} <van-icon name="arrow-down" />
          </button>
        </div>
      </div>
      <!-- 校园全景照片带（学校元素） -->
      <img class="hero-photo" :src="asset.url('campus-pano', '/campus-pano.jpg')" alt="石实实验学校">
      <!-- 搜索（图4：搜索学生姓名） -->
      <div class="search">
        <van-icon name="search" />
        <input v-model="keyword" placeholder="搜索学生姓名..." @input="debouncedLoad" />
      </div>
    </div>

    <!-- 本周微光：班级最近随手拍（横滑照片带；空态给拍照引导） -->
    <div class="app-card overlap tl gold tex-04 moments">
      <div class="mo-head">
        <div class="app-sec mo-title" style="margin: 0"
          @click="$router.push({ path: '/moment', query: { classId: String(classId ?? '') } })">
          本周微光<span class="mo-cnt">{{ moments.length }}</span><van-icon class="mo-more" name="arrow" />
        </div>
        <button class="mo-cam" type="button"
          @click="$router.push({ path: '/moment/new', query: { classId: String(classId ?? '') } })">
          <van-icon name="photograph" /> 拍照
        </button>
      </div>
      <div v-if="moments.length" class="mo-row">
        <div v-for="m in moments" :key="m.id" class="mo-card">
          <MomentPhoto :url="m.photoUrl" @tap="(src) => openMoment(m, src)" />
          <span v-if="(m.photoUrls?.length ?? 0) > 1" class="mo-n">{{ m.photoUrls?.length }}张</span>
          <span class="mo-tag">{{ m.sceneTag }}</span>
        </div>
      </div>
      <button v-else class="mo-empty" type="button"
        @click="$router.push({ path: '/moment/new', query: { classId: String(classId ?? '') } })">
        <van-icon name="photograph" /> 拍下第一束微光，记录闪光时刻
      </button>
    </div>

    <!-- 学生卡片列表（图书馆长廊底纹；微光卡恒在流内——空态也有引导按钮，列表永不上叠） -->
    <div v-if="students.length" class="app-card tl tex-05 list">
      <div v-for="s in students" :key="s.id" class="stu" @click="$router.push(`/student/${s.id}`)">
        <span class="ava" :style="{ background: avaColor(s.name) }">{{ s.name.charAt(0) }}</span>
        <div class="stu-info">
          <span class="stu-name">{{ s.name }}</span>
          <span class="stu-no">学号 {{ s.studentNo || '—' }}</span>
        </div>
        <div class="stu-tags">
          <span class="app-chip">{{ s.gender || '未填' }}</span>
        </div>
        <van-icon class="stu-arrow" name="arrow" />
      </div>
    </div>
    <div v-else-if="loading" class="app-card list">
      <van-skeleton v-for="i in 6" :key="i" :row="1" title class="stu-skeleton" />
    </div>
    <div v-else class="app-card list">
      <van-empty image-size="88" :description="keyword ? '没有找到该学生' : '本班暂无在读学生'" />
    </div>

    <p class="count">共 {{ total }} 名学生</p>
    <CampusSkyline />
    <p class="app-foot">石实实验学校 · 石实SHINE</p>

    <!-- 班级选择（底部弹层） -->
    <van-popup v-model:show="pickOpen" position="bottom" round>
      <van-picker title="选择班级" :columns="classColumns" @confirm="onPick" @cancel="pickOpen = false" />
    </van-popup>

    <!-- 家长邀请码（批8.6；批57① 改班级码：全班共用一码，家长凭「学号+孩子姓名+班级码」自助注册 -->
    <van-popup v-model:show="inviteOpen" position="bottom" round :style="{ maxHeight: '78%' }" class="invite-pop">
      <div class="iv-head">
        <div>
          <b>家长邀请码</b>
          <small>发一次全班通用；家长在登录页「家长注册」凭学号+孩子姓名绑定</small>
        </div>
        <van-button size="small" round type="primary" :loading="inviteBusy" @click="genClass">生成班级码</van-button>
      </div>
      <div v-if="classCode" class="iv-class" @click="copyText(classCode, '班级邀请码')">
        <div class="iv-stu">
          <small>本班班级码（点击复制）</small>
          <b class="iv-code">{{ classCode }}</b>
        </div>
        <van-tag type="primary" plain>全班共用</van-tag>
      </div>
      <div v-else class="iv-empty-code">尚未生成班级码，点击右上角「生成班级码」</div>
      <div class="iv-list">
        <div v-for="r in inviteRows" :key="r.studentId" class="iv-row" @click="r.code && copyCode(r)">
          <div class="iv-stu">
            <b>{{ r.name }}</b>
            <small>{{ r.studentNo || '无学号' }}<template v-if="r.boundCount"> · 已绑 {{ r.boundCount }} 位家长</template><template v-if="r.registered"> · 已注册</template></small>
          </div>
          <van-tag v-if="r.code" type="primary" plain class="iv-code">{{ r.code }}</van-tag>
        </div>
        <van-empty v-if="!inviteRows.length" image-size="72" description="本班暂无在读学生" />
      </div>
      <p class="iv-tip">班级码全班共用、可重复使用，重新生成后旧码作废。每位学生最多自助注册 2 位家长，更多家长请由管理端绑定。个别需专属码的学生（如转班过渡期）可由管理端「家长邀请码」生成。</p>
    </van-popup>
    </van-pull-refresh>
    <PhotoPreview ref="photoPreview" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showSuccessToast, showToast } from 'vant'
import { api, fetchBlob } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useAssetStore } from '../stores/asset'
import MomentPhoto from '../components/MomentPhoto.vue'
import CampusSkyline from '../components/CampusSkyline.vue'
import PhotoPreview from '../components/PhotoPreview.vue'

interface Cls { id: number; name: string }
interface Stu { id: number; name: string; studentNo?: string; gender?: string }
interface MomentItem { id: number; photoUrl: string; photoUrls?: string[]; sceneTag: string; note: string }

const classes = ref<Cls[]>([])
const classId = ref<number>()
const students = ref<Stu[]>([])
const moments = ref<MomentItem[]>([])
const total = ref(0)
const keyword = ref('')
const loading = ref(true)
const pickOpen = ref(false)

/* 家长邀请码（批8.6）：班主任/级长/管理员可见（后端按班硬校验；批51b 补 GRADE_LEADER 对齐手册口径） */
const auth = useAuthStore()
const asset = useAssetStore()
const canInvite = computed(() => ['HEAD_TEACHER', 'GRADE_LEADER', 'LEADER', 'ADMIN'].includes(auth.role))
interface InviteRow { studentId: number; studentNo: string; name: string; code: string; classCode: string; registered: boolean; boundCount: number }
const inviteOpen = ref(false)
const inviteBusy = ref(false)
const inviteRows = ref<InviteRow[]>([])
/** 班级码全班同值（后端每行携带，取首行） */
const classCode = computed(() => inviteRows.value[0]?.classCode ?? '')

async function openInvite() {
  if (!classId.value) return
  inviteOpen.value = true
  await loadInvite()
}

async function loadInvite() {
  try {
    inviteRows.value = await api<InviteRow[]>(`/api/invite/list?classId=${classId.value}`)
  } catch (e) {
    inviteOpen.value = false
    showToast((e as Error).message || '仅班主任可查看本班邀请码')
  }
}

async function genClass() {
  inviteBusy.value = true
  try {
    await api('/api/invite/generate', { method: 'POST', json: { classId: classId.value } })
    await loadInvite()
  } finally {
    inviteBusy.value = false
  }
}

async function copyText(code: string, label: string) {
  try {
    await navigator.clipboard.writeText(code)
    showSuccessToast(`已复制${label}`)
  } catch {
    showToast(`${label}：${code}`)
  }
}

async function copyCode(r: InviteRow) {
  if (!r.code) return
  try {
    await navigator.clipboard.writeText(r.code)
    showSuccessToast(`已复制 ${r.name} 的专属码`)
  } catch {
    showToast(`专属码：${r.code}`)
  }
}
const refreshing = ref(false)
const photoPreview = ref<InstanceType<typeof PhotoPreview>>()

const curClassName = computed(() => classes.value.find((c) => c.id === classId.value)?.name ?? '选择班级')
const classColumns = computed(() => classes.value.map((c) => ({ text: c.name, value: c.id })))

/** Vant4 confirm 载荷：{selectedValues, selectedOptions, selectedIndexes}，取值走 selectedOptions */
function onPick(ev: { selectedOptions?: { value: number }[] }) {
  classId.value = ev.selectedOptions?.[0]?.value
  pickOpen.value = false
  load()
  loadMoments()
}

async function loadMoments() {
  if (!classId.value) return
  api<MomentItem[]>(`/api/moment/class?classId=${classId.value}&limit=12`)
    .then((d) => (moments.value = d)).catch(() => (moments.value = []))
}

/** 批62 多图：小卡只显首张（角标示张数），点击拉齐该条全部照片全屏轮播（缓存避免重复拉流） */
const multiUrls = new Map<number, string[]>()
async function openMoment(m: MomentItem, first: string) {
  const urls = m.photoUrls?.length ? m.photoUrls : [m.photoUrl]
  if (urls.length <= 1) { photoPreview.value?.open([first]); return }
  let list = multiUrls.get(m.id)
  if (!list) {
    list = []
    for (const u of urls) {
      try { list.push(URL.createObjectURL(await fetchBlob(u))) } catch { /* 缺失跳过 */ }
    }
    multiUrls.set(m.id, list)
  }
  photoPreview.value?.open(list.length ? list : [first], 0)
}

/** 头像底色：按姓名散列到一组柔和深浅蓝/暖色 */
const palette = ['#A8232B', '#7C4DD8', '#0D9467', '#B07A1C', '#D6567A', '#3A7CA5']
function avaColor(name: string) {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997
  return palette[h % palette.length]
}

let debounce = 0
function debouncedLoad() {
  clearTimeout(debounce)
  debounce = window.setTimeout(load, 250)
}

async function load() {
  loading.value = true
  try {
    const d = await api<{ total: number; records: Stu[] }>(
      `/api/student/list?classId=${classId.value ?? ''}&keyword=${encodeURIComponent(keyword.value)}&page=1&size=100`,
    )
    students.value = d.records
    total.value = d.total
  } finally {
    loading.value = false
  }
}

/** 下拉刷新：重拉学生列表 + 本周微光 */
async function reload() {
  try {
    await Promise.allSettled([load(), loadMoments()])
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  classes.value = await api<Cls[]>('/api/meta/my-classes')
  classId.value = classes.value[0]?.id
  await load()
  loadMoments()
})
</script>

<style scoped>
.list { margin-top: 12px; }   /* 与上方微光卡留出间隙（其余页面 .cells 同为 12px） */
.stu-skeleton { padding: 8px 0; }
.stu-skeleton + .stu-skeleton { border-top: 1px solid var(--app-card-border); }
.hero-row { display: flex; align-items: flex-start; justify-content: space-between; }
.hero .hero-photo { height: 72px; }   /* 列表页用矮照片带，给学生列表让空间 */
.hero h1 { margin: 4px 0 2px; font-size: 21px; font-weight: 800; }
.hero p { margin: 0; font-size: 12px; color: rgba(255,255,255,.65); }
.class-pick { display: flex; align-items: center; gap: 4px; padding: 8px 14px;
  border: none; border-radius: 999px; background: rgba(255,255,255,.18); color: #fff;
  font-size: 13px; cursor: pointer; }

.search { display: flex; align-items: center; gap: 8px; margin-top: 14px; padding: 10px 14px;
  border-radius: 12px; background: rgba(255,255,255,.95); color: var(--app-text-3); }
.search .van-icon { font-size: 16px; }
.search input { flex: 1; border: none; outline: none; background: none; font-size: 14px; color: var(--app-text-1); }
.search input::placeholder { color: var(--app-text-3); }

/* 本周微光照片带 */
.moments { padding: 14px 14px 12px; }
.mo-head { display: flex; align-items: center; justify-content: space-between; }
.mo-title { display: flex; align-items: center; cursor: pointer; }
.mo-more { margin-left: 2px; font-size: 13px; color: #C6CDD9; }
.mo-cnt { margin-left: 6px; padding: 0 8px; border-radius: 999px; background: #FDEEE2;
  color: #EA580C; font-size: 11px; font-weight: 600; }
.mo-cam { display: flex; align-items: center; gap: 4px; padding: 6px 14px; border: none;
  border-radius: 999px; background: linear-gradient(150deg, #F97316, #EA580C);
  color: #fff; font-size: 12px; font-weight: 600; cursor: pointer; }
.mo-cam .van-icon { font-size: 14px; }
.mo-row { display: flex; gap: 10px; margin-top: 12px; overflow-x: auto;
  scrollbar-width: none; }
.mo-row::-webkit-scrollbar { display: none; }
.mo-card { position: relative; flex: none; width: 112px; height: 148px;
  border-radius: 12px; overflow: hidden; }
.mo-tag { position: absolute; left: 6px; bottom: 6px; padding: 2px 8px;
  border-radius: 999px; background: rgba(13,22,50,.55); color: #fff;
  font-size: 10px; backdrop-filter: blur(4px); }
.mo-n { position: absolute; right: 6px; bottom: 6px; padding: 2px 8px;
  border-radius: 999px; background: rgba(13,22,50,.55); color: #fff;
  font-size: 10px; backdrop-filter: blur(4px); }
.mo-empty { display: flex; align-items: center; justify-content: center; gap: 6px;
  width: 100%; margin-top: 12px; padding: 20px 0; border: 1.5px dashed #F3C9A8;
  border-radius: 12px; background: #FFF9F3; color: #EA580C; font-size: 13px; cursor: pointer; }
.mo-empty .van-icon { font-size: 16px; }

.list { padding: 10px 14px; }
/* 学生行垫白色玻璃片：与虚化底纹分层，名单清晰不融合 */
.stu { display: flex; align-items: center; gap: 12px; padding: 11px 12px; cursor: pointer;
  background: rgba(255,255,255,.92); border-radius: 12px;
  box-shadow: 0 1px 3px rgba(10,22,60,.06); }
.stu + .stu { margin-top: 12px; }
.stu:active { opacity: .75; }
.ava { display: flex; align-items: center; justify-content: center; width: 42px; height: 42px;
  border-radius: 50%; color: #fff; font-size: 16px; font-weight: 600; flex: none; }
.stu-info { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.stu-name { font-size: 15px; font-weight: 600; color: var(--app-text-1); }
.stu-no { font-size: 11px; color: var(--app-text-3); }
.stu-arrow { color: #C6CDD9; }
.count { margin: 14px 0 0; text-align: center; font-size: 12px; color: var(--app-text-3); }
</style>

/* 家长邀请码入口+弹层（批8.6） */
.hero-btns { display: flex; gap: 8px; flex: none; }
.invite-pick { background: rgba(255,255,255,.92); }
:global(.invite-pop) { padding-bottom: 12px; }
.iv-head { display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 16px 18px 10px; }
.iv-head b { font-size: 16px; }
.iv-head small { display: block; margin-top: 2px; font-size: 11px; color: var(--app-text-3); }
.iv-class { display: flex; align-items: center; justify-content: space-between; gap: 10px;
  margin: 8px 12px 0; padding: 12px 12px; border-radius: 12px;
  background: rgba(27, 46, 107, 0.06); border: 1px dashed rgba(27, 46, 107, 0.35); }
.iv-class .iv-code { font-size: 20px; letter-spacing: 3px; }
.iv-empty-code { margin: 10px 14px 0; padding: 10px 12px; border-radius: 10px; font-size: 12px;
  color: var(--app-text-3); background: rgba(0, 0, 0, 0.03); }
.iv-list { max-height: 46vh; overflow-y: auto; padding: 0 10px; }
.iv-row { display: flex; align-items: center; gap: 10px; padding: 10px 10px;
  border-bottom: 1px solid var(--app-card-border, #eef1f7); }
.iv-row:last-child { border-bottom: none; }
.iv-stu { flex: 1; min-width: 0; }
.iv-stu b { font-size: 14px; }
.iv-stu small { display: block; font-size: 11px; color: var(--app-text-3); }
.iv-code { font-size: 14px; font-weight: 700; letter-spacing: 2px; font-family: monospace; }
.iv-none { font-size: 12px; color: var(--app-text-3); }
.iv-tip { margin: 8px 18px 4px; font-size: 11px; line-height: 1.6; color: var(--app-text-3); }
