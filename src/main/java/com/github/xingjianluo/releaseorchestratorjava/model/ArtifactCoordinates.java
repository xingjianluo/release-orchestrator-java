package com.github.xingjianluo.releaseorchestratorjava.model;

/**
 * 制品坐标：uri 为本地文件相对路径，sha256 为制品字节内容的完整 SHA-256。
 * 相同 sourceContent 必然产生相同坐标。
 */
public record ArtifactCoordinates(String uri, String sha256, long size) {
}
