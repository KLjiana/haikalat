package com.kaleblangley.haikalat.subsystems.animation;

import java.util.List;

final class MarkerSynchronizer {
    private MarkerSynchronizer() {
    }

    static float mapTime(AnimationClip source, float sourceTime,
                         AnimationClip destination) {
        List<AnimationMarker> sourceMarkers = source.markers();
        List<AnimationMarker> destinationMarkers = destination.markers();
        if (sourceMarkers.size() < 2 || destinationMarkers.size() < 2
                || source.durationSeconds() <= 0.0f
                || destination.durationSeconds() <= 0.0f) {
            return -1.0f;
        }
        int sourceStart = intervalStart(sourceMarkers, sourceTime);
        int sourceEnd = (sourceStart + 1) % sourceMarkers.size();
        AnimationMarker start = sourceMarkers.get(sourceStart);
        AnimationMarker end = sourceMarkers.get(sourceEnd);
        float sourceSpan = wrappedSpan(start.timeSeconds(), end.timeSeconds(),
                source.durationSeconds());
        if (sourceSpan <= 0.0f) return -1.0f;
        float sourceOffset = wrappedSpan(start.timeSeconds(), sourceTime,
                source.durationSeconds());
        float phase = Math.max(0.0f, Math.min(1.0f, sourceOffset / sourceSpan));

        for (int index = 0; index < destinationMarkers.size(); index++) {
            int next = (index + 1) % destinationMarkers.size();
            AnimationMarker destinationStart = destinationMarkers.get(index);
            AnimationMarker destinationEnd = destinationMarkers.get(next);
            if (!destinationStart.name().equals(start.name())
                    || !destinationEnd.name().equals(end.name())) continue;
            float destinationSpan = wrappedSpan(destinationStart.timeSeconds(),
                    destinationEnd.timeSeconds(), destination.durationSeconds());
            float mapped = destinationStart.timeSeconds() + destinationSpan * phase;
            return mapped >= destination.durationSeconds()
                    ? mapped - destination.durationSeconds() : mapped;
        }
        return -1.0f;
    }

    private static int intervalStart(List<AnimationMarker> markers, float time) {
        int result = markers.size() - 1;
        for (int index = 0; index < markers.size(); index++) {
            if (markers.get(index).timeSeconds() <= time) result = index;
            else break;
        }
        return result;
    }

    private static float wrappedSpan(float start, float end, float duration) {
        float span = end - start;
        return span < 0.0f ? span + duration : span;
    }
}
