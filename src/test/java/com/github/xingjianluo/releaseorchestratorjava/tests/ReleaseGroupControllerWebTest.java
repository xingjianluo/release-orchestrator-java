package com.github.xingjianluo.releaseorchestratorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 发布组 REST 端到端：提交、推进、回滚、查询，以及 400/404/409 错误码区分。 */
@AutoConfigureMockMvc
class ReleaseGroupControllerWebTest extends AbstractOrchestratorTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private String healthyGroup(String key, String a, String b) {
        return "{\"idempotencyKey\":\"" + key + "\",\"members\":["
                + "{\"serviceName\":\"billing-api\",\"commit\":\"" + a
                + "\",\"sourceContent\":\"c1\",\"faultInjection\":null},"
                + "{\"serviceName\":\"edge-api\",\"commit\":\"" + b
                + "\",\"sourceContent\":\"c2\",\"faultInjection\":null}]}";
    }

    @Test
    void groupJourneyOverHttp() throws Exception {
        mvc().perform(post("/api/release-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(healthyGroup("web-1",
                                "0123456789abcdef0123456789abcdef01234567",
                                "fedcba9876543210fedcba9876543210fedcba98")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("grp-web-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.members.length()").value(2));

        for (int i = 0; i < 4; i++) {
            mvc().perform(post("/api/release-groups/grp-web-1/advance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }
        mvc().perform(get("/api/release-groups/grp-web-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc().perform(get("/api/release-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateGroupIs409WithDistinctCode() throws Exception {
        String body = healthyGroup("web-dup",
                "0123456789abcdef0123456789abcdef01234567",
                "fedcba9876543210fedcba9876543210fedcba98");
        mvc().perform(post("/api/release-groups").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc().perform(post("/api/release-groups").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUBMISSION"));
    }

    @Test
    void invalidMemberServiceNameIs400() throws Exception {
        String body = "{\"idempotencyKey\":\"bad-1\",\"members\":["
                + "{\"serviceName\":\"Bad_Service\",\"commit\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"sourceContent\":\"x\"}]}";
        mvc().perform(post("/api/release-groups").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_NAME"));
    }

    @Test
    void emptyGroupIs400() throws Exception {
        mvc().perform(post("/api/release-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"empty\",\"members\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROUP"));
    }

    @Test
    void duplicateServiceWithinGroupIs409() throws Exception {
        String body = "{\"idempotencyKey\":\"dup-svc\",\"members\":["
                + "{\"serviceName\":\"billing-api\",\"commit\":\"0123456789abcdef0123456789abcdef01234567\",\"sourceContent\":\"a\"},"
                + "{\"serviceName\":\"billing-api\",\"commit\":\"0123456789abcdef0123456789abcdef01234567\",\"sourceContent\":\"b\"}]}";
        mvc().perform(post("/api/release-groups").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SERVICE_IN_GROUP"));
    }

    @Test
    void unknownGroupIs404() throws Exception {
        mvc().perform(post("/api/release-groups/grp-missing/advance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    }
}
