package com.we.sharesystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.service.MomentService;
import com.we.sharesystem.vo.MomentDetailVO;
import com.we.sharesystem.vo.MomentVO;
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

    @PostMapping(consumes = "multipart/form-data")
    public Result<Void> publish(@RequestPart("dto") MomentDTO dto,
                                @RequestPart(value = "images",required = false) List<MultipartFile> images,
                                HttpServletRequest request){
        Long userId = (Long) request.getAttribute("userId");
        momentService.publish(dto,userId,images);
        return Result.ok(null);
    }
    @GetMapping("/list")
    public Result<Page<MomentVO>> list(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer size,
                                       @RequestParam(required = false) Long topicId,
                                       @RequestParam(defaultValue = "new") String sort){
        return Result.ok(momentService.list(page, size, topicId, sort));
    }

    /**
     * 动态详情（含作者、图片、评论树）
     */
    @GetMapping("/{id}")
    public Result<MomentDetailVO> detail(@PathVariable Long id){
        return Result.ok(momentService.detail(id));
    }
}
