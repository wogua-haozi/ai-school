import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    realName: localStorage.getItem('realName') || '',
    role: localStorage.getItem('role') || '',
    mustChangePwd: localStorage.getItem('mustChangePwd') === '1',
    // 批49：权限点随登录下发（SCORE_ALL 全校成绩查看等；ADMIN 天生全权不落表）
    perms: JSON.parse(localStorage.getItem('perms') || '[]') as string[],
  }),
  getters: {
    /** 批49 成绩可见门（与后端 requireScoreAccess 同口径）：管理员/书记/校级领导/级长，或被授全校成绩查看权 */
    scoreAccess(): boolean {
      return ['ADMIN', 'SECRETARY', 'LEADER', 'GRADE_LEADER'].includes(this.role)
        || this.perms.includes('SCORE_ALL')
    },
    /** 批49 成绩可传可改门（与后端 requireScoreUpload 同口径）：管理员/书记/级长 */
    scoreUpload(): boolean {
      return ['ADMIN', 'SECRETARY', 'GRADE_LEADER'].includes(this.role)
    },
  },
  actions: {
    set(token: string, realName: string, role: string, mustChangePwd = false, perms: string[] = []) {
      this.token = token
      this.realName = realName
      this.role = role
      this.mustChangePwd = mustChangePwd
      this.perms = perms
      localStorage.setItem('token', token)
      localStorage.setItem('realName', realName)
      localStorage.setItem('role', role)
      localStorage.setItem('perms', JSON.stringify(perms))
      if (mustChangePwd) localStorage.setItem('mustChangePwd', '1')
      else localStorage.removeItem('mustChangePwd')
    },
    /** 换发 token（改密成功）；clearPwd=true 时一并清「待改密」态 */
    refreshToken(token: string, clearPwd = false) {
      this.token = token
      localStorage.setItem('token', token)
      if (clearPwd) {
        this.mustChangePwd = false
        localStorage.removeItem('mustChangePwd')
      }
    },
    logout() {
      this.token = ''
      this.realName = ''
      this.role = ''
      this.mustChangePwd = false
      this.perms = []
      localStorage.removeItem('token')
      localStorage.removeItem('realName')
      localStorage.removeItem('role')
      localStorage.removeItem('mustChangePwd')
      localStorage.removeItem('perms')
    },
  },
})
