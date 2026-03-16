package com.tongji.knowpost.api.dto;

import java.util.List;

/**
 * 首页 Feed 单条记录。
 */
public record FeedItemResponse(
        String id,//唯一标识ID
        String title,//内容标题
        String description,//内容描述或摘要
        String coverImage,//封面图URL或路径
        List<String> tags,//标签列表集合
        String authorAvatar,//作者头像URL
        String authorNickname,//作者昵称
        String tagJson,//标签的JSON格式字符串
        Long likeCount,//点赞总数
        Long favoriteCount,//收藏总数
        Boolean liked,//当前用户是否已点赞
        Boolean faved,//当前用户是否已收藏
        Boolean isTop//是否置顶标志
) {}