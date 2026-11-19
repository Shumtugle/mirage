package com.shumtugle.mirage;

/**
 * How the rubber lies: every turn and lean the picture has been given, and
 * the frame that is left once they are given.
 *
 * The automatic answer, the angle and the two leans are all one thing, a
 * turn of an imagined camera; they are multiplied into one rotation, and the
 * picture is redrawn through the homography K\u00b7R\u00b7K\u207b\u00b9. A quarter turn and a
 * mirror are taken after it, on the frame itself. Everything together is a
 * single 3\u00d73 matrix from the upright picture to the frame, and everything on
 * the rubber, words included, is drawn through that matrix: nothing can lean
 * one way while the picture leans another.
 *
 * A picture that leans no longer fills a rectangle, so the frame is the
 * largest rectangle of the picture's own shape that lies wholly on the leaned
 * picture, centred where the picture's middle went. It is fitted once, for
 * all the turns together; fitted after each in turn, every one would eat
 * into the corners again.
 *
 * Plain arithmetic, with nothing of the platform in it.
 */
final class Pose {

    /** The camera when the picture says nothing: a phone's usual lens, in 35 mm terms. */
    static final double USUAL_LENS = 26.0;
    /** The diagonal of the 35 mm frame, against which a lens is told in those terms. */
    private static final double FULL_FRAME = 43.27;

    /** The automatic turn, row by row; the identity when none is taken. */
    double[] auto = Upright.identity();
    /** Turn in the plane, in degrees, clockwise on the screen. */
    float angle;
    /** Lean up and down, and side to side, in degrees of camera tilt. */
    float leanV;
    float leanH;
    /** Quarter turns clockwise, 0 to 3, and a mirror across the upright axis. */
    int quarter;
    boolean mirror;
    /** The lens, as a 35 mm equivalent. */
    double lens = USUAL_LENS;

    /** The matrix from the upright picture to the frame, and the frame's size. */
    static final class Laid {
        final double[] m;
        final double w;
        final double h;

        Laid(double[] m, double w, double h) {
            this.m = m;
            this.w = w;
            this.h = h;
        }
    }

    Pose copy() {
        Pose p = new Pose();
        p.auto = auto.clone();
        p.angle = angle;
        p.leanV = leanV;
        p.leanH = leanH;
        p.quarter = quarter;
        p.mirror = mirror;
        p.lens = lens;
        return p;
    }

    boolean plain() {
        return angle == 0f && leanV == 0f && leanH == 0f && quarter == 0 && !mirror
            && Upright.angleOf(auto) < 1e-9;
    }

    /** The focal length in pixels of a picture w by h. */
    double focal(double w, double h) {
        return lens / FULL_FRAME * Math.hypot(w, h);
    }

    /** The camera's turn: the hand's angle and leans after the automatic answer. */
    double[] rotation() {
        double[] r = auto;
        if (leanH != 0f) {
            r = Upright.mul(Upright.rotY(Math.toRadians(leanH)), r);
        }
        if (leanV != 0f) {
            r = Upright.mul(Upright.rotX(Math.toRadians(-leanV)), r);
        }
        if (angle != 0f) {
            r = Upright.mul(Upright.rotZ(Math.toRadians(angle)), r);
        }
        return r;
    }

    /** The homography of the camera's turn for a picture w by h, before the frame is fitted. */
    double[] homography(double w, double h) {
        double f = focal(w, h);
        double[] k = {f, 0, w / 2, 0, f, h / 2, 0, 0, 1};
        double[] ki = {1 / f, 0, -w / (2 * f), 0, 1 / f, -h / (2 * f), 0, 0, 1};
        return Upright.mul(k, Upright.mul(rotation(), ki));
    }

    /** Where a point goes through a homography; null if it goes behind the camera. */
    static double[] apply(double[] m, double x, double y) {
        double z = m[6] * x + m[7] * y + m[8];
        if (z <= 1e-9) {
            return null;
        }
        return new double[] {(m[0] * x + m[1] * y + m[2]) / z, (m[3] * x + m[4] * y + m[5]) / z};
    }

    /**
     * The whole of it for a picture w by h: the matrix from the upright
     * picture to the frame, and the frame's size. Null if the turn is so
     * steep that part of the picture falls behind the camera.
     */
    Laid lay(double w, double h) {
        return lay(w, h, null);
    }

