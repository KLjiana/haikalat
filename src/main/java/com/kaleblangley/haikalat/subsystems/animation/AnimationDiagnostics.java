package com.kaleblangley.haikalat.subsystems.animation;

/** 单个 controller update 后可安全导出的不可变诊断快照。 */
public record AnimationDiagnostics(
        long evaluatedStates,
        long evaluatedMotions,
        long sampledClips,
        long sampledChannels,
        long transitions,
        long interruptions,
        long activeLayers,
        long overrideLayers,
        long additiveLayers,
        long events,
        long markers,
        long signals,
        long droppedSignals,
        long syncFallbacks,
        long constraints,
        long constraintIterations,
        float constraintResidual,
        long morphTargets,
        long activeMorphWeights,
        long globalMatrixRecomputes,
        long scratchEstimatedBytes,
        long updateCpuNanos) {

    public static AnimationDiagnostics empty() {
        return new AnimationDiagnostics(0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0.0f, 0, 0, 0, 0, 0);
    }
}
