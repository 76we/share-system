package com.we.sharesystem.service;

import com.we.sharesystem.vo.FriendSearchVO;
import com.we.sharesystem.vo.FriendVO;

import java.util.List;

/**
 * 好友关系：双向对称，双方同意才成为好友。
 * user_a < user_b 归一化，from_user 记录发起方。
 */
public interface FriendshipService {

    /** 按昵称/用户名模糊搜人，排除自己，标注与我的关系（0无 1好友 2我申请中 3对方申请我） */
    List<FriendSearchVO> searchUser(Long meId, String keyword);

    /** 我发起好友申请（若已有记录则幂等）；已好友则提示。通知对方。 */
    void request(Long meId, Long targetId);

    /** 同意某人对我的申请。通知申请人。 */
    void accept(Long meId, Long applicantId);

    /** 拒绝某人对我的申请（删除该申请记录）。 */
    void reject(Long meId, Long applicantId);

    /** 我是否已与 userId 成为好友（status=1） */
    boolean isFriend(Long meId, Long otherId);

    /** 我的好友列表（status=1） */
    List<FriendVO> myFriends(Long meId);

    /** 我收到的待处理申请（from_user=对方, user 含我, status=0） */
    List<FriendVO> incomingRequests(Long meId);

    /** 我发出的待处理申请（from_user=我, status=0） */
    List<FriendVO> mySentRequests(Long meId);
}
