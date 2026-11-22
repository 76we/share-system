package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.we.sharesystem.service.MomentCacheService;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MomentServiceImpl implements MomentService {

    /** 单页最大条数，与 MybatisPlusConfig 里的 maxLimit 保持一致 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上限，防止 (page-1)*size 溢出或用户深翻页打库 */
    private static final int MAX_PAGE = 1000;

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
    @Autowired
    private MomentCacheService cacheService;
    @Autowired
    private com.we.sharesystem.service.FriendshipService friendshipService;

    @Autowired
    private com.we.sharesystem.common.SensitiveWordFilter sensitiveWordFilter;

    /** 内容合规兜底：标题与正文任一命中敏感词即拒绝，不告诉用户命中了哪个词 */
    private void validateContent(String title, String content) {
        if (sensitiveWordFilter.hit(title) || sensitiveWordFilter.hit(content)) {
            throw new com.we.sharesystem.common.BusinessException("内容包含不合规词汇，请修改后再发布");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(MomentDTO dto, Long userId, List<MultipartFile> images) {
        validateContent(dto.getTitle(), dto.getContent());
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

        // 发布新动态后，所有列表缓存都失效（新内容应出现在最新榜/各筛选页）
        cacheService.evictAllLists();
    }

    @Override
    public Page<MomentVO> list(Integer page, Integer size, Long topicId, String sort, String keyword) {
        // 分页归一化：size 传 10000 会让缓存 key 无限膨胀，也让热度榜的全量内存排序白跑
        page = (page == null || page < 1) ? 1 : Math.min(page, MAX_PAGE);
        size = (size == null || size < 1) ? 10 : Math.min(size, MAX_PAGE_SIZE);

        // 缓存命中直接返回（第一次查 MySQL 回填，第二次走 Redis）
        String cacheKey = cacheService.listKey(page, size, topicId, sort, keyword);
        MomentCacheService.CachedList cached = cacheService.getList(cacheKey);
        if (cached != null) {
            Page<MomentVO> hit = new Page<>(page, size, cached.getTotal());
            List<MomentVO> hitRecords = new ArrayList<>(cached.getRecords());
            // 缓存只存内容快照，计数每次用 Redis 实时值覆盖，否则点赞后列表数字不动
            applyRealCounts(hitRecords);
            hit.setRecords(hitRecords);
            return hit;
        }

        boolean hot = "hot".equals(sort);
        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<>();
        qw.eq(Moment::getStatus, 1);
        if (topicId != null) qw.eq(Moment::getTopicId, topicId);
        // 关键词搜索：标题 / 正文 / 地点 任一匹配即可（OR）
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(Moment::getTitle, kw)
                         .or().like(Moment::getContent, kw)
                         .or().like(Moment::getLocation, kw));
        }

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

        Page<MomentVO> result = assembleVOs(records, page, size, total, hot ? likeCountMap : null,
                hot ? collectCountMap : null);
        // 回填缓存：只存 records + total，避免序列化 MyBatis-Plus 的 Page
        MomentCacheService.CachedList toCache = new MomentCacheService.CachedList();
        toCache.setRecords(result.getRecords());
        toCache.setTotal(result.getTotal());
        cacheService.putList(cacheKey, toCache);
        // 返回前统一用 Redis 计数覆盖（最新榜原本只读 DB，会滞后）
        applyRealCounts(result.getRecords());
        return result;
    }

    /**
     * 某用户发布的动态（个人中心）。按创建时间倒序，直接查库不走缓存，
     * 保证发布/删除后立即反映在个人中心。
     */
    @Override
    public Page<MomentVO> listByUser(Long userId, Integer page, Integer size) {
        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<>();
        qw.eq(Moment::getStatus, 1).eq(Moment::getUserId, userId)
          .orderByDesc(Moment::getCreateTime);
        Page<Moment> p = momentMapper.selectPage(new Page<>(page, size), qw);
        return assembleVOs(p.getRecords(), page, size, p.getTotal(), null, null);
    }

    /**
     * 只看朋友：返回"我 + 我好友"发布的动态（按时间倒序）。不走缓存。
     * 好友列表为空时也包含我自己的动态（feed 永不空白，且与"只看自己"语义一致）。
     */
    @Override
    public Page<MomentVO> listByFriends(Long userId, Integer page, Integer size) {
        List<Long> friendIds = friendshipService.myFriends(userId).stream()
                .map(f -> f.getId()).collect(Collectors.toList());
        // 把自己也加进来，"只看朋友"= 朋友 + 我
        List<Long> authorIds = new java.util.ArrayList<>(friendIds);
        authorIds.add(userId);
        authorIds = authorIds.stream().distinct().collect(Collectors.toList());

        LambdaQueryWrapper<Moment> qw = new LambdaQueryWrapper<>();
        qw.eq(Moment::getStatus, 1)
          .in(Moment::getUserId, authorIds)
          .orderByDesc(Moment::getCreateTime);
        Page<Moment> p = momentMapper.selectPage(new Page<>(page, size), qw);
        return assembleVOs(p.getRecords(), page, size, p.getTotal(), null, null);
    }

    /**
     * 按给定动态 id 集合查询（个人中心「我点赞/我收藏」）。
     * 保持传入 ids 的顺序（关系表按时间倒序）；分页在 ids 层面手动切分，
     * 再批量查动态并装配 VO。过滤掉已删除(status!=1)或已不存在的动态。
     */
    @Override
    public Page<MomentVO> listByMomentIds(List<Long> ids, Integer page, Integer size) {
        if (ids == null || ids.isEmpty()) {
            return new Page<>(page, size, 0);
        }
        long total = ids.size();
        int from = (int) Math.min((long) (page - 1) * size, total);
        int to = (int) Math.min(from + (long) size, total);
        List<Long> slice = from >= to ? new ArrayList<>() : ids.subList(from, to);
        if (slice.isEmpty()) {
            Page<MomentVO> empty = new Page<>(page, size, total);
            empty.setRecords(new ArrayList<>());
            return empty;
        }
        List<Moment> moments = momentMapper.selectBatchIds(slice);
        // 按 ids 的顺序排列（selectBatchIds 不保证顺序）
        Map<Long, Moment> map = moments.stream()
                .filter(m -> Integer.valueOf(1).equals(m.getStatus()))
                .collect(Collectors.toMap(Moment::getId, m -> m, (a, b) -> a));
        List<Moment> ordered = new ArrayList<>();
        for (Long id : slice) {
            Moment m = map.get(id);
            if (m != null) ordered.add(m);
        }
        return assembleVOs(ordered, page, size, total, null, null);
    }

    /**
     * 把动态记录装配为 VO 分页结果（列表 / 搜索共用）。
     * 批量查用户与图片避免 N+1；计数映射非空时用 Redis 真实值覆盖（热度榜）。
     */
    private Page<MomentVO> assembleVOs(List<Moment> records, Integer page, Integer size, long total,
                                       Map<Long, Integer> likeCountMap, Map<Long, Integer> collectCountMap) {
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

        boolean useRedisCount = likeCountMap != null && collectCountMap != null;
        List<MomentVO> vos = records.stream().map(m -> {
            MomentVO vo = new MomentVO();
            // 列表只给缩略图（720px），原图留给详情页；老图没有缩略图时 thumbUrl 会回退原图
            List<String> imgs = imageMap.get(m.getId());
            fillBase(vo, m, userMap.get(m.getUserId()),
                    imgs == null ? null : imgs.stream().map(fileUtil::thumbUrl).toList());
            if (useRedisCount) {
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

    /**
     * 用 Redis 里的实时计数覆盖 VO 的点赞/收藏数。
     * 列表缓存（含个人中心、搜索）会把计数一起快照进去，缓存 TTL 内数字就冻住了；
     * 这里每次返回前批量取一次 Redis 真值。Redis 里没有该 key（如重启后未回填）时，
     * 保留 VO 原值（来自 DB），避免冷启动把真实数字显示成 0。
     */
    private void applyRealCounts(List<MomentVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        List<String> likeKeys = vos.stream().map(v -> "like:count:1:" + v.getId()).toList();
        List<String> collectKeys = vos.stream().map(v -> "collect:count:" + v.getId()).toList();
        List<Object> likeVals = redisTemplate.opsForValue().multiGet(likeKeys);
        List<Object> collectVals = redisTemplate.opsForValue().multiGet(collectKeys);
        for (int i = 0; i < vos.size(); i++) {
            Integer like = readCount(likeVals, i);
            if (like != null) vos.get(i).setLikeCount(like);
            Integer collect = readCount(collectVals, i);
            if (collect != null) vos.get(i).setCollectCount(collect);
        }
    }

    /** 取第 i 个 Redis 计数值；越界/缺失/非法一律返回 null（表示"无权威值，别覆盖"） */
    private Integer readCount(List<Object> vals, int i) {
        if (vals == null || i >= vals.size() || vals.get(i) == null) return null;
        try {
            return Integer.parseInt(vals.get(i).toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
        return detail(id, null);
    }

    @Override
    public MomentDetailVO detail(Long id, String visitor) {
        // 已经被判定为不存在的 id：60 秒内直接拒，不再打库（挡缓存穿透）
        if (cacheService.isNil(id)) {
            throw new BusinessException("动态不存在或已删除");
        }
        // 缓存命中直接用（内容 + 评论树），计数另外现算
        MomentDetailVO vo = cacheService.getDetail(id);
        if (vo == null) {
            Moment m = momentMapper.selectById(id);
            if (m == null || !Integer.valueOf(1).equals(m.getStatus())) {
                cacheService.markNil(id);
                throw new BusinessException("动态不存在或已删除");
            }

            vo = new MomentDetailVO();
            User user = userMapper.selectById(m.getUserId());
            List<String> images = momentImageMapper.selectList(
                    new LambdaQueryWrapper<MomentImage>()
                            .eq(MomentImage::getMomentId, id)
                            .orderByAsc(MomentImage::getSortOrder))
                    .stream().map(MomentImage::getUrl).toList();
            fillBase(vo, m, user, images);

            vo.setComments(commentService.getCommentTree(id));
            cacheService.putDetail(id, vo);
        }
        // 浏览量每次返回实时值（缓存里那份是快照，不能直接用）
        vo.setViewCount((int) recordView(id, visitor));
        return vo;
    }

    /**
     * 记一次浏览并返回实时浏览量。
     * 去重：同一访客同一天只算一次（key 带日期，过期 2 天自然回收）。
     * 计数权威在 Redis，DB 只做持久化落账；任何异常都不影响详情页打开。
     */
    private long recordView(Long momentId, String visitor) {
        String countKey = "view:count:" + momentId;
        try {
            if (redisTemplate.opsForValue().get(countKey) == null) {
                Moment m = momentMapper.selectById(momentId);
                long base = (m == null || m.getViewCount() == null) ? 0L : m.getViewCount();
                redisTemplate.opsForValue().setIfAbsent(countKey, base);
            }
            if (visitor != null && !visitor.isBlank()) {
                String dedupKey = "view:dedup:" + momentId + ":" + LocalDate.now();
                Long added = redisTemplate.opsForSet().add(dedupKey, visitor);
                if (added != null && added > 0) {
                    redisTemplate.expire(dedupKey, Duration.ofDays(2));
                    redisTemplate.opsForValue().increment(countKey);
                    momentMapper.update(null, Wrappers.<Moment>lambdaUpdate()
                            .eq(Moment::getId, momentId)
                            .setSql("view_count = view_count + 1"));
                }
            }
            Object v = redisTemplate.opsForValue().get(countKey);
            return v == null ? 0L : Long.parseLong(v.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MomentDTO dto, Long userId) {
        Moment m = momentMapper.selectById(id);
        if (m == null || !Integer.valueOf(1).equals(m.getStatus())) {
            throw new BusinessException("动态不存在或已删除");
        }
        if (!m.getUserId().equals(userId)) {
            throw new BusinessException("无权限修改该动态");
        }
        validateContent(dto.getTitle(), dto.getContent());
        m.setTitle(dto.getTitle());
        m.setContent(dto.getContent());
        m.setLocation(dto.getLocation());
        m.setMood(dto.getMood());
        Long topicId = resolveTopicId(dto);
        if (topicId != null) {
            m.setTopicId(topicId);
        }
        momentMapper.updateById(m);
        // 更新后删除缓存：详情缓存 + 全部列表缓存
        cacheService.evictMoment(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        Moment m = momentMapper.selectById(id);
        if (m == null || !Integer.valueOf(1).equals(m.getStatus())) {
            throw new BusinessException("动态不存在或已删除");
        }
        if (!m.getUserId().equals(userId)) {
            throw new BusinessException("无权限修改该动态");
        }
        m.setStatus(0);
        momentMapper.updateById(m);
        // 删除后清理缓存
        cacheService.evictMoment(id);
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
        try {
            topicMapper.insert(topic);
            return topic.getId();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下另一个请求已建同名话题（topic.name 有唯一索引）：重查复用，避免重复话题
            Topic again = topicMapper.selectOne(
                    new LambdaQueryWrapper<Topic>().eq(Topic::getName, name).last("limit 1"));
            if (again != null) {
                return again.getId();
            }
            throw new BusinessException("话题创建失败，请重试");
        }
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
