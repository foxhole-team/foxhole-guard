package com.foxhole.guard.ui.cli.components;

import android.annotation.SuppressLint;
import androidx.compose.animation.core.FiniteAnimationSpec;
import androidx.compose.material3.MotionScheme;
import androidx.compose.material3.SheetState;

@SuppressLint("UnsafeOptInUsageError")
final class MaterialSheetMotion {
    private MaterialSheetMotion() {
    }

    static FiniteAnimationSpec<Float> slowSpatialSpec() {
        return MotionScheme.Companion.standard$material3().slowSpatialSpec();
    }

    static void applySlowSpatialSpec(
            SheetState sheetState,
            FiniteAnimationSpec<Float> motionSpec
    ) {
        sheetState.setShowMotionSpec$material3(motionSpec);
        sheetState.setHideMotionSpec$material3(motionSpec);
        sheetState.setAnchoredDraggableMotionSpec$material3(motionSpec);
    }
}
