package com.kaleblangley.haikalat.subsystems.render3d;

/** Completed-frame metadata; querying this value does not synchronize with the GPU. */
public record VolumetricFogDiagnostics(boolean available,long generationId,long frameSequence,
        int columnsX,int columnsY,int depthSlices,long ownedBytes,long residentBytes,
        boolean historyValid,int dirtyDomains,String rejectionReason,boolean shadowRefit,
        long successfulIndex,float samplePhase,float samplePhaseX,float samplePhaseY,float simulationTimeSeconds) {
    public static final VolumetricFogDiagnostics UNAVAILABLE = new VolumetricFogDiagnostics(
            false,-1,-1,0,0,0,0,0,false,0,"UNAVAILABLE",false,0,0,0,0,0);
    public enum Field { MEDIUM, SOURCE, SCATTERING_TRANSMISSION, HISTORY_REJECTION, REACTIVE_PREFIX }
    /** Optional shader counters. Reading these counters is an explicit GPU synchronization. */
    public record Counters(long frameSequence,long voxels,long overflowVoxels,long outsideClusterVoxels,
            long candidateVisits,long maximumCandidates,long lightEvaluations,long shadowWithoutSlot,
            long nonfiniteMedium,long limitedMedium,long limitedSource,long limitedIntegral,
            long historySamples,long historyAccepted,java.util.List<Long> candidateHistogram,
            java.util.List<Long> historyRejections) {
        public Counters {
            candidateHistogram=java.util.List.copyOf(candidateHistogram);
            historyRejections=java.util.List.copyOf(historyRejections);
        }
    }
}
