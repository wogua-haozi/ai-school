<template>
  <div class="login-wrap">
    <!-- 虚化校园大图 + 深蓝渐变罩（设计语言：深蓝体系 + 保留校园元素） -->
    <div class="login-bg" aria-hidden="true"></div>
    <motion.div class="login-card"
      :initial="{ opacity: 0, y: 30, scale: 0.96 }" :animate="{ opacity: 1, y: 0, scale: 1 }"
      :transition="{ type: 'spring', stiffness: 240, damping: 22 }">
      <!-- 批49② 正门照专门展示区（卡片顶部横幅；素材位 login-gate 可由校方自助替换） -->
      <img class="login-gate" :src="gateUrl" alt="石实实验学校正门">
      <div class="login-head">
        <img class="login-logo" src="/badge.png" alt="">
        <h1>学生素质成长报告平台</h1>
        <p>石实实验学校 · 学生的每一步成长都值得被记录</p>
        <p class="motto">任重道远，毋忘奋斗</p>
      </div>

      <van-form @submit="doLogin">
        <van-cell-group inset :border="false" class="fields">
          <van-field v-model="form.username" label-width="44px" left-icon="manager-o" placeholder="用户名"
            :rules="[{ required: true, message: '请输入用户名' }]" />
          <van-field v-model="form.password" type="password" label-width="44px" left-icon="lock" placeholder="密码"
            :rules="[{ required: true, message: '请输入密码' }]" />
        </van-cell-group>
        <van-button round block type="primary" native-type="submit" class="login-btn" :loading="loading">
          登录
        </van-button>
      </van-form>

      <button class="forgot" type="button" @click="forgotOpen = true">忘记密码？</button>
      <button class="forgot reg" type="button" @click="$router.push('/p/register')">家长注册（凭邀请码绑定孩子）</button>
      <button class="forgot reg" type="button" @click="$router.push('/t/register')">教师注册（新老师提交审批）</button>

      <!-- 忘记密码引导（批8.5）：无短信通道，线下找对应负责人重置 -->
      <van-dialog v-model:show="forgotOpen" title="忘记密码" :show-confirm-button="false">
        <div class="forgot-tip">
          <p><b>教师 / 管理员</b>：请联系学校管理员，在管理端「教师与任课」中重置密码。</p>
          <p><b>家长</b>：请联系孩子的班主任，班主任可在学生详情页为家长重置密码；也可联系学校管理员。</p>
          <p class="sub">重置后获得统一初始密码，首次登录会要求设置新密码。</p>
        </div>
      </van-dialog>

      <!-- App 直连服务器地址（打包形态必配；浏览器形态留空走同源） -->
      <button v-if="!isNative" class="srv-toggle" type="button" @click="srvOpen = !srvOpen">
        <van-icon name="setting-o" /> 服务器地址{{ srvBase ? '' : '（默认，无需设置）' }}
        <van-icon :name="srvOpen ? 'arrow-up' : 'arrow-down'" />
      </button>
      <div v-if="srvOpen" class="srv-row">
        <van-field v-model="srvInput" placeholder="如 http://192.168.1.10:8080" clearable />
        <van-button size="small" round type="primary" plain @click="saveSrv">保存</van-button>
      </div>
    </motion.div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { motion } from 'motion-v'
import { showSuccessToast, showFailToast } from 'vant'
import { api, apiBase } from '../api/http'
import { isNative } from '../api/nativeShare'
import { useAuthStore } from '../stores/auth'
import { useAssetStore } from '../stores/asset'

const asset = useAssetStore()
const gateUrl = computed(() => asset.url('login-gate', '/login-gate.jpg'))

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()
const forgotOpen = ref(false)

/* 服务器地址：App 安装后首次使用在此配置（默认值来自打包注入 VITE_API_BASE） */
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
  showSuccessToast(v ? '服务器地址已保存' : '已恢复默认地址')
}

