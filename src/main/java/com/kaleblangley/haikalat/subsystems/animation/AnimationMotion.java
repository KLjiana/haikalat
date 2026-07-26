package com.kaleblangley.haikalat.subsystems.animation;

/** 可由 Animation Graph 求值的不可变 motion definition。 */
public sealed interface AnimationMotion permits ClipMotion, BlendTree1D, BlendTree2D {
    Skeleton skeleton();

    float durationSeconds();
}
