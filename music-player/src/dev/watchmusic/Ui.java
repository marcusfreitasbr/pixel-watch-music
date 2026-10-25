package dev.watchmusic;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** watchOS-style colours and press feedback, copied from ancs-bridge. */
final class Ui {
    static final int RED = 0xFFFF3B30, GREEN = 0xFF34C759, ORANGE = 0xFFFF9F0A, PINK = 0xFFFF2D55, FILL = 0xFF2C2C2E,
            LABEL = 0xFFFFFFFF, SECONDARY = 0xFF8E8E93;

    private Ui() {}

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static GradientDrawable pill(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(color);
        return d;
    }

    /** Background with a ripple, a press-shrink like watchOS, and a haptic tick on click. */
    static void pressable(View v, GradientDrawable shape, Runnable onClick) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        v.setClickable(true);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false; // let the normal click handling run
        });
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            onClick.run();
        });
    }
}
