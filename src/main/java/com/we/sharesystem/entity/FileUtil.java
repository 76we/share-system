package com.we.sharesystem.entity;

import com.we.sharesystem.common.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Component
public class FileUtil {
    private static final String DIR="D:/uploads/";
    private static final String PREFIX="/images/";

    public String upload(MultipartFile file){
        if(file==null ||file.isEmpty()){
            throw new BusinessException("文件为空");
        }
        String original = file.getOriginalFilename();
        String ext = original.substring(original.lastIndexOf(".")).toLowerCase();
        if(!List.of(".jpg",".jpeg",".png").contains(ext)){
            throw new BusinessException("只支持jpg/png格式");
        }
        if(file.getSize()>5L*1024*1024){
            throw new BusinessException("单张图片不能超过5MB");
        }
        String filename = UUID.randomUUID().toString().replace("-","")+ext;
        try {
            Path dir = Paths.get(DIR);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename).toFile());
        } catch (IOException e) {
            throw new BusinessException("图片上传失败");
        }
        return PREFIX+filename;
    }

    public void delete(String url){
        if(url==null || !url.startsWith(PREFIX))
            return;
        try {
            Files.deleteIfExists(Paths.get(DIR + url.substring(PREFIX.length())));
        } catch (IOException ignored) {
        }
    }

    public void deleteAll(List<String> urls){
        if(urls!=null)
            urls.forEach(this::delete);
    }
}
