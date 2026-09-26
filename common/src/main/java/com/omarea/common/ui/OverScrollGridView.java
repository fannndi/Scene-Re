package com.omarea.common.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridView;

public class OverScrollGridView extends GridView {
    private int mMaxOverScrollY = 400;// default 200

    public OverScrollGridView(Context context) {
        super(context);
    }

    public OverScrollGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public OverScrollGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    /**
     * Sets the maximum over-scroll distance
     *
     * @param maxOverScrollY
     */
    public void setMaxOverScrollY(int maxOverScrollY) {
        this.mMaxOverScrollY = maxOverScrollY;
    }

    /**
     * @param deltaX         additional horizontal scroll distance
     * @param deltaY         additional vertical scroll distance; negative: top edge reached, positive: bottom edge reached
     * @param scrollX        horizontal scroll distance
     * @param scrollY        vertical scroll distance
     * @param scrollRangeX
     * @param scrollRangeY
     * @param maxOverScrollX maximum horizontal over-scroll distance
     * @param maxOverScrollY maximum vertical over-scroll distance
     * @param isTouchEvent   true: finger drag, false: fling (inertial scroll);
     * @return
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, mMaxOverScrollY, isTouchEvent);
    }
}
