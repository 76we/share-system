package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.entity.Topic;
import com.we.sharesystem.mapper.TopicMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/topics")
public class TopicController {
    @Autowired
    private TopicMapper topicMapper;

    @GetMapping
    public Result<List<Topic>> list(){
        return Result.ok(topicMapper.selectList(null));
    }

    @PostMapping
    public Result<Void> add(@RequestBody Topic topic){
        topicMapper.insert(topic);
        return Result.ok(null);
    }
}
