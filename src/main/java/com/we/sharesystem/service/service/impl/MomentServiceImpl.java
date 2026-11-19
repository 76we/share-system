package com.we.sharesystem.service.service.impl;

import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.service.MomentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MomentServiceImpl implements MomentService {

    @Autowired
    private MomentMapper momentMapper;

    @Override
    public void publish(MomentDTO dto, Long userId) {
        Moment moment = new Moment();
        moment.setTitle(dto.getTitle());
        moment.setContent(dto.getContent());
        moment.setTopicId(dto.getTopicId());
        moment.setLocation(dto.getLocation());
        moment.setMood(dto.getMood());
        moment.setUserId(userId);
        moment.setStatus(1);
        momentMapper.insert(moment);
    }
}
