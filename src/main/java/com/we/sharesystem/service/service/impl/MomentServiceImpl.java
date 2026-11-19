package com.we.sharesystem.service.service.impl;

import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.service.MomentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class MomentServiceImpl implements MomentService {

    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private MomentImageMapper momentImageMapper;
    @Autowired
    private FileUtil fileUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(MomentDTO dto, Long userId, List<MultipartFile> images) {
        List<String> uploaded = new ArrayList<>();
        try {
            if(images!=null && images.size()>9){
                throw new RuntimeException("最多上传9张图片");
            }
            Moment moment = new Moment();
            moment.setTitle(dto.getTitle());
            moment.setContent(dto.getContent());
            moment.setTopicId(dto.getTopicId());
            moment.setLocation(dto.getLocation());
            moment.setMood(dto.getMood());
            moment.setUserId(userId);
            moment.setStatus(1);
            momentMapper.insert(moment);

            if(images!=null){
                int order = 0;
                for(MultipartFile file : images) {
                    if(file==null || file.isEmpty())
                        continue;
                    String url = fileUtil.upload(file);
                    uploaded.add(url);
                    MomentImage img = new MomentImage();
                    img.setMomentId(moment.getId());
                    img.setUrl(url);
                    img.setSortOrder(order++);
                    momentImageMapper.insert(img);
                }
            }
        } catch (RuntimeException e) {
            fileUtil.deleteAll(uploaded);
            throw e;
        }

    }
}
