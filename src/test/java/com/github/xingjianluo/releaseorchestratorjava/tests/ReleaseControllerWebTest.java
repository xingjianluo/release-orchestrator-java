package com.github.xingjianluo.releaseorchestratorjava.tests;

import com.github.xingjianluo.releaseorchestratorjava.model.Release;
import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseRequest;
import com.github.xingjianluo.releaseorchestratorjava.model.StageName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端：离线启动，断言不同拒绝原因映射为不同 HTTP 状态与稳定错误码。
 */
@AutoConfigureMockMvc
class ReleaseControllerWebTest extends AbstractOrchestratorTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private Release submit(String commit, String content, String fault) {
        return orchestrator.submit(new ReleaseRequest("edge-api", commit, content, null, fault));
    }

    private String body(String commit, String content, String fault) {
        return "{\"serviceName\":\"edge-api\",\"commit\":\"" + commit
                + "\",\"sourceContent\":\"" + content + "\""
                + (fault == null ? "" : ",\"faultInjection\":\"" + fault + "\"") + "}";
    }

    @Test
    void submitReturnsDeterministicReleaseOverHttp() throws Exception {
        mvc().perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("abcdef0123456789abcdef0123456789abcdef01", "http-content", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.releaseId").isNotEmpty());
        testLog.info("final state: HTTP submit ok");
    }

    @Test
    void duplicateSubmitReturns409WithDistinctCode() throws Exception {
        mvc().perform(post("/api/releases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("abcdef0123456789abcdef0123456789abcdef01", "dup-http", null)))
                .andExpect(status().isOk());
        mvc().perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("abcdef0123456789abcdef0123456789abcdef01", "dup-http", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUBMISSION"));
        testLog.info("final state: HTTP 409 DUPLICATE_SUBMISSION");
    }

    @Test
    void invalidRequestReturns400WithValidationCode() throws Exception {
        mvc().perform(post("/api/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"Bad Name\",\"commit\":\"abc\",\"sourceContent\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_NAME"));
    }

    @Test
    void advancingUnknownReleaseReturns404() throws Exception {
        mvc().perform(post("/api/releases/rel-nope/advance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
    }

    @Test
    void fullJourneyOverHttpThenFailedAdvanceReturns409() throws Exception {
        Release r = submit("fedcba9876543210fedcba9876543210fedcba98", "journey", null);
        for (int i = 0; i < StageName.ordered().size(); i++) {
            mvc().perform(post("/api/releases/" + r.getReleaseId() + "/advance"))
                    .andExpect(status().isOk());
        }
        mvc().perform(get("/api/releases/" + r.getReleaseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc().perform(post("/api/releases/" + r.getReleaseId() + "/advance"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_ALREADY_COMPLETED"));
        testLog.info("final state: HTTP journey completed then 409 on re-advance");
    }
}
