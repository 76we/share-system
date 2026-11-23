package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.PageParam;
import com.we.sharesystem.common.TxHooks;
import com.we.sharesystem.dto.PasswordUpdateDTO;
import com.we.sharesystem.dto.ProfileUpdateDTO;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.entity.UserCollect;
import com.we.sharesystem.entity.UserLike;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.mapper.UserCollectMapper;
import com.we.sharesystem.mapper.UserLikeMapper;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.MomentService;
import com.we.sharesystem.service.ProfileService;
import com.we.sharesystem.service.TokenVersionService;
import com.we.sharesystem.vo.MomentVO;
import com.we.sharesystem.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 个人中心服务实现：资料维护 + 创作统计 + 我的发布/点赞/收藏 */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserMapper userMapper;
    private final MomentMapper momentMapper;
    private final UserLikeMapper userLikeMapper;
    private final UserCollectMapper userCollectMapper;
    private final MomentService momentService;
    private final TokenVersionService tokenVersionService;
    private final com.we.sharesystem.common.SensitiveWordFilter sensitiveWordFilter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public UserProfileVO getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        UserProfileVO vo = new UserProfileVO();
        BeanUtils.copyProperties(user, vo);

        // 发布数：status=1 的动态
        Long momentCount = momentMapper.selectCount(
                new LambdaQueryWrapper<Moment>()
                        .eq(Moment::getUserId, userId)
                        .eq(Moment::getStatus, 1));
        vo.setMomentCount(momentCount == null ? 0L : momentCount);

        List<Long> myIds = myMomentIds(userId);
        if (myIds.isEmpty()) {
            vo.setLikeCount(0L);
            vo.setCollectCount(0L);
            return vo;
        }

        // 获赞数：我的动态收到的点赞（targetType=1）
        Long likeCount = userLikeMapper.selectCount(
                new LambdaQueryWrapper<UserLike>()
                        .eq(UserLike::getTargetType, 1)
                        .in(UserLike::getTargetId, myIds));
        vo.setLikeCount(likeCount == null ? 0L : likeCount);

        // 收藏数：我的动态被收藏的次数
        Long collectCount = userCollectMapper.selectCount(
                new LambdaQueryWrapper<UserCollect>()
                        .in(UserCollect::getMomentId, myIds));
        vo.setCollectCount(collectCount == null ? 0L : collectCount);
        return vo;
    }

    @Override
    public UserProfileVO getPublicProfile(Long userId) {
        UserProfileVO vo = getProfile(userId);
        // 对外视角不暴露邮箱
        vo.setEmail(null);
        return vo;
    }

    @Override
    public Page<MomentVO> publicMoments(Long userId, Integer page, Integer size) {
        return momentService.listByUser(userId, page, size);
    }

    /** 我发布的所有动态 id（用于统计获赞/被收藏） */
    private List<Long> myMomentIds(Long userId) {
        return momentMapper.selectList(
                        new LambdaQueryWrapper<Moment>()
                                .select(Moment::getId)
                                .eq(Moment::getUserId, userId)
                                .eq(Moment::getStatus, 1))
                .stream().map(Moment::getId).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long userId, ProfileUpdateDTO dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 邮箱唯一性校验（排除自己）
        if (dto.getEmail() != null && !dto.getEmail().isBlank()
                && !dto.getEmail().equals(user.getEmail())) {
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getEmail, dto.getEmail()));
            if (count != null && count > 0) {
                throw new BusinessException("该邮箱已被使用");
            }
        }
        // 昵称与简介会公开展示，同样过一遍词库
        if (sensitiveWordFilter.hit(dto.getNickname()) || sensitiveWordFilter.hit(dto.getBio())) {
            throw new BusinessException("昵称或简介包含不合规词汇，请修改后再保存");
        }
        if (dto.getNickname() != null) user.setNickname(dto.getNickname().isBlank() ? user.getUsername() : dto.getNickname().trim());
        if (dto.getAvatar() != null) user.setAvatar(dto.getAvatar().isBlank() ? null : dto.getAvatar().trim());
        if (dto.getEmail() != null) user.setEmail(dto.getEmail().isBlank() ? null : dto.getEmail().trim());
        if (dto.getBio() != null) user.setBio(dto.getBio().isBlank() ? null : dto.getBio().trim());
        userMapper.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, PasswordUpdateDTO dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!encoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        user.setPassword(encoder.encode(dto.getNewPassword()));
        userMapper.updateById(user);
        // 改密码后旧 token 全部失效：盗号者拿到的 token 也跟着作废。
        // 放到提交后执行：版本号 +1 与缓存删除如果发生在提交前，
        // 并发请求会读到「旧版本号」并缓存 30 分钟，导致旧 token 继续可用。
        TxHooks.afterCommit(() -> tokenVersionService.bump(userId));
    }

    @Override
    public Page<MomentVO> myMoments(Long userId, Integer page, Integer size) {
        return momentService.listByUser(userId, page, size);
    }

    @Override
    public Page<MomentVO> myLikedMoments(Long userId, Integer page, Integer size) {
        page = PageParam.page(page);
        size = PageParam.size(size, 10);
        // targetType=1（动态）的点赞，按时间倒序
        Page<UserLike> relPage = userLikeMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<UserLike>()
                        .select(UserLike::getTargetId)
                        .eq(UserLike::getUserId, userId)
                        .eq(UserLike::getTargetType, 1)
                        .orderByDesc(UserLike::getCreateTime));
        List<Long> ids = relPage.getRecords().stream().map(UserLike::getTargetId).toList();
        // relPage 已按 page/size 取出「当前页」的 id 集合，这里传 page=1
        // 让 listByMomentIds 不再二次切分（否则第 2 页起 from>=to，永远返回空）。
        // 真实总数由关系表的 total 覆盖回去。
        Page<MomentVO> result = momentService.listByMomentIds(ids, 1, Math.max(ids.size(), 1));
        result.setTotal(relPage.getTotal());
        // size 要还原成请求的分页大小：listByMomentIds 会按传入 size 生成 Page，
        // 若沿用 ids.size() 会让前端拿到 size=1、total=11 算出 11 页
        result.setSize(size);
        return result;
    }

    @Override
    public Page<MomentVO> myCollectedMoments(Long userId, Integer page, Integer size) {
        page = PageParam.page(page);
        size = PageParam.size(size, 10);
        Page<UserCollect> relPage = userCollectMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<UserCollect>()
                        .select(UserCollect::getMomentId)
                        .eq(UserCollect::getUserId, userId)
                        .orderByDesc(UserCollect::getCreateTime));
        List<Long> ids = relPage.getRecords().stream().map(UserCollect::getMomentId).toList();
        // 同上：分页已在 relPage 完成，此处 page=1 避免二次切分
        Page<MomentVO> result = momentService.listByMomentIds(ids, 1, Math.max(ids.size(), 1));
        result.setTotal(relPage.getTotal());
        result.setSize(size);
        return result;
    }
}
