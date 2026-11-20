package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.vo.MomentDetailVO;
import com.we.sharesystem.vo.MomentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MomentService {
    void publish(MomentDTO dto, Long userId, List<MultipartFile> imageUrl);
    Page<MomentVO> list(Integer page,Integer size,Long topicId,String sort);
    MomentDetailVO detail(Long id);
}
