<template>
  <div class="app-page p-mine">
    <!-- 头区（第一版结构：头像+姓名+角色 chip；右上校徽水印） -->
    <div class="app-hero hero mark">
      <div class="me">
        <span class="ava">{{ auth.realName?.charAt(0) || '家' }}</span>
        <div>
          <h1>{{ auth.realName }}</h1>
          <p><span class="app-chip role-chip">家长</span></p>
        </div>
      </div>
    </div>

    <div class="app-card overlap tl tex-17 cells">
      <van-cell title="消息通知" icon="volume-o" is-link :value="notifyBadge" @click="$router.push('/p/notify')" />
      <van-cell title="修改手机号" icon="phone-o" is-link @click="phoneOpen = true" />
      <van-cell title="修改密码" icon="lock" is-link @click="pwdOpen = true" />
      <van-cell title="意见反馈" icon="chat-o" is-link @click="$router.push('/p/feedback')" />
      <van-cell title="使用帮助" icon="question-o" is-link @click="$router.push('/p/help')" />
      <van-cell title="账号说明" icon="shield-o" is-link @click="tipOpen = true" />
    </div>

    <div class="app-card tex-18 cells">
      <van-cell title="检查更新" icon="upgrade" is-link :value="appVersion" @click="onCheckUpdate" />
      <van-cell v-if="!isNative" title="服务器地址" icon="desktop-o" is-link :value="srvBase || '默认'" @click="srvOpen = true" />
      <van-cell title="关于" icon="info-o" is-link @click="aboutOpen = true" />
      <van-cell title="退出登录" icon="revoke" is-link class="logout" @click="logoutOpen = true" />
    </div>

    <!-- 修改密码（同教师端机制：改密换发新 token） -->
    <van-dialog v-model:show="pwdOpen" title="修改密码" show-cancel-button :before-close="onPwdClose">
      <div style="padding-top: 10px">
        <van-field v-model="pwd.old" type="password" label="旧密码" placeholder="当前密码" />
        <van-field v-model="pwd.next" type="password" label="新密码" placeholder="至少 8 位" />
        <van-field v-model="pwd.again" type="password" label="确认新密码" placeholder="再输入一遍" />
      </div>
    </van-dialog>

    <!-- 修改手机号（批8.5）：家长账号登录名即手机号，换手机号后用新号登录 -->
    <van-dialog v-model:show="phoneOpen" title="修改手机号" show-cancel-button :before-close="onPhoneClose">
      <div class="srv-tip" style="padding-top: 10px">家长账号的登录名就是手机号，换绑后请用新手机号登录。</div>
      <div style="padding-top: 6px">
        <van-field v-model="phoneNext" type="tel" label="新手机号" placeholder="11 位手机号" maxlength="11" />
        <van-field v-model="phonePwd" type="password" label="当前密码" placeholder="输入密码确认" />
      </div>
    </van-dialog>

    <!-- 账号说明 -->
    <van-dialog v-model:show="tipOpen" title="账号说明" :show-confirm-button="false">
      <p class="tip">家长账号由学校发放，仅可查看绑定孩子的成长动态。换手机号在上方「修改手机号」自助办理（登录名同步更换）；忘记密码请找孩子的班主任重置；变更绑定孩子请联系班主任或学校管理员。</p>
    </van-dialog>

    <!-- 服务器地址编辑 -->
    <van-dialog v-model:show="srvOpen" title="服务器地址" show-cancel-button @confirm="saveSrv">
      <div class="srv-tip">App 直连的学校服务器，如 http://192.168.1.10:8080；留空恢复默认</div>
      <van-field v-model="srvInput" placeholder="http://ip:端口" clearable />
    </van-dialog>

    <!-- 关于（同教师端） -->
    <van-dialog v-model:show="aboutOpen" title="关于" :show-confirm-button="false">
      <div class="about">
        <img :src="asset.url('campus-pano', '/campus-pano.jpg')" alt="石实实验学校" class="about-pano">
        <img src="/badge.png" alt="" class="about-badge">
        <b>佛山市南海区石实实验学校</b>
        <p>石实SHINE · 中学素质报告平台</p>
        <p class="motto">任重道远，毋忘奋斗 · 扬长教育，出彩人生</p>
        <p class="ver">{{ appVersion }}</p>
      </div>
    </van-dialog>

    <!-- 退出确认 -->
    <van-dialog v-model:show="logoutOpen" title="退出登录" message="确定退出当前账号吗？" show-cancel-button @confirm="logout" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showFailToast, showSuccessToast } from 'vant'
import { App as CapApp } from '@capacitor/app'
import { useAuthStore } from '../../stores/auth'
import { useAssetStore } from '../../stores/asset'
import { useNotifyStore } from '../../stores/notify'
import { api, apiBase } from '../../api/http'
import { isNative } from '../../api/nativeShare'
import { checkForUpdate } from '../../utils/appUpdate'

const auth = useAuthStore()
const asset = useAssetStore()
const notifyStore = useNotifyStore()
const router = useRouter()

/** 通知未读角标（批29）：轮询由 App 壳维护 */
const notifyBadge = computed(() => (notifyStore.unread ? `${notifyStore.unread} 条未读` : ''))

