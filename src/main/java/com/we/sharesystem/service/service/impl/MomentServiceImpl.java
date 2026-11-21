package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.entity.Topic;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.mapper.TopicMapper;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.MomentCommentService;
import com.we.sharesystem.service.MomentService;
import com.we.sharesystem.vo.MomentDetailVO;
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
    @Autowired
    private TopicMapper topicMapper;
    @Autowired
    private MomentCommentService commentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(MomentDTO dto, Long userId, List<MultipartFile> images) {
        List<String> uploaded = new ArrayList<>();
        try {
            if(images!=null && images.size()>9){
                throw new BusinessException("最多上传9张图片");
            }
            Moment moment = new Moment();
            moment.setTitle(dto.getTitle());
            moment.setContent(dto.getContent());
            moment.setTopicId(resolveTopicId(dto));
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
            fillBase(vo, m, userMap.get(m.getUserId()), imageMap.get(m.getId()));
            return vo;
        }).toList();

        result.setRecords(vos);
        return result;
    }

    @Override
    public MomentDetailVO detail(Long id) {
        Moment m = momentMapper.selectById(id);
        if (m == null || !Integer.valueOf(1).equals(m.getStatus())) {
            throw new BusinessException("动态不存在或已删除");
        }

        MomentDetailVO vo = new MomentDetailVO();
        User user = userMapper.selectById(m.getUserId());
        List<String> images = momentImageMapper.selectList(
                new LambdaQueryWrapper<MomentImage>()
                        .eq(MomentImage::getMomentId, id)
                        .orderByAsc(MomentImage::getSortOrder))
                .stream().map(MomentImage::getUrl).toList();
        fillBase(vo, m, user, images);

        vo.setComments(commentService.getCommentTree(id));
        return vo;
    }

    /**
     * 解析话题 id：前端"其它"自定义话题传了 topicName 时，按名称查话题，
     * 不存在则新建（这样自定义话题会进入 topic 表，首页筛选 tab 自动出现）；
     * 未传自定义名则沿用前端选中的 topicId。
     */
    private Long resolveTopicId(MomentDTO dto) {
        String name = dto.getTopicName();
        if (name == null || name.isBlank()) {
            return dto.getTopicId();
        }
        name = name.trim();
        Topic exist = topicMapper.selectOne(
                new LambdaQueryWrapper<Topic>().eq(Topic::getName, name).last("limit 1"));
        if (exist != null) {
            return exist.getId();
        }
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription("用户自定义话题");
        topicMapper.insert(topic);
        return topic.getId();
    }

    // 装配动态基础信息（作者昵称头像 + 图片列表），列表与详情共用
    private void fillBase(MomentVO vo, Moment m, User user, List<String> images) {
        BeanUtils.copyProperties(m, vo);
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        vo.setImages(images == null ? new ArrayList<>() : images);
    }

}
