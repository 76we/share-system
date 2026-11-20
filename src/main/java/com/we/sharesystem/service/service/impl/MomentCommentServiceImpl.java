package com.we.sharesystem.service.service.impl;

import com.we.sharesystem.dto.CommentAddDTO;
import com.we.sharesystem.entity.MomentComment;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.MomentCommentMapper;
import com.we.sharesystem.service.MomentCommentService;
import com.we.sharesystem.service.UserService;
import com.we.sharesystem.vo.CommentVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
public class MomentCommentServiceImpl implements MomentCommentService {

    @Resource
    private MomentCommentMapper commentMapper;

    @Resource
    private UserService userService;

    // 敏感词简单拦截（可扩展词库）
    private final List<String> BAD_WORD = Arrays.asList("垃圾", "傻逼", "脏话", "违规词");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addComment(CommentAddDTO dto, Long loginUserId) {
        // 1.敏感词校验
        for (String word : BAD_WORD) {
            if (dto.getContent().contains(word)) {
                throw new RuntimeException("评论包含违规内容！");
            }
        }

        // 2.构造数据
        MomentComment comment = new MomentComment();
        comment.setMomentId(dto.getMomentId());
        comment.setUserId(loginUserId);
        comment.setParentId(dto.getParentId() == null ? 0 : dto.getParentId());
        comment.setReplyUserId(dto.getReplyUserId());
        comment.setContent(dto.getContent().trim());







        comment.setLikeCount(0);
        comment.setStatus(1);

        commentMapper.insert(comment);
    }

    @Override
    public List<CommentVO> getCommentTree(Long momentId) {
        // 1.查询所有评论（扁平数据）
        List<MomentComment> allComment = commentMapper.listByMomentId(momentId);
        if (allComment == null || allComment.isEmpty()) {
            return new ArrayList<>();
        }

        // 2.收集所有用户ID，批量查询用户信息（解决N+1）
        Set<Long> userIdSet = new HashSet<>();
        for (MomentComment c : allComment) {
            userIdSet.add(c.getUserId());
            if (c.getReplyUserId() != null) {
                userIdSet.add(c.getReplyUserId());
            }
        }
        Map<Long, User> userMap = userService.getUserMapByIds(userIdSet);

        // 3.内存组装树形结构
        Map<Long, CommentVO> voMap = new HashMap<>();
        List<CommentVO> rootList = new ArrayList<>();

        for (MomentComment comment : allComment) {
            CommentVO vo = new CommentVO();
            vo.setId(comment.getId());
            vo.setMomentId(comment.getMomentId());
            vo.setParentId(comment.getParentId());
            vo.setReplyUserId(comment.getReplyUserId());
            vo.setContent(comment.getContent());
            vo.setLikeCount(comment.getLikeCount());
            vo.setCreateTime(comment.getCreateTime());

            // 填充评论人信息
            User user = userMap.get(comment.getUserId());
            if (user != null) {
                vo.setCommentUserId(user.getId());
                vo.setCommentUsername(user.getUsername());
                vo.setCommentAvatar(user.getAvatar());
            }

            // 填充被回复人昵称
            if (comment.getReplyUserId() != null) {
                User replyUser = userMap.get(comment.getReplyUserId());
                if (replyUser != null) {
                    vo.setReplyUsername(replyUser.getUsername());
                }
            }

            voMap.put(vo.getId(), vo);

            // 一级评论放入根节点
            if (comment.getParentId() == 0) {
                rootList.add(vo);
            } else {
                // 二级评论挂载父节点
                CommentVO parent = voMap.get(comment.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                }
            }
        }
        return rootList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long loginUserId) {
        MomentComment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() == 0) {
            throw new RuntimeException("评论不存在或已删除");
        }

        // 权限校验：只能删自己的评论
        if (!comment.getUserId().equals(loginUserId)) {
            throw new RuntimeException("无权限删除该评论");
        }

        // 逻辑删除
        comment.setStatus(0);
        commentMapper.updateById(comment);
    }
}
