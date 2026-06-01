package com.tongji.knowpost.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.cache.hotkey.HotKeyDetector;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostFeedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowPostFeedServiceImplTest {

    @Mock
    private KnowPostMapper mapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private CounterService counterService;

    @Mock
    private HotKeyDetector hotKeyDetector;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private Cache<String, FeedPageResponse> feedPublicCache;
    private Cache<String, FeedPageResponse> feedMineCache;
    private KnowPostFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        feedPublicCache = Caffeine.newBuilder().build();
        feedMineCache = Caffeine.newBuilder().build();

        service = new KnowPostFeedServiceImpl(
                mapper,
                redis,
                new ObjectMapper(),
                counterService,
                feedPublicCache,
                feedMineCache,
                hotKeyDetector
        );
    }

    @Test
    void getPublicFeedShouldRecomputeUserFlagsFromLocalCache() {
        FeedItemResponse cachedItem = new FeedItemResponse(
                "100",
                "缓存中的首页帖子",
                "公共缓存中的基础数据",
                "https://img.example.com/cover.png",
                List.of("技术"),
                "https://img.example.com/avatar.png",
                "作者A",
                "[\"技术\"]",
                7L,
                3L,
                false,
                false,
                null
        );
        feedPublicCache.put("feed:public:20:1:v1", new FeedPageResponse(List.of(cachedItem), 1, 20, true));
        when(redis.getExpire("feed:item:100")).thenReturn(0L);
        when(counterService.isLiked("knowpost", "100", 88L)).thenReturn(true);
        when(counterService.isFaved("knowpost", "100", 88L)).thenReturn(false);

        FeedPageResponse response = service.getPublicFeed(1, 20, 88L);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().liked()).isTrue();
        assertThat(response.items().getFirst().faved()).isFalse();
        assertThat(response.items().getFirst().likeCount()).isEqualTo(7L);
        assertThat(response.items().getFirst().favoriteCount()).isEqualTo(3L);

        verify(counterService).isLiked("knowpost", "100", 88L);
        verify(counterService).isFaved("knowpost", "100", 88L);
        verify(mapper, never()).listFeedPublic(anyInt(), anyInt());
    }

    @Test
    void getPublicFeedShouldClampPaginationAndMapDatabaseRows() {
        KnowPostFeedRow row = new KnowPostFeedRow();
        row.setId(101L);
        row.setTitle("数据库首页帖子");
        row.setDescription("数据库返回的数据");
        row.setTags("[\"校园\",\"通知\"]");
        row.setImgUrls("[\"https://img.example.com/db-cover.png\"]");
        row.setAuthorAvatar("https://img.example.com/db-avatar.png");
        row.setAuthorNickname("作者B");
        row.setAuthorTagJson("[\"校园\"]");
        row.setIsTop(Boolean.TRUE);

        when(redis.opsForList()).thenReturn(listOperations);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(redis.opsForSet()).thenReturn(setOperations);
        when(listOperations.range(anyString(), eq(0L), eq(49L))).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(mapper.listFeedPublic(51, 0)).thenReturn(List.of(row));
        when(counterService.getCounts(eq("knowpost"), eq("101"), anyList())).thenReturn(counts(11L, 4L));

        FeedPageResponse response = service.getPublicFeed(0, 99, null);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.hasMore()).isFalse();
        assertThat(response.items()).hasSize(1);
        FeedItemResponse item = response.items().getFirst();
        assertThat(item.id()).isEqualTo("101");
        assertThat(item.title()).isEqualTo("数据库首页帖子");
        assertThat(item.coverImage()).isEqualTo("https://img.example.com/db-cover.png");
        assertThat(item.tags()).containsExactly("校园", "通知");
        assertThat(item.likeCount()).isEqualTo(11L);
        assertThat(item.favoriteCount()).isEqualTo(4L);
        assertThat(item.liked()).isFalse();
        assertThat(item.faved()).isFalse();
        assertThat(item.isTop()).isNull();

        verify(mapper).listFeedPublic(51, 0);
        verify(counterService).getCounts("knowpost", "101", List.of("like", "fav"));
        verify(counterService, never()).isLiked(anyString(), anyString(), anyLong());
        verify(counterService, never()).isFaved(anyString(), anyString(), anyLong());
    }

    private Map<String, Long> counts(long likeCount, long favCount) {
        Map<String, Long> counts = new HashMap<>();
        counts.put("like", likeCount);
        counts.put("fav", favCount);
        return counts;
    }
}
