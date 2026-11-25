package com.we.sharesystem.entity;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传链路的两件事必须同时成立，缺一个都是线上事故：
 *   1. 元数据要剥离 —— 手机照片里的 GPS 坐标、设备型号、精确拍摄时间，
 *      原样存下来等于把用户家庭住址挂到公网；
 *   2. 方向要转正 —— 剥离元数据会连 EXIF Orientation 一起丢掉，
 *      手机竖拍的照片像素本来是横的，不按标记转正，发布后集体横躺。
 *
 * 这两个约束互相牵制，所以测试用「左红右蓝」的图 + 手工插入的 Orientation
 * 来同时验证：输出尺寸交换了没有、颜色块转到哪个角了。
 */
class FileUtilTest {

    private Path tmpDir;
    private FileUtil fileUtil;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("fileutil-test");
        fileUtil = new FileUtil();
        // 绕开 Spring 注入：@Value 字段在纯单元测试里没有容器来填
        ReflectionTestUtils.setField(fileUtil, "uploadDir", tmpDir.toString());
        ReflectionTestUtils.setField(fileUtil, "urlPrefix", "/images/");
    }

    @Test
    @DisplayName("按 EXIF Orientation 把像素转正，并返回转正后的宽高")
    void appliesOrientationAndReturnsRotatedSize() throws Exception {
        // 40×20 的图，左半红右半蓝；orientation=6 表示"需顺时针旋转 90° 才是正的"
        byte[] jpeg = withExifOrientation(redBlueJpeg(40, 20), 6);

        FileUtil.UploadResult res = fileUtil.upload(
                new MockMultipartFile("images", "phone.jpg", "image/jpeg", jpeg));

        // 转正后宽高必须交换：这条错了，前端拿到的宽高比就是横图的比例，九宫格会跳版
        assertThat(res.width()).isEqualTo(20);
        assertThat(res.height()).isEqualTo(40);

        BufferedImage saved = readUploaded(res);
        assertThat(saved.getWidth()).isEqualTo(20);
        assertThat(saved.getHeight()).isEqualTo(40);

        // 顺时针 90°：原左上角(红)转到右上角，原右上角(蓝)转到右下角。
        // 取靠边的像素避开 JPEG 有损压缩的边缘振铃。
        assertThat(isRed(saved.getRGB(19, 1))).as("右上角应为红色（原左上角转过来）").isTrue();
        assertThat(isBlue(saved.getRGB(19, 38))).as("右下角应为蓝色").isTrue();
    }

    @Test
    @DisplayName("没有 EXIF 时不旋转，宽高保持原样")
    void keepsUprightWhenNoExif() throws Exception {
        byte[] jpeg = redBlueJpeg(40, 20);

        FileUtil.UploadResult res = fileUtil.upload(
                new MockMultipartFile("images", "plain.jpg", "image/jpeg", jpeg));

        assertThat(res.width()).isEqualTo(40);
        assertThat(res.height()).isEqualTo(20);
        BufferedImage saved = readUploaded(res);
        assertThat(isRed(saved.getRGB(1, 1))).as("左上仍是红色").isTrue();
        assertThat(isBlue(saved.getRGB(38, 18))).as("右下仍是蓝色").isTrue();
    }

    @Test
    @DisplayName("落盘文件里不再有任何 EXIF / GPS 元数据")
    void stripsMetadataFromSavedFile() throws Exception {
        byte[] jpeg = withExifOrientation(redBlueJpeg(40, 20), 6);

        FileUtil.UploadResult res = fileUtil.upload(
                new MockMultipartFile("images", "phone.jpg", "image/jpeg", jpeg));

        // 原图确实带 EXIF，否则这个测试什么也没证明
        Metadata source = ImageMetadataReader.readMetadata(new java.io.ByteArrayInputStream(jpeg));
        assertThat(source.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNotNull();

        Metadata saved = ImageMetadataReader.readMetadata(pathOf(res).toFile());
        assertThat(saved.getFirstDirectoryOfType(ExifIFD0Directory.class))
                .as("EXIF IFD0 应已被剥离").isNull();
        assertThat(saved.getFirstDirectoryOfType(GpsDirectory.class))
                .as("GPS 目录应已被剥离").isNull();
    }

    @Test
    @DisplayName("缩略图与原图成对落盘，缩略图长边不超过 720")
    void writesThumbnailAlongside() throws Exception {
        byte[] jpeg = redBlueJpeg(1600, 900);

        FileUtil.UploadResult res = fileUtil.upload(
                new MockMultipartFile("images", "big.jpg", "image/jpeg", jpeg));

        String name = res.url().substring("/images/".length());
        Path thumb = tmpDir.resolve(name.replaceAll("\\.(jpg|jpeg|png)$", "_t.jpg"));
        assertThat(thumb).exists();
        BufferedImage thumbImg = ImageIO.read(thumb.toFile());
        assertThat(Math.max(thumbImg.getWidth(), thumbImg.getHeight())).isLessThanOrEqualTo(720);
    }

    /** 按返回的 URL 找到磁盘上的文件 */
    private Path pathOf(FileUtil.UploadResult res) {
        return tmpDir.resolve(res.url().substring("/images/".length()));
    }

    private BufferedImage readUploaded(FileUtil.UploadResult res) throws IOException {
        return ImageIO.read(pathOf(res).toFile());
    }

    private static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r > 170 && g < 90 && b < 90;
    }

    private static boolean isBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return b > 170 && r < 90 && g < 90;
    }

    /** 左半红、右半蓝的测试图（用来判断旋转方向对不对） */
    private static byte[] redBlueJpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /**
     * 在 SOI 之后插入一个只含 Orientation 的 APP1(EXIF) 段。
     * ImageIO 不会写 EXIF，只能手工拼：小端 TIFF 头 + 一个 0x0112 的 SHORT entry。
     */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[]{'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00}); // 小端 + 魔数 42 + IFD0 偏移 8
        tiff.write(new byte[]{0x01, 0x00});                                    // 1 个 entry
        tiff.write(new byte[]{0x12, 0x01});                                    // tag 0x0112 = Orientation
        tiff.write(new byte[]{0x03, 0x00});                                    // type 3 = SHORT
        tiff.write(new byte[]{0x01, 0x00, 0x00, 0x00});                        // count 1
        tiff.write(new byte[]{(byte) orientation, 0x00, 0x00, 0x00});          // value（SHORT 只占前 2 字节）
        tiff.write(new byte[]{0x00, 0x00, 0x00, 0x00});                        // 没有下一个 IFD

        byte[] tiffBytes = tiff.toByteArray();
        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(0xFF);
        app1.write(0xE1);
        int len = 2 + 6 + tiffBytes.length;   // 长度字段自身 2 字节 + "Exif\0\0" + TIFF 数据
        app1.write((len >> 8) & 0xFF);
        app1.write(len & 0xFF);
        app1.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        app1.write(tiffBytes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // SOI
        out.write(app1.toByteArray());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** 每个用例都会往临时目录写几张图，跑完清掉，别在 C 盘堆垃圾 */
    @AfterEach
    void cleanUp() throws IOException {
        try (Stream<Path> walk = Files.walk(tmpDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
