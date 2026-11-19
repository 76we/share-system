package com.we.sharesystem.service;

import com.we.sharesystem.dto.MomentDTO;

public interface MomentService {
    void publish(MomentDTO dto,Long userId,String imageUrl);
}
