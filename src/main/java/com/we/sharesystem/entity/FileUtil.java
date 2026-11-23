package com.we.sharesystem.entity;

import com.we.sharesystem.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
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

    /**
     * 像素上限（4000 万）。
     * 5MB 的体积限制挡不住「解压炸弹」：一张几十 KB 的 PNG 可以声明 2 万 × 2 万，
     * ImageIO.read 全解码需要几 GB 堆内存，生产 -Xmx512m 会直接把进程撑爆，
     * 一个请求就能让整个服务不可用。所以解码前先只读文件头拿宽高做体检。
     */
    private static final long MAX_PIXELS = 40_000_000L;

    /* 图片治理参数：原图长边 1600px / 缩略图长边 720px。
       手机直出 4000px 的原图直接进信息流，一张就几 MB，九宫格首屏会被拖死。
       缩略图取 720：首页中栏单图卡片图片区约 740px 宽，720 基本 1:1 不糊；
       九宫格每格约 240px，更是绰绰有余。 */
    private static final int MAX_EDGE = 1600;
    private static final int THUMB_EDGE = 720;
    /** 尺寸和体积都在阈值内就原样保存，避免无谓的二次编码掉画质 */
    private static final long KEEP_AS_IS_SIZE = 800L * 1024;
    private static final float MAIN_QUALITY = 0.85f;
    private static final float THUMB_QUALITY = 0.78f;

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
        // 解码前先看宽高，挡住解压炸弹
        assertPixelBudget(file);

        String uuid = UUID.randomUUID().toString().replace("-", "");
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            BufferedImage src = ImageIO.read(file.getInputStream());
            if (src == null) {
                throw new BusinessException("文件内容不是有效的图片");
            }

            int longest = Math.max(src.getWidth(), src.getHeight());
            boolean smallEnough = longest <= MAX_EDGE && file.getSize() <= KEEP_AS_IS_SIZE;
            // 大 PNG 若无透明通道就改存 JPEG：无损 PNG 一张 1.6MB，同图 JPEG 只要二三百 KB。
            // 有透明通道才保留 PNG，否则透明区域会变成黑/白块。
            String saveExt = (!smallEnough && ".png".equals(ext) && !src.getColorModel().hasAlpha())
                    ? ".jpg" : ext;
            String filename = uuid + saveExt;
            Path mainPath = dir.resolve(filename);

            if (smallEnough) {
                // 尺寸和体积都够小：原样落盘，不重新编码
                file.transferTo(mainPath.toFile());
            } else if (".png".equals(saveExt)) {
                ImageIO.write(resize(src, MAX_EDGE), "png", mainPath.toFile());
            } else {
                writeJpeg(resize(src, MAX_EDGE), mainPath.toFile(), MAIN_QUALITY);
            }
            // 缩略图统一走 JPEG：列表只需要清楚，不需要透明通道
            writeJpeg(resize(src, THUMB_EDGE), dir.resolve(thumbName(filename)).toFile(), THUMB_QUALITY);
            return urlPrefix + filename;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("图片上传失败");
        }
    }

    public void delete(String url) {
        if (url == null || !url.startsWith(urlPrefix)) {
            return;
        }
        String name = url.substring(urlPrefix.length());
        try {
            Path dir = Paths.get(uploadDir);
            Files.deleteIfExists(dir.resolve(name));
            // 缩略图跟着原图一起删，否则会留下孤儿文件慢慢吃掉磁盘
            Files.deleteIfExists(dir.resolve(thumbName(name)));
        } catch (IOException ignored) {
        }
    }

    public void deleteAll(List<String> urls) {
        if (urls != null) {
            urls.forEach(this::delete);
        }
    }

    /**
     * 列表用缩略图、详情用原图。
     * 缩略图不存在时（升级前上传的老图）回退原图，保证老数据不破图。
     */
    public String thumbUrl(String url) {
        if (url == null || !url.startsWith(urlPrefix)) {
            return url;
        }
        String name = url.substring(urlPrefix.length());
        String thumb = thumbName(name);
        try {
            return Files.exists(Paths.get(uploadDir).resolve(thumb)) ? urlPrefix + thumb : url;
        } catch (Exception e) {
            return url;
        }
    }

    /** 等比缩到长边不超过 maxEdge；已经更小则原样返回 */
    private BufferedImage resize(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge) {
            return src;
        }
        double scale = (double) maxEdge / longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        // 有透明通道用 ARGB，否则 RGB —— 否则透明 PNG 缩完背景会变黑
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage dst = new BufferedImage(nw, nh,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    /** 带质量参数的 JPEG 写出（ImageIO.write 无法控制压缩比） */
    private void writeJpeg(BufferedImage img, File out, float quality) throws IOException {
        // JPEG 没有 alpha：先铺白底，防止透明区域变黑
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** abc123.png -> abc123_t.jpg */
    private String thumbName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot < 0 ? filename : filename.substring(0, dot)) + "_t.jpg";
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

    /**
     * 只读文件头拿宽高做像素级体检，不解码整张图。
     * 一旦超过 {@link #MAX_PIXELS} 就直接拒绝，避免 ImageIO.read 把堆撑爆。
     */
    private void assertPixelBudget(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new BusinessException("文件内容不是有效的图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException("文件内容不是有效的图片");
            }
            ImageReader reader = readers.next();
            try {
                // seekForwardOnly=true、ignoreMetadata=true：只解析头部，不读像素数据
                reader.setInput(iis, true, true);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w <= 0 || h <= 0) {
                    throw new BusinessException("文件内容不是有效的图片");
                }
                if (w * h > MAX_PIXELS) {
                    throw new BusinessException("图片尺寸过大（超过 4000 万像素），请压缩后再上传");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BusinessException("图片读取失败，请换一张试试");
        }
    }
}
