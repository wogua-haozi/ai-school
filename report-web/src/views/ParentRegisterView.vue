<template>
  <div class="login-wrap">
    <div class="login-bg" aria-hidden="true"></div>
    <motion.div class="login-card reg-card"
      :initial="{ opacity: 0, y: 30, scale: 0.96 }" :animate="{ opacity: 1, y: 0, scale: 1 }"
      :transition="{ type: 'spring', stiffness: 240, damping: 22 }">
      <div class="login-head">
        <img class="login-logo" src="/badge.png" alt="">
        <h1>家长注册</h1>
        <p>凭班主任发放的邀请码绑定孩子</p>
      </div>

      <van-form @submit="doRegister">
        <van-cell-group inset :border="false" class="fields">
          <van-field v-model="form.phone" type="tel" maxlength="11" left-icon="phone-o" placeholder="手机号（即登录账号）"
            :rules="[
              { required: true, message: '请输入手机号' },
              { pattern: /^1\d{10}$/, message: '手机号格式不正确' }]" />
          <van-field v-model="form.password" type="password" left-icon="lock" placeholder="设置密码（至少 8 位）"
            :rules="[
              { required: true, message: '请设置密码' },
              { validator: (v: string) => v.length >= 8, message: '密码至少 8 位' }]" />
          <van-field v-model="form.password2" type="password" left-icon="lock" placeholder="确认密码"
            :rules="[
              { required: true, message: '请再次输入密码' },
              { validator: (v: string) => v === form.password, message: '两次密码不一致' }]" />
          <van-field v-model="form.studentNo" left-icon="user-o" placeholder="孩子学号（如 2773630101）"
            :rules="[{ required: true, message: '请输入孩子学号' }]" />
          <van-field v-model="form.studentName" left-icon="friends-o" placeholder="孩子姓名"
            :rules="[{ required: true, message: '请输入孩子姓名' }]" />
          <van-field v-model="form.inviteCode" left-icon="coupon-o" placeholder="班级邀请码（向班主任获取）"
            :rules="[{ required: true, message: '请输入班级邀请码' }]" />
          <van-field v-model="form.realName" left-icon="contact" placeholder="您的姓名（选填）" />
          <van-field v-model="relationText" is-link readonly left-icon="friends-o" placeholder="与孩子的关系"
            :rules="[{ required: true, message: '请选择与孩子的关系' }]" @click="relOpen = true" />
        </van-cell-group>
        <van-button round block type="primary" native-type="submit" class="login-btn" :loading="loading">
          注册并绑定
        </van-button>
      </van-form>

      <button class="forgot" type="button" @click="$router.push('/login')">已有账号？返回登录</button>
      <p class="tip">班级邀请码由班主任在 App「班级-家长邀请码」生成，全班共用一个码，家长凭「学号+孩子姓名+邀请码」绑定自己孩子；每位学生最多自助注册 2 位家长，更多家长请联系班主任绑定。</p>
    </motion.div>

    <van-popup v-model:show="relOpen" position="bottom" round>
      <van-picker :columns="RELATIONS" @confirm="onRel" @cancel="relOpen = false" />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { motion } from 'motion-v'
import { showSuccessToast } from 'vant'
import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'

// Vant4 Picker columns 须对象数组（{text,value}，同 ClassView 班级选择器）
const RELATIONS = ['父亲', '母亲', '爷爷', '奶奶', '外公', '外婆', '其他家属'].map((t) => ({ text: t, value: t }))

const form = reactive({ phone: '', password: '', password2: '', studentNo: '', studentName: '', inviteCode: '', realName: '' })
const relationText = ref('')
const relation = ref('')
const relOpen = ref(false)
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()

/** Vant4 confirm 载荷：{selectedValues, selectedOptions}，取值走 selectedOptions（同 ClassView） */
function onRel(ev: { selectedOptions?: { text: string }[] }) {
  const v = ev.selectedOptions?.[0]?.text ?? ''
  relation.value = v
  relationText.value = v
  relOpen.value = false
}

async function doRegister() {
  loading.value = true
  try {
    const data = await api<{ token: string; user: { realName: string; role: string } }>('/api/auth/parent/register', {
      method: 'POST',
      json: {
        phone: form.phone, password: form.password,
        studentNo: form.studentNo.trim(), studentName: form.studentName.trim(), inviteCode: form.inviteCode.trim(),
        realName: form.realName.trim(), relation: relation.value,
      },
    })
    auth.set(data.token, data.user.realName, data.user.role, false)
    showSuccessToast('注册成功，已绑定孩子')
    router.push('/p/home')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reg-card { max-height: 86vh; overflow-y: auto; }
.tip { margin: 10px 6px 0; font-size: 11px; line-height: 1.7; color: var(--app-text-3, #9aa3b8); text-align: left; }
/* 与登录页同款视觉（深蓝渐变罩+卡片）；bg/card/head 复用 LoginView 的构建方式 */
.login-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center;
  padding: 18px 14px; position: relative; overflow: hidden; }
.login-bg { position: absolute; inset: -40px; background: linear-gradient(200deg, #16264F 0%, #0E1833 100%); z-index: 0; }
.login-card { position: relative; z-index: 1; width: 100%; max-width: 400px;
  background: var(--app-card, #fff); border-radius: 20px;
  box-shadow: 0 16px 48px rgba(9,18,46,.45); padding: 28px 18px 18px; }
.login-head { text-align: center; margin-bottom: 18px; }
.login-logo { width: 52px; height: auto; }
.login-head h1 { margin: 10px 0 4px; font-size: 24px; font-weight: 800; letter-spacing: 4px;
  background: linear-gradient(150deg, #A8232B, #C9A227);
  -webkit-background-clip: text; background-clip: text; color: transparent; }
.login-head p { margin: 0; font-size: 12px; color: var(--app-text-2, #5b6478); letter-spacing: 1px; }
.fields { margin: 0 0 14px; border-radius: 14px; overflow: hidden; border: 1px solid var(--app-card-border, #e8ecf5); }
:deep(.fields .van-field) { padding: 12px 14px; font-size: 15px; }
.login-btn { height: 44px; font-size: 16px; font-weight: 600;
  background: linear-gradient(150deg, #8C1D23, #A8232B); border: none; }
.forgot { display: block; margin: 14px auto 0; background: none; border: none;
  font-size: 13px; color: var(--app-blue, #1B2E6B); }
</style>
