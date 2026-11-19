package com.shumtugle.mirage;

/**
 * The rubber bent: what a matrix cannot carry.
 *
 * Two things bend it. The lens, which bows straight lines out from the
 * middle or in toward it, is taken back by the division model of radial
 * distortion: a point at distance r from the middle, counted in half
 * diagonals, goes to the same direction at r / (1 + k r^2)
 * (Fitzgibbon, Simultaneous Linear Estimation of Multiple View Geometry
 * and Lens Distortion, 2001). A negative k pushes the corners out and
 * straightens a barrel; a positive one pulls them in and straightens a
 * pincushion. The hand, which pushes, swells, pinches and twists the rubber
 * under a finger, is kept as a displacement at every point of a coarse grid
 * laid over the picture, and between the points it is bilinear.
 *
 * So a point of the picture goes first through the lens, then by its share
 * of the grid's displacement, and only then through the pose. Everything
 * is counted in the picture's own pixels, upright, before any turn.
 *
 * Plain arithmetic, with nothing of the platform in it.
 */
final class Warp {

    /** The grid's cells along the picture's long side. */
    static final int CELLS = 96;
    /** The lens taken back no further than this either way. */
    static final float MOST_LENS = 0.3f;

    /** The lens, as the division model's k. */
    float lens;
    /** The grid, its cells across and down, and each point's displacement as fractions of width and height. */
    int across;
    int down;
    float[] dx;
    float[] dy;

    Warp() {
    }

    /** A grid for a picture of this proportion, nothing displaced yet. */
    void grid(double w, double h) {
        if (dx != null) {
            return;
        }
        if (w >= h) {
            across = CELLS;
            down = Math.max(8, (int) Math.round(CELLS * h / w));
        } else {
            down = CELLS;
            across = Math.max(8, (int) Math.round(CELLS * w / h));
        }
        dx = new float[(across + 1) * (down + 1)];
        dy = new float[(across + 1) * (down + 1)];
    }

    Warp copy() {
        Warp c = new Warp();
        c.lens = lens;
        c.across = across;
        c.down = down;
        c.dx = dx == null ? null : dx.clone();
        c.dy = dy == null ? null : dy.clone();
        return c;
    }

