package io.github.maharjanronish.jumper.core;

/** Minimal mutable 3-component vector (pure Java, no Android dependencies). */
public final class Vec3 {
    public float x, y, z;

    public Vec3() { this(0, 0, 0); }
    public Vec3(float x, float y, float z) { set(x, y, z); }

    public Vec3 set(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }
    public Vec3 set(Vec3 o) { x = o.x; y = o.y; z = o.z; return this; }

    @Override public String toString() { return "(" + x + ", " + y + ", " + z + ")"; }
}
