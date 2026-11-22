package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.vo.MomentDetailVO;
import com.we.sharesystem.vo.MomentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MomentService {
    void publish(MomentDTO dto, Long userId, List<MultipartFile> imageUrl);
    /** 动态列表 / 搜索：keyword 非空时按标题/正文/地点匹配 */
    Page<MomentVO> list(Integer page, Integer size, Long topicId, String sort, String keyword);
    /** 某用户发布的动态（个人中心，不走缓存） */
    Page<MomentVO> listByUser(Long userId, Integer page, Integer size);
    /** 只看朋友：我 + 我好友 发布的动态，按时间倒序，不走缓存 */
    Page<MomentVO> listByFriends(Long userId, Integer page, Integer size);
    /** 按动态 id 集合查询并保持传入顺序（个人中心点赞/收藏，不走缓存） */
    Page<MomentVO> listByMomentIds(java.util.List<Long> ids, Integer page, Integer size);
    MomentDetailVO detail(Long id);

    /**
     * 动态详情（带浏览量统计）。visitor 为去重标识（登录用 u{userId}，游客用 IP），
     * 同一标识同一天只计一次浏览。传 null 则只读不计数（供分享页等内部调用）。
     */
    MomentDetailVO detail(Long id, String visitor);
    /** 更新动态（仅作者），更新后失效缓存 */
    void update(Long id, MomentDTO dto, Long userId);
    /** 删除动态（仅作者，逻辑删除），删除后失效缓存 */
    void delete(Long id, Long userId);
}
