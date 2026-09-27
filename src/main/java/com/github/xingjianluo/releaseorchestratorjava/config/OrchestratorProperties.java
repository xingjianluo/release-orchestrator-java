package com.github.xingjianluo.releaseorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 关键配置项（application.properties 可调）。 */
@ConfigurationProperties(prefix = "release.orchestrator")
public class OrchestratorProperties {

    /** 本地状态与制品根目录。 */
    private String dataDir = "target/release-data";

    /** 同一发布推进操作的互斥锁等待毫秒。 */
    private long lockTimeoutMillis = 5000;

    /** 是否在启动时恢复中断状态（RUNNING 阶段）。 */
    private boolean recoverOnStartup = true;

    /** 发布组批量操作（推进/回滚）时成员级并行度上限。 */
    private int groupParallelism = 8;

    /**
     * 实时解析数据目录。不要在 Bean 构造函数中缓存结果：
     * ConfigurationProperties 的绑定发生在 Bean 构造之后，
     * 构造时读取会拿到默认值。
     */
    public Path dataPath() {
        return Paths.get(dataDir);
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public long getLockTimeoutMillis() { return lockTimeoutMillis; }
    public void setLockTimeoutMillis(long lockTimeoutMillis) { this.lockTimeoutMillis = lockTimeoutMillis; }
    public boolean isRecoverOnStartup() { return recoverOnStartup; }
    public void setRecoverOnStartup(boolean recoverOnStartup) { this.recoverOnStartup = recoverOnStartup; }
    public int getGroupParallelism() { return groupParallelism; }
    public void setGroupParallelism(int groupParallelism) { this.groupParallelism = groupParallelism; }
}
