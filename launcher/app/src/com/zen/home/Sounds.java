package com.zen.home;

import android.content.Context;

/**
 * Focus movement and clicks stay silent. The methods remain so the rest of the
 * launcher can call them without playing a tick, a select click, or a bump, and
 * without holding the HDMI audio link open.
 */
final class Sounds {

    Sounds(Context ctx) { }

    void tick() { }
    void select() { }
    void bump() { }

    void wake() { }
    void sleep() { }
    void release() { }
}
