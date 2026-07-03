package com.kaleblangley.haikalat.demo.async;

import org.joml.Matrix4f;

import java.util.List;

record FrameState(Matrix4f view, List<Matrix4f> transforms) {}
