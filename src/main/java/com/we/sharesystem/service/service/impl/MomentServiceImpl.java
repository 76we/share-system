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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

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
        boolean hot = "hot".equals(sort);
        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<>();
        qw.eq(Moment::getStatus, 1);
        if (topicId != null) qw.eq(Moment::getTopicId, topicId);

        final Map<Long, Integer> likeCountMap;
        final Map<Long, Integer> collectCountMap;

        List<Moment> records;
        long total;

        if (hot) {
            // 热度榜：点赞/收藏计数在 Redis、不回写 moment 表，SQL 的 ORDER BY 拿不到真实值。
            // 故全量查出候选 → 从 Redis 批量取真实计数 → 内存算分排序 → 手动分页，保证全局名次正确。
            List<Moment> all = momentMapper.selectList(qw);
            final Map<Long, Integer> likes = batchCount(all, id -> "like:count:1:" + id);   // targetType=1 表示动态
            final Map<Long, Integer> collects = batchCount(all, id -> "collect:count:" + id);
            all.sort((a, b) -> {
                int sa = hotScore(a, likes, collects);
                int sb = hotScore(b, likes, collects);
                if (sa != sb) return Integer.compare(sb, sa);            // 分数降序
                if (a.getCreateTime() != null && b.getCreateTime() != null) {
                    return b.getCreateTime().compareTo(a.getCreateTime()); // 同分按时间倒序
                }
                return 0;
            });
            likeCountMap = likes;
            collectCountMap = collects;
            total = all.size();
            int from = (int) Math.min((long) (page - 1) * size, total);
            int to = (int) Math.min(from + (long) size, total);
            records = from >= to ? new ArrayList<>() : new ArrayList<>(all.subList(from, to));
        } else {
            // 最新榜：按创建时间倒序，交给 MyBatis-Plus 分页
            likeCountMap = Collections.emptyMap();
            collectCountMap = Collections.emptyMap();
            qw.orderByDesc(Moment::getCreateTime);
            Page<Moment> p = momentMapper.selectPage(new Page<>(page, size), qw);
            total = p.getTotal();
            records = p.getRecords();
        }

        Page<MomentVO> result = new Page<>(page, size, total);
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
            if (hot) {
                // 热度榜把计数填成 Redis 真实值，保证列表展示与排序依据一致
                vo.setLikeCount(likeCountMap.getOrDefault(m.getId(), 0));
                vo.setCollectCount(collectCountMap.getOrDefault(m.getId(), 0));
            }
            return vo;
        }).toList();

        result.setRecords(vos);
        return result;
    }

    /** 批量从 Redis 取计数，key 缺失按 0 计（避免逐条查询 N+1） */
    private Map<Long, Integer> batchCount(List<Moment> moments, Function<Long, String> keyFn) {
        if (moments.isEmpty()) return Collections.emptyMap();
        List<String> keys = moments.stream().map(m -> keyFn.apply(m.getId())).toList();
        List<Object> vals = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, Integer> map = new HashMap<>();
        for (int i = 0; i < moments.size(); i++) {
            Object v = (vals == null) ? null : vals.get(i);
            int c = 0;
            if (v != null) {
                try {
                    c = Integer.parseInt(v.toString());
                } catch (NumberFormatException ignored) {
                    // 非法值按 0 处理
                }
            }
            map.put(moments.get(i).getId(), c);
        }
        return map;
    }

    /** 热度 = 点赞*2 + 评论*3 + 收藏 */
    private int hotScore(Moment m, Map<Long, Integer> likeMap, Map<Long, Integer> collectMap) {
        int like = likeMap.getOrDefault(m.getId(), 0);
        int collect = collectMap.getOrDefault(m.getId(), 0);
        int comment = m.getCommentCount() == null ? 0 : m.getCommentCount();
        return like * 2 + comment * 3 + collect;
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
