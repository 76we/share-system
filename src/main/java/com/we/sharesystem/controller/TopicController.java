package com.we.sharesystem.controller;

import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.entity.Topic;
import com.we.sharesystem.mapper.TopicMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/topics")
public class TopicController {

    /** 话题名长度上限，与前端 maxlength 保持一致 */
    private static final int MAX_NAME_LENGTH = 30;

    @Autowired
    private TopicMapper topicMapper;

    @GetMapping
    public Result<List<Topic>> list() {
        return Result.ok(topicMapper.selectList(null));
    }

    /**
     * 新增话题。需要登录（见 LoginInterceptor），且只接受 name / description，
     * 服务端自行生成 id 与 createTime，防止客户端伪造主键或字段。
     */
    @PostMapping
    public Result<Void> add(@RequestBody Topic topic) {
        if (topic == null || topic.getName() == null || topic.getName().isBlank()) {
            throw new BusinessException("话题名不能为空");
        }
        String name = topic.getName().trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("话题名不能超过 " + MAX_NAME_LENGTH + " 个字");
        }
        Topic t = new Topic();
        t.setName(name);
        t.setDescription(topic.getDescription());
        topicMapper.insert(t);
        return Result.ok(null);
    }
}
