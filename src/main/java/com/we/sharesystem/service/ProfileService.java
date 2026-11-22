package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.dto.PasswordUpdateDTO;
import com.we.sharesystem.dto.ProfileUpdateDTO;
import com.we.sharesystem.vo.MomentVO;
import com.we.sharesystem.vo.UserProfileVO;

/** 个人中心服务 */
public interface ProfileService {
    /** 我的资料 + 统计 */
    UserProfileVO getProfile(Long userId);

    /** 编辑资料 */
    void updateProfile(Long userId, ProfileUpdateDTO dto);

    /** 修改密码 */
    void changePassword(Long userId, PasswordUpdateDTO dto);

    /** 我发布的动态 */
    Page<MomentVO> myMoments(Long userId, Integer page, Integer size);

    /** 我点赞的动态 */
    Page<MomentVO> myLikedMoments(Long userId, Integer page, Integer size);

    /** 我收藏的动态 */
    Page<MomentVO> myCollectedMoments(Long userId, Integer page, Integer size);
}
