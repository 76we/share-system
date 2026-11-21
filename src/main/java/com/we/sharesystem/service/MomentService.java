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
    MomentDetailVO detail(Long id);
    /** 更新动态（仅作者），更新后失效缓存 */
    void update(Long id, MomentDTO dto, Long userId);
    /** 删除动态（仅作者，逻辑删除），删除后失效缓存 */
    void delete(Long id, Long userId);
}
