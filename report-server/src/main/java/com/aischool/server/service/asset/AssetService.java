package com.aischool.server.service.asset;

import com.aischool.server.common.BizException;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材库（批35 学校自治）：固定 key 目录 + MinIO 读写 + PDF 渲染覆盖目录同步。
 * key 即契约：web 消费端 /api/asset/{key} 远程优先内置兜底；PDF 渲染子进程读覆盖目录优先、
 * classpath 内置兜底。上传即生效——web 立即可见（URL 带 etag 版本参数），
 * PDF 侧覆盖目录同步后下一次渲染即生效，均无需重建容器/发 APK（App 在线升级模式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    /** 素材 key 目录（group: web=App 内素材 / pdf=成长报告图库；accepts: 允许的上传扩展名） */
    public record Spec(String key, String group, String label, String scene, String accepts) {}

    public static final List<Spec> CATALOG = List.of(
            new Spec("campus-bg", "web", "首页主图", "教师/家长/领导首页与登录页背景照片", "jpg"),
            new Spec("campus-pano", "web", "班级页主图", "班级页顶部照片与「关于」页全景图", "jpg"),
            // 批49②：登录卡片顶部正门照专门展示区（替换/换季可自助上传，不进 PDF 渲染覆盖目录）
            new Spec("login-gate", "web", "登录页正门照", "登录卡片顶部学校正门照片横幅", "jpg"),
            new Spec("tex-a", "web", "纹理·课堂", "页面装饰底纹（课堂师生互动）", "jpg"),
            new Spec("tex-b", "web", "纹理·扬长课", "页面装饰底纹（扬长课程课堂）", "jpg"),
            new Spec("tex-c", "web", "纹理·美术室", "页面装饰底纹（美术室）", "jpg"),
            new Spec("tex-d", "web", "纹理·机器人", "页面装饰底纹（VEX 机器人专训室）", "jpg"),
            new Spec("tex-e", "web", "纹理·建筑", "页面装饰底纹（校园建筑）", "jpg"),
            new Spec("tex-f", "web", "纹理·农场", "页面装饰底纹（爱乐农场劳动）", "jpg"),
            new Spec("tex-g", "web", "纹理·诗词", "页面装饰底纹（诗词大会书香）", "jpg"),
            new Spec("img_idea_bg", "pdf", "理念页·校训石", "成长报告「我们的理念」页装裱照片", "jpg"),
            new Spec("img_photo1", "pdf", "报告照片一", "成长报告校园文化页照片位一", "jpg"),
            new Spec("img_photo2", "pdf", "报告照片二", "成长报告校园文化页照片位二", "jpg"),
            new Spec("img_nine_grid", "pdf", "九格示意图", "成长报告评价体系示意九宫格", "png,jpg"),
            new Spec("img_principal", "pdf", "校长照片", "成长报告校长寄语页照片", "png,jpg"),
            // 批40c IP 成长画像垫图底图：PortraitService 直读 MinIO（不进 PDF 渲染覆盖目录）；
            // IP 素材有校方授权边界不入仓库，生产须校方在素材库上传（未上传=报告画像保持虚线占位框）
            new Spec("ip_shi", "ip", "画像底图·小石", "成长画像 AI 垫图底图（男生角色）", "png"),
            new Spec("ip_meng", "ip", "画像底图·小萌", "成长画像 AI 垫图底图（女生角色）", "png"));

    private static final String PREFIX = "asset/";

    private final MinioClient minioClient;

    @Value("${aischool.minio.bucket}")
    private String bucket;

    /** PDF 渲染覆盖目录（server 与渲染子进程同容器同文件系统） */
    @Value("${aischool.asset.override-dir}")
    private String overrideDir;

    /** 容器重建后本地覆盖目录清空，启动时从 MinIO 补一次（PDF 图位自定义不丢） */
    @PostConstruct
    void syncOnStart() {
        try {
            syncRendererOverrides();
        } catch (Exception e) {
            log.warn("素材覆盖目录启动同步失败（不影响服务，下次上传会再同步）: {}", e.getMessage());
        }
    }

    public static Spec specOf(String key) {
        return CATALOG.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    /** 当前 MinIO 里的对象名（asset/{key}.{ext}），无则 null */
    private String objectNameOf(String key) {
        Iterable<Result<Item>> list = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(PREFIX + key + ".").maxKeys(10).build());
        for (Result<Item> r : list) {
            try {
                String name = r.get().objectName();
                // 前缀防误配：tex-a 不匹配 tex-ab（不存在此 key，但防将来扩目录）
                String rest = name.substring(PREFIX.length());
                if (rest.startsWith(key + ".")) {
                    return name;
                }
            } catch (Exception e) {
                log.warn("素材对象列举失败 {}: {}", key, e.getMessage());
            }
        }
        return null;
    }

    /** 管理端列表：目录 + 当前状态（内置默认 / 已自定义 + 更新时间 + 预览版本） */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (Spec spec : CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", spec.key());
            m.put("group", spec.group());
            m.put("label", spec.label());
            m.put("scene", spec.scene());
            m.put("accepts", spec.accepts());
            String obj = null;
            try {
                obj = objectNameOf(spec.key());
            } catch (Exception e) {
                log.warn("素材状态查询失败 {}: {}", spec.key(), e.getMessage());
            }
            m.put("customized", obj != null);
            if (obj != null) {
                m.put("version", versionOf(obj));
                try {
                    StatObjectResponse st = minioClient.statObject(
                            StatObjectArgs.builder().bucket(bucket).object(obj).build());
                    m.put("updateTime", fmt.format(st.lastModified().withZoneSameInstant(java.time.ZoneId.systemDefault())));
                } catch (Exception e) {
                    m.put("updateTime", "");
                }
            }
            rows.add(m);
        }
        return rows;
    }

    /** 公共 manifest：{key: version}，仅含已自定义的 key（web 端据此远程优先） */
    public Map<String, String> manifest() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Spec spec : CATALOG) {
            String obj;
            try {
                obj = objectNameOf(spec.key());
            } catch (Exception e) {
                continue;
            }
            if (obj != null) {
                m.put(spec.key(), versionOf(obj));
            }
        }
        return m;
    }

    /** version = etag 截断（URL 版本参数用；对象覆盖即变，天然防缓存） */
    private String versionOf(String objectName) {
        try {
            StatObjectResponse st = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            String etag = st.etag() == null ? "" : st.etag().replace("\"", "");
            return etag.length() > 10 ? etag.substring(0, 10) : etag;
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    public record AssetStream(InputStream in, String contentType, String version) {}

    public AssetStream get(String key) {
        String obj = objectNameOf(key);
        if (obj == null) {
            throw new BizException(404, "该素材未上传自定义图，使用内置默认");
        }
        String ext = obj.substring(obj.lastIndexOf('.') + 1).toLowerCase();
        try {
            InputStream in = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(obj).build());
            return new AssetStream(in, "png".equals(ext) ? "image/png" : "image/jpeg", versionOf(obj));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "素材读取失败: " + e.getMessage());
        }
    }

    /** 上传覆盖：同 key 旧对象（含异扩展名）先删后传；PDF 图位同步落覆盖目录 */
    public void upload(String key, InputStream in, long size, String ext) {
        Spec spec = specOf(key);
        if (spec == null) {
            throw new BizException(400, "未知素材位：" + key);
        }
        if (!List.of(spec.accepts().split(",")).contains(ext.toLowerCase())) {
            throw new BizException(400, spec.label() + " 仅支持 " + spec.accepts() + " 格式");
        }
        try {
            String old = objectNameOf(key);
            if (old != null) {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(old).build());
            }
            String obj = PREFIX + key + "." + ext.toLowerCase();
            minioClient.putObject(io.minio.PutObjectArgs.builder()
                    .bucket(bucket).object(obj).stream(in, size, -1)
                    .contentType("png".equals(ext.toLowerCase()) ? "image/png" : "image/jpeg")
                    .build());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("素材上传失败 {}: {}", key, e.getMessage());
            throw new BizException(500, "素材上传失败: " + e.getMessage());
        }
        syncRendererOverrides();
    }

    /** 读取某素材位当前对象字节（批40c 画像垫图用）；未上传返回 null */
    public byte[] readAssetBytes(String key) {
        String obj;
        try {
            obj = objectNameOf(key);
        } catch (Exception e) {
            return null;
        }
        if (obj == null) {
            return null;
        }
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(obj).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("素材读取失败 {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 恢复内置：删 MinIO 对象 + 清覆盖目录文件 */
    public void delete(String key) {
        if (specOf(key) == null) {
            throw new BizException(400, "未知素材位：" + key);
        }
        String obj = objectNameOf(key);
        if (obj != null) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(obj).build());
            } catch (Exception e) {
                log.warn("素材删除失败 {}: {}", key, e.getMessage());
            }
        }
        clearOverrideFiles(key);
    }

    public Path overrideDir() {
        return Paths.get(overrideDir);
    }

    /** PDF 渲染覆盖目录：把已自定义的 pdf 组素材下载为 {key}.{ext}（幂等，上传/删除/启动时调用） */
    public void syncRendererOverrides() {
        try {
            Files.createDirectories(overrideDir());
            for (Spec spec : CATALOG) {
                if (!"pdf".equals(spec.group())) {
                    continue;
                }
                String obj = objectNameOf(spec.key());
                clearOverrideFiles(spec.key());
                if (obj == null) {
                    continue;
                }
                String ext = obj.substring(obj.lastIndexOf('.') + 1).toLowerCase();
                Path target = overrideDir().resolve(spec.key() + "." + ext);
                try (InputStream in = minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(obj).build())) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("素材覆盖目录已同步：{}", overrideDir);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("素材覆盖目录同步失败: {}", e.getMessage());
            throw new BizException(500, "素材覆盖目录同步失败: " + e.getMessage());
        }
    }

    private void clearOverrideFiles(String key) {
        for (String ext : List.of("jpg", "png")) {
            try {
                Files.deleteIfExists(overrideDir().resolve(key + "." + ext));
            } catch (Exception ignored) {
            }
        }
    }
}