async function doLogin() {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    const data = await api<{ token: string; user: { realName: string; role: string; mustChangePassword?: boolean; perms?: string[] } }>('/api/auth/login', {
      method: 'POST',
      // 手机输入法易带首尾空格/全角字符（全角字母数字→半角），静默清掉防"密码对的也 401"
      json: {
        username: form.username.trim().normalize('NFKC'),
        password: form.password.trim(),
      },
    })
    auth.set(data.token, data.user.realName, data.user.role, !!data.user.mustChangePassword, data.user.perms ?? [])
    // 管理员设密/重置/批量导入的账号：首登强制先改密
    router.push(data.user.mustChangePassword ? '/change-password' : '/')
  } catch (e) {
    // 按 HTTP 状态区分失败原因，绝不把"密码错/限流"误报成"连不上服务器"
    // （登录接口的 401 是密码错，与"会话过期"同码不同义——登录页必须自己解释）
    const st = (e as { status?: number }).status
    if (st === 401) showFailToast('用户名或密码错误')
    else if (st === 429) showFailToast('尝试过于频繁，请等 10 分钟再试')
    else showFailToast('连接服务器失败，请检查网络或服务器地址')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  position: relative; height: 100%; height: 100dvh; overflow: hidden;
  display: flex; align-items: center; justify-content: center; padding: 24px;
  background: #101B3D; /* 图片加载前兜底 */
}
.login-bg {
  position: absolute; inset: -14px;
  background:
    radial-gradient(720px 420px at 85% -10%, rgba(91,133,232,.3), transparent 62%),
    radial-gradient(640px 480px at -6% 108%, rgba(201,138,45,.18), transparent 60%),
    linear-gradient(160deg, rgba(13,22,50,.72) 0%, rgba(22,38,90,.52) 45%, rgba(30,58,138,.64) 100%),
    var(--campus-bg, url('/campus-bg.jpg')) center 42%/cover no-repeat;
  filter: blur(2px);
}
.login-card {
  position: relative; width: 100%; max-width: 380px;
  background: rgba(255,255,255,.94); backdrop-filter: blur(14px);
  border: 1px solid rgba(255,255,255,.5); border-radius: 20px;
  box-shadow: 0 16px 48px rgba(9,18,46,.45);
  padding: 0 18px 18px;
}
/* 批49② 正门照横幅：顶到卡片圆角边（负边距抵消卡片水平 padding） */
.login-gate {
  display: block; width: calc(100% + 36px); margin: 0 -18px; height: 138px;
  object-fit: cover; object-position: center 42%;
  border-radius: 19px 19px 0 0;
  border-bottom: 1px solid rgba(255,255,255,.55);
}
.login-head { text-align: center; margin: 12px 0 12px; }
.login-logo { width: 38px; height: auto; margin-top: 2px; }
.login-head h1 {
  margin: 7px 0 3px; font-size: 19px; font-weight: 800; letter-spacing: 2px;
  background: linear-gradient(150deg, #A8232B, #C9A227);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.login-head p { margin: 0; font-size: 11.5px; color: var(--app-text-2); letter-spacing: 1px; }
.login-head .motto { margin-top: 6px; color: var(--app-gold); letter-spacing: 3px; }

.fields { margin: 0 0 14px; border-radius: 14px; overflow: hidden;
  border: 1px solid var(--app-card-border); }
:deep(.fields .van-field) { padding: 12px 14px; font-size: 15px; }
:deep(.fields .van-field .van-icon) { color: var(--app-blue); font-size: 18px; }
.login-btn { height: 44px; font-size: 16px; font-weight: 600;
  background: linear-gradient(150deg, #8C1D23, #A8232B); border: none; }
.login-btn .van-button__text { letter-spacing: 6px; text-indent: 6px; }  /* 字距用 CSS，可访问名保持「登录」 */

.srv-toggle { display: flex; align-items: center; gap: 6px; justify-content: center;
  width: 100%; margin-top: 16px; padding: 6px; background: none; border: none;
  color: var(--app-text-3); font-size: 12px; cursor: pointer; }
.forgot { display: block; margin: 2px auto 0; background: none; border: none;
  color: var(--app-text-3); font-size: 12.5px; cursor: pointer; text-decoration: underline dotted; }
.forgot-tip { padding: 14px 20px 18px; }
.forgot-tip p { margin: 0 0 10px; font-size: 13px; line-height: 1.7; color: var(--app-text-2); }
.forgot-tip b { color: var(--app-text-1); }
.forgot-tip .sub { margin: 0; font-size: 12px; color: var(--app-text-3); }
.srv-row { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
.srv-row .van-field { flex: 1; border: 1px solid var(--app-card-border); border-radius: 10px; }
</style>
