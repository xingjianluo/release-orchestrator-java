package com.github.xingjianluo.releaseorchestratorjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class ReleaseControllerTest extends AbstractIntegrationTest {

    @BeforeAll
    static void initDir() throws Exception {
        initWorkDir(ReleaseControllerTest.class);
    }

    private static final Logger log = LoggerFactory.getLogger(ReleaseControllerTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void endpointsExposeDistinguishableErrorCodes() throws Exception {
        // 非法版本号 -> 409 INVALID_VERSION
        String badVersion = "{\"service\":\"api\",\"version\":\"not-a-version\",\"sourceRef\":\"r\"}";
        log.info("REST 校验非法版本号请求体={}", badVersion);
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content(badVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_VERSION"));

        // 合法提交 -> BUILD 完成，IN_PROGRESS
        String ok = "{\"service\":\"api\",\"version\":\"1.0.0\",\"sourceRef\":\"r\",\"changeNotes\":[]}";
        String response = mockMvc.perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        String releaseId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("releaseId").asText();
        log.info("REST 提交成功 releaseId={}", releaseId);

        // 重复提交 -> 409 DUPLICATE_RELEASE
        mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RELEASE"));

        // 查询不存在的发布 -> 404 RELEASE_NOT_FOUND
        mockMvc.perform(get("/api/releases/rel-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RELEASE_NOT_FOUND"));
    }
}
