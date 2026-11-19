package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.MomentService;
import com.we.sharesystem.vo.MomentVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MomentServiceImpl implements MomentService {

    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private MomentImageMapper momentImageMapper;
    @Autowired
    private FileUtil fileUtil;
    @Autowired
    private UserMapper userMapper;

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

    @Override
    public Page<MomentVO> list(Integer page, Integer size, Long topicId, String sort) {
        Page<Moment> req = new Page<>(page, size);
        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<>();
        qw.eq(Moment::getStatus, 1);
        if (topicId != null) qw.eq(Moment::getTopicId, topicId);
        if ("hot".equals(sort)) {
            qw.last("ORDER BY like_count * 2 + comment_count * 3 + collect_count DESC");
        } else {
            qw.orderByDesc(Moment::getCreateTime);
        }

        Page<Moment> p = momentMapper.selectPage(req, qw);
        Page<MomentVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Moment> records = p.getRecords();
        if (records.isEmpty()) {
            result.setRecords(new ArrayList<>());
            return result;
        }

        // 批量查用户和图片，避免 N+1
        List<Long> userIds = records.stream().map(Moment::getUserId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<Long> momentIds = records.stream().map(Moment::getId).toList();
        List<MomentImage> allImages = momentImageMapper.selectList(
                new LambdaQueryWrapper<MomentImage>().in(MomentImage::getMomentId, momentIds)
                        .orderByAsc(MomentImage::getSortOrder)
        );
        Map<Long, List<String>> imageMap = allImages.stream().collect(Collectors.groupingBy(
                MomentImage::getMomentId,
                Collectors.mapping(MomentImage::getUrl, Collectors.toList())
        ));

        List<MomentVO> vos = records.stream().map(m -> {
            MomentVO vo = new MomentVO();
            BeanUtils.copyProperties(m, vo);
            User u = userMap.get(m.getUserId());
            if (u != null) {
                vo.setNickname(u.getNickname());
                vo.setAvatar(u.getAvatar());
            }
            vo.setImages(imageMap.getOrDefault(m.getId(), new ArrayList<>()));
            return vo;
        }).toList();

        result.setRecords(vos);
        return result;
    }

}
