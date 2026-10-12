/**
 * 批52③ 纹理池：37 张学校介绍照片（tex-01~tex-37）全 App 轮换，同页面不重复。
 * 静态位按页错开分配（页基准=文件序×3 mod 37，页内第 i 个静态位=基准+i）；
 * 轮换位 texAt(页基准+静态数+i) 续在静态位之后，保证同页不撞图。
 * key 与 style.css .tex-NN、AssetService CATALOG 素材位一一对应，学校可在素材库逐槽自助替换。
 */
export const TEX_KEYS = Array.from({ length: 37 }, (_, i) => 'tex-' + String(i + 1).padStart(2, '0'))
export const texAt = (i: number) => TEX_KEYS[((i % TEX_KEYS.length) + TEX_KEYS.length) % TEX_KEYS.length]
