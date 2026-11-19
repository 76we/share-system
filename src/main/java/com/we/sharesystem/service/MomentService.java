package com.we.sharesystem.service;

import com.we.sharesystem.dto.MomentDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MomentService {
    void publish(MomentDTO dto, Long userId, List<MultipartFile> imageUrl);
}
