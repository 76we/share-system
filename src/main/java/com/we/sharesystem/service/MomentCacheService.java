package com.we.sharesystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.we.sharesystem.vo.MomentDetailVO;
import com.we.sharesystem.vo.MomentVO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 分享内容缓存服务（Cache-Aside 旁路缓存）。
 *
 * 读：先查 Redis，命中直接返回；未命中查 MySQL 回填 Redis。
 * 写：更新/删除/发布动态后主动失效相关缓存，避免脏读。
 *
 * 使用 StringRedisTemplate + 独立 ObjectMapper 手动序列化 JSON，
 * 与点赞/收藏模块使用的 RedisTemplate<String,Object> 完全隔离，
 * 不改动它们已有的 like 与 collect 系列数据结构。
 */
@Service
@Slf4j
public class MomentCacheService {

    private static final String DETAIL_PREFIX = "moment:detail:";
    private static final String LIST_PREFIX = "moment:list:";
    /** 空值标记：不存在的 id 也缓存一小会儿，挡住"拿随机 id 反复打库"的穿透 */
    private static final String NIL_PREFIX = "moment:nil:";
    private static final Duration DETAIL_TTL = Duration.ofMinutes(30);
    private static final Duration LIST_TTL = Duration.ofMinutes(5);
    private static final Duration NIL_TTL = Duration.ofSeconds(60);

    /** 独立 ObjectMapper：注册 JavaTimeModule 以正确处理 LocalDateTime */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    private StringRedisTemplate redis;

    /** 列表缓存载体：只存 records + total，避免序列化 MyBatis-Plus 的 Page */
    @Data
    public static class CachedList {
        private List<MomentVO> records = new ArrayList<>();
        private long total;
    }

    /** 列表缓存 key：把全部查询条件拼进去，keyword 用 hashCode 避免空格/中文等非法字符 */
    public String listKey(Integer page, Integer size, Long topicId, String sort, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        return LIST_PREFIX + page + ":" + size + ":" + (topicId == null ? 0 : topicId)
                + ":" + (sort == null ? "new" : sort) + ":" + Integer.toHexString(kw.hashCode());
    }

    public MomentDetailVO getDetail(Long id) {
        return read(DETAIL_PREFIX + id, MomentDetailVO.class);
    }

    public void putDetail(Long id, MomentDetailVO vo) {
        write(DETAIL_PREFIX + id, vo, DETAIL_TTL);
    }

    public CachedList getList(String key) {
        return read(key, CachedList.class);
    }

    public void putList(String key, CachedList cache) {
        write(key, cache, LIST_TTL);
    }

    public void evictDetail(Long id) {
        redis.delete(DETAIL_PREFIX + id);
        // 详情有了变动就顺手清掉空值标记，避免"刚发布的动态被判不存在"
        redis.delete(NIL_PREFIX + id);
    }

    /**
     * 该 id 是否已被判定为「不存在」。
     * TTL 只给 60 秒：既能挡住短时间内的重复打库，又不会把"之后真的发布了"误判太久。
     */
    public boolean isNil(Long id) {
        if (id == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(NIL_PREFIX + id));
        } catch (Exception e) {
            log.warn("读取空值标记失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** 把查不到的 id 记下来（含已删除的），后续 60 秒内直接判不存在，不再打库 */
    public void markNil(Long id) {
        if (id == null) {
            return;
        }
        try {
            redis.opsForValue().set(NIL_PREFIX + id, "1", NIL_TTL);
        } catch (Exception e) {
            log.warn("写入空值标记失败 id={}: {}", id, e.getMessage());
        }
    }

    /** 清除全部列表缓存：动态内容/评论数变化会影响任意分页与筛选，用 SCAN 增量删除，避免 KEYS 阻塞 */
    public void evictAllLists() {
        ScanOptions options = ScanOptions.scanOptions().match(LIST_PREFIX + "*").count(100).build();
        try (RedisConnection conn = redis.getConnectionFactory().getConnection();
             Cursor<byte[]> cursor = conn.scan(options)) {
            while (cursor.hasNext()) {
                conn.del(cursor.next());
            }
        } catch (Exception e) {
            log.warn("清理列表缓存失败", e);
        }
    }

    /** 某条动态发生内容变更：删详情 + 清全部列表 */
    public void evictMoment(Long id) {
        evictDetail(id);
        evictAllLists();
    }

    private <T> T read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, type);
        } catch (Exception e) {
            // 缓存异常降级为查库，绝不影响主流程
            log.warn("读取缓存失败，降级查库 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("写入缓存失败，忽略 key={}: {}", key, e.getMessage());
        }
    }
}
