package com.tongji.knowpost.api;

import com.tongji.auth.config.SecurityConfig;
import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowPostController.class)
@Import(SecurityConfig.class)
class KnowPostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowPostService knowPostService;

    @MockBean
    private KnowPostFeedService knowPostFeedService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void feedShouldAllowAnonymousAccessAndUseDefaultPagination() throws Exception {
        FeedPageResponse response = new FeedPageResponse(
                List.of(new FeedItemResponse(
                        "101",
                        "首页帖子",
                        "公开内容",
                        "https://img.example.com/cover.png",
                        List.of("校园", "活动"),
                        "https://img.example.com/avatar.png",
                        "张三",
                        "[\"校园\"]",
                        12L,
                        5L,
                        false,
                        false,
                        null
                )),
                1,
                20,
                true
        );
        when(knowPostFeedService.getPublicFeed(1, 20, null)).thenReturn(response);

        mockMvc.perform(get("/api/v1/knowposts/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].id").value("101"))
                .andExpect(jsonPath("$.items[0].title").value("首页帖子"))
                .andExpect(jsonPath("$.items[0].liked").value(false));

        verify(knowPostFeedService).getPublicFeed(1, 20, null);
        verifyNoInteractions(jwtService);
    }

    @Test
    void feedShouldResolveCurrentUserWhenJwtIsPresent() throws Exception {
        FeedPageResponse response = new FeedPageResponse(
                List.of(new FeedItemResponse(
                        "202",
                        "登录态帖子",
                        "带用户态增强",
                        "https://img.example.com/cover-2.png",
                        List.of("推荐"),
                        "https://img.example.com/avatar-2.png",
                        "李四",
                        "[\"推荐\"]",
                        20L,
                        8L,
                        true,
                        false,
                        null
                )),
                2,
                5,
                false
        );
        when(jwtService.extractUserId(any(Jwt.class))).thenReturn(42L);
        when(knowPostFeedService.getPublicFeed(2, 5, 42L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/knowposts/feed?page=2&size=5")
                        .with(jwt().jwt(jwt -> jwt.claim("uid", 42L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].id").value("202"))
                .andExpect(jsonPath("$.items[0].liked").value(true))
                .andExpect(jsonPath("$.items[0].faved").value(false));

        verify(jwtService).extractUserId(any(Jwt.class));
        verify(knowPostFeedService).getPublicFeed(2, 5, 42L);
    }
}