    /**
     * The same for a picture bent by a warp first: the frame is fitted inside
     * the bent edge, carried through the pose point by point, rather than
     * inside four corners.
     */
    Laid lay(double w, double h, Warp warp) {
        if (warp != null && !warp.plain()) {
            return layBent(w, h, warp);
        }
        double[] hm = homography(w, h);
        double[][] quad = new double[4][];
        double[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        for (int i = 0; i < 4; i++) {
            quad[i] = apply(hm, corners[i][0], corners[i][1]);
            if (quad[i] == null) {
                return null;
            }
        }
        double[] mid = apply(hm, w / 2, h / 2);
        if (mid == null || !convex(quad)) {
            return null;
        }
        // The largest frame of the picture's shape, centred on its middle,
        // that lies wholly on the leaned picture: halved until it fits.
        double lo = 0;
        double hi = 4;
        for (int i = 0; i < 40; i++) {
            double s = (lo + hi) / 2;
            if (inside(quad, mid[0], mid[1], w * s / 2, h * s / 2)) {
                lo = s;
            } else {
                hi = s;
            }
        }
        if (lo < 0.05) {
            return null;
        }
        return framed(hm, mid, w, h, lo);
    }

    private Laid layBent(double w, double h, Warp warp) {
        double[] hm = homography(w, h);
        double[][] edge = warp.outline(w, h);
        double[][] poly = new double[edge.length][];
        for (int i = 0; i < edge.length; i++) {
            poly[i] = apply(hm, edge[i][0], edge[i][1]);
            if (poly[i] == null) {
                return null;
            }
        }
        double[] c = warp.map(w / 2, h / 2, w, h);
        double[] mid = apply(hm, c[0], c[1]);
        if (mid == null || !within(poly, mid[0], mid[1])) {
            return null;
        }
        double lo = 0;
        double hi = 4;
        for (int i = 0; i < 40; i++) {
            double s = (lo + hi) / 2;
            if (insidePolygon(poly, mid[0], mid[1], w * s / 2, h * s / 2)) {
                lo = s;
            } else {
                hi = s;
            }
        }
        if (lo < 0.05) {
            return null;
        }
        return framed(hm, mid, w, h, lo);
    }

    /** The frame of a share of the picture's size about a middle, turned and mirrored as asked. */
    private Laid framed(double[] hm, double[] mid, double w, double h, double lo) {
        double fw = w * lo;
        double fh = h * lo;
        double[] shift = {1, 0, -(mid[0] - fw / 2), 0, 1, -(mid[1] - fh / 2), 0, 0, 1};
        double[] m = Upright.mul(shift, hm);
        // The frame is turned and mirrored as a whole after it is cut.
        double ow = fw;
        double oh = fh;
        for (int q = 0; q < ((quarter % 4) + 4) % 4; q++) {
            double[] t = {0, -1, oh, 1, 0, 0, 0, 0, 1};
            m = Upright.mul(t, m);
            double swap = ow;
            ow = oh;
            oh = swap;
        }
        if (mirror) {
            m = Upright.mul(new double[] {-1, 0, ow, 0, 1, 0, 0, 0, 1}, m);
        }
        return new Laid(m, ow, oh);
    }

    private static boolean convex(double[][] q) {
        double sign = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = q[i];
            double[] b = q[(i + 1) % 4];
            double[] c = q[(i + 2) % 4];
            double z = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (Math.abs(z) < 1e-9) {
                return false;
            }
            if (sign == 0) {
                sign = Math.signum(z);
            } else if (Math.signum(z) != sign) {
                return false;
            }
        }
        return true;
    }

    /** Whether a point lies within a polygon, by the crossings of a ray from it. */
    static boolean within(double[][] poly, double x, double y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double[] a = poly[i];
            double[] b = poly[j];
            if ((a[1] > y) != (b[1] > y)
                && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * Whether a rectangle, by its middle and half sides, lies within a
     * polygon that need not be convex: its corners inside, and no side of the
     * polygon crossing a side of the rectangle.
     */
    static boolean insidePolygon(double[][] poly, double cx, double cy, double hw, double hh) {
        double[][] r = {{cx - hw, cy - hh}, {cx + hw, cy - hh}, {cx + hw, cy + hh}, {cx - hw, cy + hh}};
        for (double[] p : r) {
            if (!within(poly, p[0], p[1])) {
                return false;
            }
        }
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            for (int k = 0; k < 4; k++) {
                if (cross(poly[j], poly[i], r[k], r[(k + 1) % 4])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether two segments cross. */
    private static boolean cross(double[] a, double[] b, double[] c, double[] d) {
        double d1 = side(c, d, a);
        double d2 = side(c, d, b);
        double d3 = side(a, b, c);
        double d4 = side(a, b, d);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
            && d1 != 0 && d2 != 0 && d3 != 0 && d4 != 0;
    }

    private static double side(double[] a, double[] b, double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
    }

    /** Whether a rectangle, by its middle and half sides, lies within a convex quadrilateral. */
    private static boolean inside(double[][] q, double cx, double cy, double hw, double hh) {
        double[][] pts = {{cx - hw, cy - hh}, {cx + hw, cy - hh}, {cx + hw, cy + hh}, {cx - hw, cy + hh}};
        double sign = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = q[i];
            double[] b = q[(i + 1) % 4];
            double z = (b[0] - a[0]) * (q[(i + 2) % 4][1] - b[1]) - (b[1] - a[1]) * (q[(i + 2) % 4][0] - b[0]);
            sign = Math.signum(z);
            for (double[] p : pts) {
                double s = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
                if (Math.signum(s) == -sign && Math.abs(s) > 1e-9) {
                    return false;
                }
            }
        }
        return true;
    }
}
