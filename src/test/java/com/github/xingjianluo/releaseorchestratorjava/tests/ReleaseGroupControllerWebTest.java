package com.github.xingjianluo.releaseorchestratorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 发布组 REST 端到端：提交、推进、查询与稳定错误码映射。 */
@AutoConfigureMockMvc
class ReleaseGroupControllerWebTest extends AbstractOrchestratorTest {

    private static final String COMMIT = "4444444789abcdef0123456789abcdef01234567";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private String groupBody(String key, String... services) {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < services.length; i++) {
            if (i > 0) {
                members.append(',');
            }
            members.append("{\"serviceName\":\"").append(services[i])
                    .append("\",\"commit\":\"").append(COMMIT)
                    .append("\",\"sourceContent\":\"src-").append(services[i]).append("\"}");
        }
        return "{\"groupKey\":\"" + key + "\",\"members\":[" + members + "]}";
    }

    @Test
    void groupLifecycleOverHttp() throws Exception {
        MvcResult submitted = mvc().perform(post("/api/release-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody("web-1", "web-a", "web-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.releases.length()").value(2))
                .andReturn();
        String body = submitted.getResponse().getContentAsString();
        String groupId = com.jayway.jsonpath.JsonPath.read(body, "$.groupId");
        testLog.info("group submitted over http id={}", groupId);

        for (int i = 0; i < 4; i++) {
            mvc().perform(post("/api/release-groups/" + groupId + "/advance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcomes.length()").value(2));
        }
        mvc().perform(get("/api/release-groups/" + groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.releases[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.releases[1].status").value("COMPLETED"));
        mvc().perform(get("/api/release-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        testLog.info("final state: group {} COMPLETED over http", groupId);
    }

    @Test
    void groupErrorsMapToDistinctHttpCodes() throws Exception {
        mvc().perform(get("/api/release-groups/grp-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));

        mvc().perform(post("/api/release-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody("web-dup", "web-x", "web-x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_DUPLICATE_SERVICE"));

        mvc().perform(post("/api/release-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupKey\":\"web-empty\",\"members\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROUP_REQUEST"));
        testLog.info("final state: group error codes map to 404/409/400");
    }
}