const tipOpen = ref(false)
const srvOpen = ref(false)
const srvBase = ref(apiBase())
const srvInput = ref(apiBase())
function saveSrv() {
  const raw = srvInput.value.trim().replace(/\/+$/, '')
  // 无协议头自动补 http://：裸地址会被 fetch 当相对路径，拼坏所有请求
  const v = raw && !/^https?:\/\//.test(raw) ? 'http://' + raw : raw
  if (v) localStorage.setItem('serverBase', v)
  else localStorage.removeItem('serverBase')
  srvBase.value = v
  showSuccessToast(v ? '已保存，重新进入页面生效' : '已恢复默认')
}

const aboutOpen = ref(false)

const pwdOpen = ref(false)
const pwd = ref({ old: '', next: '', again: '' })
async function onPwdClose(action: string) {
  if (action !== 'confirm') return true
  if (!pwd.value.old || !pwd.value.next) { showFailToast('请填写完整'); return false }
  if (pwd.value.next.length < 8) { showFailToast('新密码至少 8 位'); return false }
  if (pwd.value.next !== pwd.value.again) { showFailToast('两次新密码不一致'); return false }
  try {
    const d = await api<{ token: string }>('/api/auth/password', { method: 'PUT', json: { oldPassword: pwd.value.old, newPassword: pwd.value.next } })
    auth.refreshToken(d.token, true) // 换发新 token（顺带清待改密态）
    showSuccessToast('密码已修改')
    pwd.value = { old: '', next: '', again: '' }
    return true
  } catch (e: any) {
    showFailToast(e?.message || '修改失败')
    return false
  }
}

/* 修改手机号（批8.5）：家长登录名=手机号，换绑后 token 立即换发 */
const phoneOpen = ref(false)
const phoneNext = ref('')
const phonePwd = ref('')
async function onPhoneClose(action: string) {
  if (action !== 'confirm') return true
  if (!/^1\d{10}$/.test(phoneNext.value)) { showFailToast('请输入 11 位手机号'); return false }
  if (!phonePwd.value) { showFailToast('请输入当前密码确认'); return false }
  try {
    const d = await api<{ token: string }>('/api/auth/phone', { method: 'PUT', json: { password: phonePwd.value, newPhone: phoneNext.value } })
    auth.refreshToken(d.token)
    showSuccessToast('已换绑，请用新手机号登录')
    phoneNext.value = ''
    phonePwd.value = ''
    return true
  } catch (e: any) {
    showFailToast(e?.message || '修改失败')
    return false
  }
}

const version = __APP_VERSION__
/** App 内显示真实安装包版本（网页版回退 package.json 版本） */
const appVersion = ref('')
onMounted(async () => {
  if (isNative) {
    try { appVersion.value = 'v' + (await CapApp.getInfo()).version } catch { /* 忽略 */ }
  }
  if (!appVersion.value) appVersion.value = 'v' + version
})

function onCheckUpdate() {
  checkForUpdate(true).catch(() => showFailToast('检查更新失败，请稍后重试'))
}

const logoutOpen = ref(false)
function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
/* C 风格页面（方案C 新中式）：结构复用第一版 MineView，仅覆盖头区渐变/点缀色为 C 令牌 */
.hero { padding-bottom: 56px; }
.me { display: flex; align-items: center; gap: 14px; }
.ava { display: flex; align-items: center; justify-content: center; width: 58px; height: 58px;
  border-radius: 50%; background: rgba(255,255,255,.92); color: var(--shine-navy);
  font-size: 22px; font-weight: 700; }
.me h1 { margin: 0 0 6px; font-size: 20px; font-weight: 800; }
.role-chip { background: rgba(255,255,255,.2); color: #fff; }

.cells { margin-top: 12px; padding: 4px 0; }
.cells.overlap { margin-top: -36px; }
.cells :deep(.van-cell) { padding: 13px 16px; font-size: 15px; }
.cells :deep(.van-cell .van-icon:not(.van-cell__right-icon)) { color: var(--shine-navy-soft); font-size: 17px; margin-right: 2px; }
.cells :deep(.logout) { color: #EF4444; }
.cells :deep(.logout .van-cell__title) { color: #EF4444; }

.tip { margin: 0; padding: 16px 20px 20px; font-size: 13px; line-height: 1.8; color: var(--app-text-2); }
.srv-tip { padding: 12px 16px 0; font-size: 12px; color: var(--app-text-3); line-height: 1.5; }
.about { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 0 24px 24px; }
.about-pano { width: 100%; height: 92px; object-fit: cover; border-radius: 12px; margin-bottom: 8px; }
.about-badge { width: 46px; }
.about b { font-size: 15px; color: var(--app-text-1); }
.about p { margin: 0; font-size: 12px; color: var(--app-text-2); }
.about .motto { color: var(--shine-gold); letter-spacing: 1px; }
.about .ver { margin-top: 4px; color: var(--app-text-3); }

/* C 覆盖：头区藏蓝渐变+金线收边（第一版 hero 结构不变，仅换配色令牌） */
.p-mine .app-hero { background: var(--shine-gradient); border-bottom: 2px solid var(--shine-gold); }
</style>
