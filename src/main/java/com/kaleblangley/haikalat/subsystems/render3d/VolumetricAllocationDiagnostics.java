package com.kaleblangley.haikalat.subsystems.render3d;

/**
 * Conservative allocation estimates, not measured driver VRAM. Query on the pipeline's owner thread.
 * Candidate peak includes the active candidate and all fog generations awaiting retirement;
 * it may exceed physical allocation because compatible graph attachments can be reused.
 */
public record VolumetricAllocationDiagnostics(long activePrivateBytes, long activeColorUpperBoundBytes,
                                              long retiringUpperBoundBytes, long lastCandidatePeakBytes,
                                              int retiringGenerations) { }
