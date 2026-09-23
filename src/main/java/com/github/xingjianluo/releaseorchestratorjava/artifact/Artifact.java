package com.github.xingjianluo.releaseorchestratorjava.artifact;

/**
 * 本地模拟制品：内容寻址，标识与校验和均由内容确定性派生。
 * 相同输入构建出的制品字节完全一致，因此可跨次复用。
 */
public class Artifact {

    private final String artifactId;
    private final String checksum;
    private final String contentKey;
    private final String service;
    private final String version;
    private final byte[] content;
    private final boolean reused;

    public Artifact(String artifactId, String checksum, String contentKey,
                    String service, String version, byte[] content, boolean reused) {
        this.artifactId = artifactId;
        this.checksum = checksum;
        this.contentKey = contentKey;
        this.service = service;
        this.version = version;
        this.content = content;
        this.reused = reused;
    }

    public String getArtifactId() { return artifactId; }
    public String getChecksum() { return checksum; }
    public String getContentKey() { return contentKey; }
    public String getService() { return service; }
    public String getVersion() { return version; }
    public byte[] getContent() { return content; }
    public int getSize() { return content == null ? 0 : content.length; }
    public boolean isReused() { return reused; }
}
