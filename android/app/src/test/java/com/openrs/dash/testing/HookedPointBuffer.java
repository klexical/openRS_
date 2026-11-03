package com.openrs.dash.testing;

import com.openrs.dash.data.DrivePointEntity;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test seam for the recorder's point buffer (#238 review finding 2): the same ArrayList the recorder uses, with hooks
 * at the two places where another thread can interleave — after a take has copied the buffer (copy constructor →
 * {@link #toArray()}) and before it clears it, and after an add. Installed into the run by reflection; production code
 * is unchanged. Java because Kotlin hides {@code toArray} on collections.
 */
public final class HookedPointBuffer extends ArrayList<DrivePointEntity> {
    /** Fires once, on the next take's thread, after the copy and before the clear. */
    public final AtomicBoolean armTake = new AtomicBoolean(false);
    public volatile Runnable onTake;
    public volatile Runnable onAdd;

    public HookedPointBuffer() { super(60); }

    @Override public Object[] toArray() {
        Object[] copy = super.toArray();
        Runnable hook = onTake;
        if (hook != null && armTake.getAndSet(false)) hook.run();
        return copy;
    }

    @Override public boolean add(DrivePointEntity e) {
        boolean r = super.add(e);
        Runnable hook = onAdd;
        if (hook != null) hook.run();
        return r;
    }
}
