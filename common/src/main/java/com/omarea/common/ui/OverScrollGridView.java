package com.omarea.common.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridView;

public class OverScrollGridView extends GridView {
    private int mMaxOverScrollY = 400;// Default 200

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
     * Set the maximum overscroll distance
     *
     * @param maxOverScrollY
     */
    public void setMaxOverScrollY(int maxOverScrollY) {
        this.mMaxOverScrollY = maxOverScrollY;
    }

    /**
     * @param deltaX         additional scroll distance on the x axis
     * @param deltaY         additional scroll distance on the y axis (negative: top edge, positive: bottom edge)
     * @param scrollX        scroll distance on the x axis
     * @param scrollY        scroll distance on the y axis
     * @param scrollRangeX
     * @param scrollRangeY
     * @param maxOverScrollX maximum overscroll distance on the x axis
     * @param maxOverScrollY maximum overscroll distance on the y axis
     * @param isTouchEvent   true for finger drag, false for fling inertia;
     * @return
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, mMaxOverScrollY, isTouchEvent);
    }
}
