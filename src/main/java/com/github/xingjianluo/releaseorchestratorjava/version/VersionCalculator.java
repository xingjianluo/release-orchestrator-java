package com.github.xingjianluo.releaseorchestratorjava.version;

/** 由输入确定性派生内容哈希、版本号与制品字节内容。 */
public interface VersionCalculator {

    String sha256Hex(String input);

    String calculateVersion(String serviceName, String commit, String sourceContent);

    byte[] buildArtifactBytes(String serviceName, String commit, String sourceContent, String version);
}
