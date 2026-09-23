package com.github.xingjianluo.releaseorchestratorjava.error;

/** 骨架：ArtifactIntegrityException。 */
public class ArtifactIntegrityException extends ReleaseException {
    public ArtifactIntegrityException(String message) {
        super(ErrorCode.ARTIFACT_INTEGRITY_FAILED, message);
    }
}
