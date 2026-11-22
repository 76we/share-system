package com.we.sharesystem.entity;

import com.we.sharesystem.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 图片上传工具。
 * 目录与访问前缀改为配置项（application.yml 的 file.upload-dir / file.url-prefix），
 * 上线时用环境变量 UPLOAD_DIR 指向 /opt/share/uploads/，不再写死 Windows 盘符。
 */
@Component
public class FileUtil {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final List<String> ALLOWED_EXT = List.of(".jpg", ".jpeg", ".png");

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.url-prefix:/images/}")
    private String urlPrefix;

    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("单张图片不能超过5MB");
        }

        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BusinessException("文件名不合法");
        }
        // 没有扩展名时 lastIndexOf 返回 -1，直接 substring 会抛越界异常，这里先判断
        int dot = original.lastIndexOf('.');
        if (dot < 0 || dot == original.length() - 1) {
            throw new BusinessException("只支持jpg/png格式");
        }
        String ext = original.substring(dot).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("只支持jpg/png格式");
        }
        // 校验文件头，防止把任意文件改后缀上传
        if (!isRealImage(file)) {
            throw new BusinessException("文件内容不是有效的图片");
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename).toFile());
        } catch (IOException e) {
            throw new BusinessException("图片上传失败");
        }
        return urlPrefix + filename;
    }

    public void delete(String url) {
        if (url == null || !url.startsWith(urlPrefix)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(uploadDir).resolve(url.substring(urlPrefix.length())));
        } catch (IOException ignored) {
        }
    }

    public void deleteAll(List<String> urls) {
        if (urls != null) {
            urls.forEach(this::delete);
        }
    }

    /** 读取前几个字节判断真实图片类型：PNG(89 50 4E 47) / JPEG(FF D8 FF) */
    private boolean isRealImage(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(8);
            if (head.length < 4) {
                return false;
            }
            if (head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
                return true;
            }
            return head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
        } catch (IOException e) {
            return false;
        }
    }
}
