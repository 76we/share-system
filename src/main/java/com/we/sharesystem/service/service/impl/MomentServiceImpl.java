package com.we.sharesystem.service.service.impl;

import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.service.MomentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MomentServiceImpl implements MomentService {

    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private MomentImageMapper momentImageMapper;

    @Override
    public void publish(MomentDTO dto, Long userId,String imageUrl) {
        Moment moment = new Moment();
        moment.setTitle(dto.getTitle());
        moment.setContent(dto.getContent());
        moment.setTopicId(dto.getTopicId());
        moment.setLocation(dto.getLocation());
        moment.setMood(dto.getMood());
        moment.setUserId(userId);
        moment.setStatus(1);
        momentMapper.insert(moment);

        if(imageUrl!=null){
            MomentImage img = new MomentImage();
            img.setMomentId(moment.getId());
            img.setUrl(imageUrl);
            img.setSortOrder(0);
            momentImageMapper.insert(img);
        }
    }
}
