// 由 local/gen_conduct_rules.py 从校方 xlsx 解析生成；修订规范=改生成脚本重跑或直接编辑本文件
// 出处：《中学生日常行为规范》量化考核标准（石实实验学校）——文明班评比口径，12 大项
export interface ConductItem { text: string; delta: string; unit: string }
export interface ConductGroup { label: string; items: ConductItem[] }
export interface ConductSection { full: string; name: string; groups: ConductGroup[] }

export const CONDUCT_BASIS =
  '文明班评比以下面 12 项得分为依据：每班每天基础分 120 分，每项 10 分；1-11 项为扣分，第 12 项为特殊加减分。'
export const CONDUCT_NOTE =
  '本页为校方官方量化考核标准（文明班评比口径），教师录入操行分 / 素养评价时可作打分参照；个人操行分等第（A/B/C/D）另见成长银行。'

export const CONDUCT_SECTIONS: ConductSection[] = [
  { full: '一、仪容仪表（10分）', name: '仪容仪表（10分）', groups: [
    { label: '', items: [
      { text: '未穿校服', delta: '-1', unit: '每人次' },
      { text: '烫发染发或女生长发未扎起来', delta: '-1', unit: '每人次' },
      { text: '佩戴饰品', delta: '-1', unit: '每人次' },
      { text: '鞋子不合格', delta: '-1', unit: '每人次' },
    ] },
  ] },
  { full: '二、宿舍管理（20分）', name: '宿舍管理（20分）', groups: [
    { label: '纪律扣分', items: [
      { text: '晚归、迟就寝', delta: '-1', unit: '每人' },
      { text: '正钟后讲话、打闹、迟东西（包括牛奶、水果）走动喧哗，严重影响他人休息', delta: '-1', unit: '每人' },
      { text: '任何时候，宿舍禁止追逐打闹、大声喧哗，一旦发现', delta: '-1', unit: '每人' },
      { text: '私自串寝，私自调换床位，双方', delta: '-2', unit: '每人' },
      { text: '顶撞宿管，不服从管理，态度恶劣', delta: '-3', unit: '每人' },
      { text: '故意在宿舍内争吵、起哄、制造矛盾', delta: '-3', unit: '每人' },
      { text: '故意在宿舍内造谣传谣，并对他人造成伤害', delta: '-5', unit: '每人' },
      { text: '未经同意。深夜串寝', delta: '-5', unit: '每人' },
      { text: '在宿舍打架、喝酒、抽烟、打牌', delta: '-5', unit: '每人' },
      { text: '在宿舍区叫外卖', delta: '-5', unit: '每人' },
    ] },
    { label: '纪律加分', items: [
      { text: '一周每晚熄灯后全程安静、无人违纪，被评为“纪律模范宿舍”', delta: '+2', unit: '' },
      { text: '全月无纪律扣分，被评为“月优模范宿舍”', delta: '+3', unit: '' },
      { text: '两睡期间，室长多次主动制止违纪，主动向生活老师汇报情况，维护宿舍环境，维护宿舍纪律，但系+1分，当月被评为优秀室长', delta: '+3', unit: '' },
      { text: '一周无违纪行为，被评为“纪律标兵”', delta: '+1', unit: '' },
    ] },
    { label: '内务卫生扣分', items: [
      { text: '室内地面有垃圾污渍，宿舍', delta: '-1', unit: '' },
      { text: '阳台地面垃圾污渍，宿舍', delta: '-1', unit: '' },
      { text: '洗手池污渍，日用品摆放不规范，宿舍', delta: '-1', unit: '' },
      { text: '窗台、床底、墙角积灰、有蜘蛛网，宿舍', delta: '-1', unit: '' },
      { text: '被子未叠、凌乱不规范，宿舍', delta: '-1', unit: '' },
      { text: '床上、柜子堆放杂物，衣物、鞋子摆放不整齐', delta: '-1', unit: '' },
      { text: '垃圾不倒、卫生间脏乱有异味，宿舍', delta: '-1', unit: '' },
    ] },
    { label: '内务卫生加分', items: [
      { text: '一周宿舍整体干净整洁、无垃圾无灰尘，检查满分，被评为“内务模范宿舍”', delta: '+2', unit: '' },
      { text: '一周卫生间、洗漱台干净无异味，宿舍', delta: '+1', unit: '' },
      { text: '一周内个人内务卫生表现优异，被评为“内务模范标兵”', delta: '+1', unit: '' },
    ] },
  ] },
  { full: '三、卫生评价（10分）', name: '卫生评价（10分）', groups: [
    { label: '公区卫生', items: [
      { text: '公区地面不干净', delta: '-0.5', unit: '每项' },
      { text: '栏杆有污迹', delta: '-0.5', unit: '每项' },
      { text: '大清洁不合格', delta: '-1', unit: '每项' },
    ] },
    { label: '课室卫生（10分）', items: [
      { text: '课室内地面不干净', delta: '-0.5', unit: '每项' },
      { text: '课室外走廊地面不干净', delta: '-0.5', unit: '每项' },
      { text: '课室外卫生角杂乱', delta: '-0.5', unit: '每项' },
    ] },
  ] },
  { full: '四、物品摆放（10分）', name: '物品摆放（10分）', groups: [
    { label: '', items: [
      { text: '水杯乱放', delta: '-0.5', unit: '每处' },
      { text: '桌面杂乱', delta: '-0.5', unit: '每处' },
      { text: '图书角凌乱', delta: '-0.5', unit: '每处' },
      { text: '讲台杂乱', delta: '-0.5', unit: '每处' },
      { text: '窗帘没扎好', delta: '-0.5', unit: '每处' },
      { text: '宣传栏粘贴不稳', delta: '-0.5', unit: '每处' },
      { text: '辅导桌违规摆放', delta: '-0.5', unit: '每处' },
      { text: '清洁用具违规摆放', delta: '-0.5', unit: '每项' },
    ] },
  ] },
  { full: '五、功能室卫生（10分）', name: '功能室卫生（10分）', groups: [
    { label: '', items: [
      { text: '功能室设备不洁', delta: '-0.5', unit: '每项' },
      { text: '功能室地面不洁', delta: '-0.5', unit: '每项' },
      { text: '桌椅不整齐', delta: '-0.5', unit: '每处' },
      { text: '物品摆放不整齐', delta: '-0.5', unit: '每项' },
    ] },
  ] },
  { full: '六、水电门窗公物（10分）', name: '水电门窗公物（10分）', groups: [
    { label: '', items: [
      { text: '电器不关（包括希沃、空调、风扇）', delta: '-1.5', unit: '每次' },
      { text: '损坏公物', delta: '-1.5', unit: '每人次' },
    ] },
  ] },
  { full: '七、每日考勤（10分）', name: '每日考勤（10分）', groups: [
    { label: '', items: [
      { text: '上课迟到', delta: '-1', unit: '每人次' },
      { text: '宿舍迟到', delta: '-1', unit: '每人次' },
      { text: '未假早退的', delta: '-1', unit: '每人次' },
      { text: '无故早退的', delta: '-3', unit: '每人次' },
      { text: '未假旷课的', delta: '-1', unit: '每人次' },
      { text: '无故旷课的', delta: '-5', unit: '每人次' },
      { text: '专训队缺席', delta: '-2', unit: '每班次' },
      { text: '专训队迟到', delta: '-1', unit: '每班次' },
      { text: '体艺节训练缺席', delta: '-2', unit: '每班次' },
      { text: '体艺节训练迟到', delta: '-1', unit: '每班次' },
    ] },
  ] },
  { full: '八、学习纪律（10分）', name: '学习纪律（10分）', groups: [
    { label: '课前准备', items: [
      { text: '不充分', delta: '-1', unit: '每人次' },
    ] },
    { label: '早读状态', items: [
      { text: '秩序乱', delta: '-1', unit: '每次' },
      { text: '声音小', delta: '-1', unit: '每次' },
      { text: '做作业', delta: '-1', unit: '每人次' },
      { text: '不站立', delta: '-1', unit: '每人次' },
      { text: '随意离开课室或者下座位', delta: '-1', unit: '每人次' },
    ] },
    { label: '自习课（或晚修）', items: [
      { text: '不安静扣', delta: '-3', unit: '每班次' },
      { text: '收发作业', delta: '-3', unit: '每班次' },
      { text: '交头接耳', delta: '-1', unit: '每人次' },
      { text: '看无关书籍', delta: '-1', unit: '每人次' },
      { text: '随意离开课室或者下座位', delta: '-1', unit: '每人次' },
      { text: '趴台睡觉或者发呆', delta: '-1', unit: '每人次' },
    ] },
    { label: '课堂专注度', items: [
      { text: '小动作', delta: '-0.5', unit: '每人次' },
      { text: '看课外书', delta: '-1', unit: '每人次' },
      { text: '东张西望或讲小话', delta: '-1', unit: '每人次' },
      { text: '吃东西或喝饮料（或牛奶）', delta: '-1', unit: '每人次' },
      { text: '随意离（换）位或出课室', delta: '-1', unit: '每人次' },
      { text: '使用电子产品', delta: '-3', unit: '每人次' },
      { text: '扰乱课堂秩序', delta: '-3', unit: '每人次' },
      { text: '抄袭作业', delta: '-3', unit: '每人次' },
      { text: '考试作弊', delta: '-3', unit: '每人次' },
      { text: '其他学习违纪', delta: '-1', unit: '每人次' },
    ] },
  ] },
  { full: '九、两操评分（10分）', name: '两操评分（10分）', groups: [
    { label: '眼保健操', items: [
      { text: '不认真做眼保健操的', delta: '-0.5', unit: '每人次' },
    ] },
    { label: '课间操', items: [
      { text: '行进中路队整齐的班级', delta: '+1', unit: '' },
      { text: '不整齐的班级', delta: '-1', unit: '' },
      { text: '缺勤', delta: '-1', unit: '每班次' },
    ] },
  ] },
  { full: '十、升旗礼纪律（10分）', name: '升旗礼纪律（10分）', groups: [
    { label: '', items: [
      { text: '升旗礼迟到', delta: '-0.5', unit: '每班次' },
      { text: '升旗礼队形不齐', delta: '-1', unit: '每班次' },
      { text: '升旗礼讲话', delta: '-0.5', unit: '每人次' },
    ] },
  ] },
  { full: '十一、班风校风（10分）', name: '班风校风（10分）', groups: [
    { label: '文明就餐', items: [
      { text: '违规叫外卖', delta: '-5', unit: '每人次' },
      { text: '私带餐盘出饭堂', delta: '-1', unit: '每人次' },
      { text: '一人打多份饭菜', delta: '-1', unit: '每人次' },
      { text: '餐桌有残渣未带走', delta: '-1', unit: '每人次' },
      { text: '饭堂插队', delta: '-1', unit: '每人次' },
      { text: '饭堂乱丢餐具（或摆放不整齐）', delta: '-1', unit: '每人次' },
    ] },
    { label: '安全管理', items: [
      { text: '高空抛物', delta: '-5', unit: '每人次' },
      { text: '追逐打闹', delta: '-1', unit: '每人次' },
      { text: '中午违规运动', delta: '-1', unit: '每人次' },
      { text: '公共场合起哄', delta: '-5', unit: '每人次' },
    ] },
    { label: '文明礼仪', items: [
      { text: '不尊重老师或者值日生', delta: '-3', unit: '每人次' },
      { text: '给同学恶意起绰号', delta: '-3', unit: '每人次' },
      { text: '无故串班或串楼层', delta: '-3', unit: '每人次' },
      { text: '霸站球场', delta: '-3', unit: '每人次' },
      { text: '乱涂乱画', delta: '-3', unit: '每人次' },
    ] },
  ] },
  { full: '十二、特别奖惩', name: '特别奖惩', groups: [
    { label: '加分类', items: [
      { text: '拾金不昧', delta: '+1', unit: '每人次' },
      { text: '助人为乐', delta: '+1', unit: '每人次' },
      { text: '墙报评比A', delta: '+6', unit: '每班次' },
      { text: '墙报评比B', delta: '+4', unit: '每班次' },
      { text: '墙报评比C', delta: '+2', unit: '每班次' },
      { text: '校级大赛特等奖', delta: '+8', unit: '每班次' },
      { text: '校级大赛一等奖', delta: '+6', unit: '每班次' },
      { text: '校级大赛二等奖', delta: '+4', unit: '每班次' },
      { text: '校级大赛三等奖', delta: '+2', unit: '每班次' },
      { text: '体艺节团总第一名', delta: '+10', unit: '每班次' },
      { text: '体艺节团总第二名', delta: '+9', unit: '每班次' },
      { text: '体艺节团总第三名', delta: '+8', unit: '每班次' },
      { text: '体艺节团总第四名', delta: '+6', unit: '每班次' },
      { text: '体艺节团总第五名', delta: '+5', unit: '每班次' },
      { text: '体艺节团总第六名', delta: '+4', unit: '每班次' },
      { text: '体艺节团总第七名', delta: '+3', unit: '每班次' },
      { text: '体艺节团总第八名', delta: '+2', unit: '每班次' },
      { text: '体艺节文明风尚奖', delta: '+6', unit: '每班次' },
      { text: '级组行政巡查表扬', delta: '+（1-10）', unit: '每班次' },
    ] },
    { label: '扣分类', items: [
      { text: '通报批评', delta: '-5', unit: '每班次' },
      { text: '警告处分', delta: '-10', unit: '每人次' },
      { text: '严重警告', delta: '-15', unit: '每人次' },
      { text: '记过处分', delta: '-20', unit: '每人次' },
      { text: '记大过处分', delta: '-25', unit: '每人次' },
      { text: '留校察看', delta: '-30', unit: '每人次' },
      { text: '勒令退学', delta: '-50', unit: '每人次' },
      { text: '上交教务日志延迟', delta: '-0.5', unit: '每次' },
      { text: '缺交教务日志', delta: '-1', unit: '每次' },
      { text: '级组行政巡查发现不良', delta: '-（1-10）', unit: '每次' },
    ] },
  ] },
]
