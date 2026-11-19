package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.service.MomentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/moments")
public class MomentController {

    @Autowired
    private MomentService momentService;
    @Autowired
    private FileUtil fileUtil;

    @PostMapping(consumes = "mutipart/form-data")
    public Result<Void> publish(@RequestPart("dto") MomentDTO dto,
                                @RequestPart(value = "images",required = false) List<MultipartFile> images,
                                HttpServletRequest request){
        Long userId = (Long) request.getAttribute("userId");
        momentService.publish(dto,userId,images);
        return Result.ok(null);
    }
}