    /** Whether the warp bends anything at all. */
    boolean plain() {
        if (lens != 0f) {
            return false;
        }
        if (dx != null) {
            for (int i = 0; i < dx.length; i++) {
                if (dx[i] != 0f || dy[i] != 0f) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the hand has bent the grid anywhere. */
    boolean handled() {
        if (dx == null) {
            return false;
        }
        for (int i = 0; i < dx.length; i++) {
            if (dx[i] != 0f || dy[i] != 0f) {
                return true;
            }
        }
        return false;
    }

    /** Where a point of a picture w by h goes through the lens alone. */
    double[] throughLens(double x, double y, double w, double h) {
        if (lens == 0f) {
            return new double[] {x, y};
        }
        double cx = w / 2;
        double cy = h / 2;
        double half = Math.hypot(w, h) / 2;
        double ux = (x - cx) / half;
        double uy = (y - cy) / half;
        double k = 1 + lens * (ux * ux + uy * uy);
        return new double[] {cx + ux / k * half, cy + uy / k * half};
    }

    /**
     * Where a point that the lens put at (x, y) came from: the division model
     * solved back, r_d from r_u by the smaller root of k r_u r_d^2 - r_d + r_u.
     */
    double[] backThroughLens(double x, double y, double w, double h) {
        if (lens == 0f) {
            return new double[] {x, y};
        }
        double cx = w / 2;
        double cy = h / 2;
        double half = Math.hypot(w, h) / 2;
        double ux = (x - cx) / half;
        double uy = (y - cy) / half;
        double ru = Math.hypot(ux, uy);
        if (ru < 1e-12) {
            return new double[] {x, y};
        }
        double disc = 1 - 4 * lens * ru * ru;
        if (disc < 0) {
            disc = 0;
        }
        double rd = (1 - Math.sqrt(disc)) / (2 * lens * ru);
        double s = rd / ru;
        return new double[] {cx + ux * s * half, cy + uy * s * half};
    }

    /** The hand's displacement at a point of a picture w by h, in pixels, bilinear between grid points. */
    double[] shift(double x, double y, double w, double h) {
        if (dx == null) {
            return new double[] {0, 0};
        }
        double gx = Math.max(0, Math.min(across, x / w * across));
        double gy = Math.max(0, Math.min(down, y / h * down));
        int i = Math.min(across - 1, (int) gx);
        int j = Math.min(down - 1, (int) gy);
        double fx = gx - i;
        double fy = gy - j;
        int row = across + 1;
        int a = j * row + i;
        int b = a + 1;
        int c = a + row;
        int d = c + 1;
        double sx = (dx[a] * (1 - fx) + dx[b] * fx) * (1 - fy) + (dx[c] * (1 - fx) + dx[d] * fx) * fy;
        double sy = (dy[a] * (1 - fx) + dy[b] * fx) * (1 - fy) + (dy[c] * (1 - fx) + dy[d] * fx) * fy;
        return new double[] {sx * w, sy * h};
    }

    /** Where a point of a picture w by h goes: through the lens, then by the hand. */
    double[] map(double x, double y, double w, double h) {
        double[] p = throughLens(x, y, w, h);
        double[] s = shift(x, y, w, h);
        return new double[] {p[0] + s[0], p[1] + s[1]};
    }

    /**
     * A mesh of points evenly spread over a region of the picture, a cols by
     * rows cells, each carried to where the warp puts it: what the platform
     * draws a bitmap through.
     */
    float[] mesh(double left, double top, double right, double bottom, int cols, int rows,
                 double w, double h) {
        float[] out = new float[(cols + 1) * (rows + 1) * 2];
        int n = 0;
        for (int j = 0; j <= rows; j++) {
            double y = top + (bottom - top) * j / rows;
            for (int i = 0; i <= cols; i++) {
                double x = left + (right - left) * i / cols;
                double[] p = map(x, y, w, h);
                out[n++] = (float) p[0];
                out[n++] = (float) p[1];
            }
        }
        return out;
    }

    /** The picture's edge carried through the warp, as many points along each side as the grid has. */
    double[][] outline(double w, double h) {
        grid(w, h);
        int per = Math.max(across, down);
        double[][] out = new double[4 * per][];
        int n = 0;
        for (int i = 0; i < per; i++) {
            out[n++] = map(w * i / per, 0, w, h);
        }
        for (int i = 0; i < per; i++) {
            out[n++] = map(w, h * i / per, w, h);
        }
        for (int i = 0; i < per; i++) {
            out[n++] = map(w - w * i / per, h, w, h);
        }
        for (int i = 0; i < per; i++) {
            out[n++] = map(0, h - h * i / per, w, h);
        }
        return out;
    }

    // ------------------------------------------------------------ the hand

    static final int PUSH = 0;
    static final int SWELL = 1;
    static final int PINCH = 2;
    static final int TWIST = 3;
    static final int RESTORE = 4;

    /** How much one touch of a brush that acts in place does, at its middle. */
    private static final double GROW = 0.035;
    private static final double TURN = 0.05;
    private static final double BACK = 0.12;

    /**
     * How much of a brush reaches a point at distance t of its radius: all of
     * it at the middle, none at the rim, falling off smoothly with no edge,
     * (1 - t^2)^2.
     */
    static double reach(double t) {
        if (t >= 1) {
            return 0;
        }
        double u = 1 - t * t;
        return u * u;
    }

    /**
     * One touch of a brush of radius r about (cx, cy), in pixels of where the
     * picture now lies, before the pose. A push carries what lies under the
     * brush by (mx, my); the others act in place. Every grid point is moved
     * by where it lies now, so the brush works on what the person sees.
     */
    double brush(int kind, double cx, double cy, double r, double mx, double my, double w, double h) {
        grid(w, h);
        int row = across + 1;
        int n = row * (down + 1);
        double[] px = new double[n];
        double[] py = new double[n];
        double[] ox = new double[n];
        double[] oy = new double[n];
        boolean any = false;
        for (int j = 0; j <= down; j++) {
            double y = h * j / down;
            for (int i = 0; i <= across; i++) {
                double x = w * i / across;
                int at = j * row + i;
                double[] p = throughLens(x, y, w, h);
                px[at] = p[0] + dx[at] * w;
                py[at] = p[1] + dy[at] * h;
                double f = reach(Math.hypot(px[at] - cx, py[at] - cy) / r);
                if (f <= 0) {
                    continue;
                }
                any = true;
                double rx = px[at] - cx;
                double ry = py[at] - cy;
                switch (kind) {
                    case PUSH:
                        ox[at] = mx * f;
                        oy[at] = my * f;
                        break;
                    case SWELL:
                        ox[at] = rx * GROW * f;
                        oy[at] = ry * GROW * f;
                        break;
                    case PINCH:
                        ox[at] = -rx * GROW * f;
                        oy[at] = -ry * GROW * f;
                        break;
                    case TWIST: {
                        double a = TURN * f;
                        double c = Math.cos(a);
                        double s = Math.sin(a);
                        ox[at] = rx * c - ry * s - rx;
                        oy[at] = rx * s + ry * c - ry;
                        break;
                    }
                    default:
                        ox[at] = -dx[at] * w * BACK * f;
                        oy[at] = -dy[at] * h * BACK * f;
                        break;
                }
            }
        }
        if (!any) {
            return 0;
        }
        // A touch that would fold the rubber over itself, a cell turned inside
        // out, is taken in smaller parts, and not at all if even a small part
        // folds: the rubber may be stretched and squeezed, never torn.
        // Bringing back only ever unfolds, so it is never held back.
        double share = 1;
        if (kind != RESTORE) {
            for (int tries = 0; tries < 5 && folds(px, py, ox, oy, share, w, h); tries++) {
                share /= 2;
            }
            if (folds(px, py, ox, oy, share, w, h)) {
                return 0;
            }
        }
        for (int at = 0; at < n; at++) {
            if (ox[at] != 0 || oy[at] != 0) {
                dx[at] += (float) (ox[at] * share / w);
                dy[at] += (float) (oy[at] * share / h);
            }
        }
        return share;
    }

    /** The least a cell may be squeezed to, as a share of its first area. */
    private static final double THINNEST = 0.08;

    /**
     * Whether moving the grid points by a share of their offsets would turn
     * any cell touched inside out or squeeze it to almost nothing: both
     * triangles of every touched cell must keep their turn and some area.
     */
    private boolean folds(double[] px, double[] py, double[] ox, double[] oy, double share,
                          double w, double h) {
        int row = across + 1;
        double least = THINNEST * (w / across) * (h / down) / 2;
        for (int j = 0; j < down; j++) {
            for (int i = 0; i < across; i++) {
                int a = j * row + i;
                int b = a + 1;
                int c = a + row;
                int d = c + 1;
                if (ox[a] == 0 && oy[a] == 0 && ox[b] == 0 && oy[b] == 0
                    && ox[c] == 0 && oy[c] == 0 && ox[d] == 0 && oy[d] == 0) {
                    continue;
                }
                double ax = px[a] + ox[a] * share;
                double ay = py[a] + oy[a] * share;
                double bx = px[b] + ox[b] * share;
                double by = py[b] + oy[b] * share;
                double cx = px[c] + ox[c] * share;
                double cy = py[c] + oy[c] * share;
                double ddx = px[d] + ox[d] * share;
                double ddy = py[d] + oy[d] * share;
                // a b / c d: the triangles a-b-d and a-d-c, both turning the same way as at rest.
                double t1 = ((bx - ax) * (ddy - ay) - (by - ay) * (ddx - ax)) / 2;
                double t2 = ((ddx - ax) * (cy - ay) - (ddy - ay) * (cx - ax)) / 2;
                if (t1 < least || t2 < least) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The same touch for a single point lying on the rubber, a caption's
     * place or a balloon's tip, so that what is laid on the picture goes
     * where the picture under it goes.
     */
    static double[] brushPoint(int kind, double px, double py, double cx, double cy, double r,
                               double mx, double my, double share) {
        double f = reach(Math.hypot(px - cx, py - cy) / r) * share;
        if (f <= 0) {
            return new double[] {px, py};
        }
        switch (kind) {
            case PUSH:
                return new double[] {px + mx * f, py + my * f};
            case SWELL:
                return new double[] {px + (px - cx) * GROW * f, py + (py - cy) * GROW * f};
            case PINCH:
                return new double[] {px - (px - cx) * GROW * f, py - (py - cy) * GROW * f};
            case TWIST: {
                double a = TURN * f;
                double c = Math.cos(a);
                double s = Math.sin(a);
                double rx = px - cx;
                double ry = py - cy;
                return new double[] {cx + rx * c - ry * s, cy + rx * s + ry * c};
            }
            default:
                return new double[] {px, py};
        }
    }

    /** Everything the hand did undone; the lens stays. */
    void smooth() {
        if (dx != null) {
            java.util.Arrays.fill(dx, 0f);
            java.util.Arrays.fill(dy, 0f);
        }
    }
}
