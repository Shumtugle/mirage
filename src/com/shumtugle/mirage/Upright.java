package com.shumtugle.mirage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What the picture's own lines say about how the camera was held.
 *
 * Plain arithmetic on a small grey reading of the picture, with nothing of
 * the platform in it, so it can be weighed on any computer against pictures
 * whose answer is known.
 *
 * Three answers, each deeper than the one before, and each may be missing:
 * a level, which only turns the picture in its plane; an upright, which
 * also stands the verticals up as a camera held level would have seen them;
 * and a square-on view, which in addition lays the horizontals flat, as if
 * the camera had faced the wall, the page or the sign.
 *
 * Every answer is a turn of the camera, a rotation R, and the picture it
 * gives is K\u00b7R\u00b7K\u207b\u00b9 applied to the old one, where K is the camera: the focal
 * length in pixels and the middle of the frame (Hartley, Zisserman,
 * Multiple View Geometry in Computer Vision, the homography of a pure
 * rotation). Working in coordinates divided by the focal length, a vanishing
 * point written as (x, y, 1) is already the direction in space its lines run
 * along, and the turn is read straight off it.
 */
final class Upright {

    /** A segment of a straight edge, in pixels of the small reading, with its length. */
    static final class Segment {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final double length;

        Segment(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.length = Math.hypot(x2 - x1, y2 - y1);
        }

        /** The direction of the segment in degrees, from 0 to 180, 90 being upright. */
        double angle() {
            double a = Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
            if (a < 0) {
                a += 180;
            }
            return a >= 180 ? a - 180 : a;
        }
    }

    /** The three answers as rotations, row by row; any of them may be null. */
    static final class Answer {
        double[] level;
        double[] upright;
        double[] square;
        int segments;
    }

    /** The most a found turn may tilt the camera; past it the lines are more likely wrong than the hand. */
    static final double MOST = Math.toRadians(35);
    /** How close to upright or to level a segment must stand to vote for that family. */
    private static final double FAMILY = 30;
    /** How far a segment may point away from a vanishing point and still be counted for it. */
    private static final double AGREE = Math.sin(Math.toRadians(1.5));
    /**
     * A family must bring at least this share of the picture's long side in
     * length: a page has only two upright edges, and both must be enough.
     */
    private static final double SUPPORT = 0.6;

    private Upright() {
    }

    /**
     * Reads a grey picture w by h, its values from 0 to 255, taken through a
     * lens whose focal length in the same pixels is given.
     */
    static Answer read(float[] grey, int w, int h, double focal) {
        Answer a = new Answer();
        List<Segment> all = segments(grey, w, h);
        a.segments = all.size();
        double cx = w / 2.0;
        double cy = h / 2.0;
        int longSide = Math.max(w, h);

        List<Segment> upright = new ArrayList<Segment>();
        List<Segment> flat = new ArrayList<Segment>();
        double upLength = 0;
        double flatLength = 0;
        for (Segment s : all) {
            double ang = s.angle();
            if (Math.abs(ang - 90) < FAMILY) {
                upright.add(s);
                upLength += s.length;
            } else if (ang < FAMILY || ang > 180 - FAMILY) {
                flat.add(s);
                flatLength += s.length;
            }
        }

        a.level = level(all);

        double[] dv = upLength > SUPPORT * longSide
            ? vanish(upright, cx, cy, focal) : null;
        double[] dh = flatLength > SUPPORT * longSide
            ? vanish(flat, cx, cy, focal) : null;

        if (dv != null) {
            if (dv[1] < 0) {
                dv = scale(dv, -1);
            }
            double[] r = toward(dv, new double[] {0, 1, 0});
            if (angleOf(r) <= MOST) {
                a.upright = r;
            }
            if (dh != null) {
                if (dh[0] < 0) {
                    dh = scale(dh, -1);
                }
                double[] r2 = squareOn(dv, dh);
                if (r2 != null && angleOf(r2) <= MOST) {
                    a.square = r2;
                }
            }
        }
        return a;
    }

    // ------------------------------------------------------------ the level

    /**
     * The turn in the plane that lays the near-level and near-upright edges
     * on the axes: the median of their leanings, weighed by length, so a few
     * slanting edges cannot pull it. Nothing to say if the edges disagree
     * by more than they agree, or if the picture is already level.
     */
    static double[] level(List<Segment> all) {
        List<double[]> leans = new ArrayList<double[]>();
        double total = 0;
        for (Segment s : all) {
            double ang = s.angle();
            double d;
            if (Math.abs(ang - 90) < 20) {
                d = ang - 90;
            } else if (ang < 20) {
                d = ang;
            } else if (ang > 160) {
                d = ang - 180;
            } else {
                continue;
            }
            leans.add(new double[] {d, s.length});
            total += s.length;
        }
        if (leans.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(leans, new java.util.Comparator<double[]>() {
            public int compare(double[] p, double[] q) {
                return Double.compare(p[0], q[0]);
            }
        });
        double half = total / 2;
        double acc = 0;
        double median = 0;
        for (double[] l : leans) {
            acc += l[1];
            if (acc >= half) {
                median = l[0];
                break;
            }
        }
        if (Math.abs(median) < 0.1) {
            return null;
        }
        // Edges leaning by +d degrees (clockwise on the screen, y down) are
        // laid level by turning the picture back by d.
        return rotZ(Math.toRadians(-median));
    }

    // ------------------------------------------------------ vanishing points

    /**
     * The direction a family of edges runs along in space, as a unit vector
     * in coordinates divided by the focal length.
     *
     * Two edges are drawn at random and their lines crossed; the crossing
     * counts the length of every edge that points at it. The best of a few
     * hundred draws is kept (Fischler, Bolles, Random Sample Consensus, 1981),
     * and then refined over all the edges that agreed with it: the point
     * that lies closest to all their lines at once, the weakest direction
     * of the sum of their lines' outer products.
     */
    static double[] vanish(List<Segment> family, double cx, double cy, double focal) {
        int n = family.size();
        if (n < 2) {
            return null;
        }
        double[][] lines = new double[n][];
        double[][] mids = new double[n][];
        double[][] dirs = new double[n][];
        for (int i = 0; i < n; i++) {
            Segment s = family.get(i);
            double[] p = {(s.x1 - cx) / focal, (s.y1 - cy) / focal, 1};
            double[] q = {(s.x2 - cx) / focal, (s.y2 - cy) / focal, 1};
            lines[i] = unit(cross(p, q));
            mids[i] = new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, 1};
            dirs[i] = new double[] {q[0] - p[0], q[1] - p[1]};
        }
        // Every pair when there are few; a few hundred drawn at random when there are many.
        Random r = new Random(7);
        long pairs = (long) n * (n - 1) / 2;
        boolean every = pairs <= 400;
        int draws = every ? (int) pairs : 400;
        double best = 0;
        double[] bestPoint = null;
        int pi = 0;
        int pj = 1;
        for (int t = 0; t < draws; t++) {
            int i;
            int j;
            if (every) {
                i = pi;
                j = pj;
                if (++pj >= n) {
                    pi++;
                    pj = pi + 1;
                }
            } else {
                i = r.nextInt(n);
                j = r.nextInt(n);
                if (i == j) {
                    continue;
                }
            }
            double[] v = cross(lines[i], lines[j]);
            if (norm(v) < 1e-12) {
                continue;
            }
            v = unit(v);
            double score = 0;
            for (int k = 0; k < n; k++) {
                if (agrees(v, mids[k], dirs[k])) {
                    score += family.get(k).length;
                }
            }
            if (score > best) {
                best = score;
                bestPoint = v;
            }
        }
        if (bestPoint == null) {
            return null;
        }
        double[] m = new double[9];
        int count = 0;
        for (int k = 0; k < n; k++) {
            if (agrees(bestPoint, mids[k], dirs[k])) {
                double w = family.get(k).length;
                double[] l = lines[k];
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        m[a * 3 + b] += w * l[a] * l[b];
                    }
                }
                count++;
            }
        }
        // Two agreeing edges are enough when they are long: a page lying on
        // a table has only its two sides to stand by.
        if (count < 2) {
            return null;
        }
        return unit(weakest(m));
    }

    /** Whether an edge, by its middle and direction, points at a vanishing point. */
    private static boolean agrees(double[] v, double[] mid, double[] dir) {
        double[] towards = cross(mid, v);
        double nx = towards[0];
        double ny = towards[1];
        double nn = Math.hypot(nx, ny);
        double dn = Math.hypot(dir[0], dir[1]);
        if (nn < 1e-12 || dn < 1e-12) {
            return false;
        }
        return Math.abs(dir[0] * nx + dir[1] * ny) / (nn * dn) < AGREE;
    }

    // ------------------------------------------------------------ guided

    /** The most a turn drawn by hand may tilt the camera: further than the found ones, since the person meant it. */
    static final double MOST_GUIDED = Math.toRadians(45);

    /**
     * The turn that stands straight the lines a person drew along what should
     * be straight, each given as x1, y1, x2, y2 in the picture's pixels; null
     * if there are none or the turn would tilt too far.
     *
     * A line nearer upright than level is taken as meant upright, the others
     * as meant level. Two or more of a kind give that kind's vanishing
     * direction, found as for the edges the picture is read by, and the turn
     * takes it onto its axis; with both kinds the turn stands the picture
     * square on. A kind with one line can give no direction, only a line to
     * lay on its axis, and is answered by the one turn that cannot disturb
     * the other kind: about the optical axis when it is alone, about the
     * axis of the other kind otherwise, since a turn about the upright axis
     * leaves upright lines upright, and likewise for level.
     *
     * In coordinates divided by the focal length, a line l through two
     * points is their cross product, and a turn R carries it to R l: a line
     * stands upright when its second term is nought, and lies level when
     * its first is.
     */
    static double[] guided(List<double[]> drawn, double cx, double cy, double focal) {
        List<double[]> ups = new ArrayList<double[]>();
        List<double[]> flats = new ArrayList<double[]>();
        for (double[] d : drawn) {
            double[] a = {(d[0] - cx) / focal, (d[1] - cy) / focal, 1};
            double[] b = {(d[2] - cx) / focal, (d[3] - cy) / focal, 1};
            double[] l = cross(a, b);
            if (norm(l) < 1e-12) {
                continue;
            }
            l = unit(l);
            if (Math.abs(d[3] - d[1]) > Math.abs(d[2] - d[0])) {
                ups.add(l);
            } else {
                flats.add(l);
            }
        }
        if (ups.isEmpty() && flats.isEmpty()) {
            return null;
        }
        double[] r;
        if (ups.size() >= 2 && flats.size() >= 2) {
            double[] dv = meet(ups);
            double[] dh = meet(flats);
            if (dv[1] < 0) {
                dv = scale(dv, -1);
            }
            if (dh[0] < 0) {
                dh = scale(dh, -1);
            }
            r = squareOn(dv, dh);
        } else if (ups.size() >= 2) {
            double[] dv = meet(ups);
            if (dv[1] < 0) {
                dv = scale(dv, -1);
            }
            r = toward(dv, new double[] {0, 1, 0});
            if (flats.size() == 1) {
                double[] l = turn(r, flats.get(0));
                r = mul(rotY(small(Math.atan2(-l[0], l[2]))), r);
            }
        } else if (flats.size() >= 2) {
            double[] dh = meet(flats);
            if (dh[0] < 0) {
                dh = scale(dh, -1);
            }
            r = toward(dh, new double[] {1, 0, 0});
            if (ups.size() == 1) {
                double[] l = turn(r, ups.get(0));
                r = mul(rotX(small(Math.atan2(l[1], l[2]))), r);
            }
        } else if (ups.size() == 1) {
            double[] l = ups.get(0);
            r = rotZ(small(Math.atan2(-l[1], l[0])));
            if (flats.size() == 1) {
                double[] f = turn(r, flats.get(0));
                r = mul(rotY(small(Math.atan2(-f[0], f[2]))), r);
            }
        } else {
            double[] l = flats.get(0);
            r = rotZ(small(Math.atan2(l[0], l[1])));
        }
        if (r == null || angleOf(r) > MOST_GUIDED) {
            return null;
        }
        return r;
    }

    /** The direction a family of lines runs toward: the point closest to all of them at once. */
    private static double[] meet(List<double[]> lines) {
        double[] m = new double[9];
        for (double[] l : lines) {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m[i * 3 + j] += l[i] * l[j];
                }
            }
        }
        return unit(weakest(m));
    }

    /** A line carried by a turn. */
    private static double[] turn(double[] r, double[] l) {
        return new double[] {
            r[0] * l[0] + r[1] * l[1] + r[2] * l[2],
            r[3] * l[0] + r[4] * l[1] + r[5] * l[2],
            r[6] * l[0] + r[7] * l[1] + r[8] * l[2],
        };
    }

    /** An angle that answers a line, which has no direction, brought to the smaller of its two turns. */
    private static double small(double t) {
        while (t > Math.PI / 2) {
            t -= Math.PI;
        }
        while (t < -Math.PI / 2) {
            t += Math.PI;
        }
        return t;
    }

    // -------------------------------------------------------------- turns

    /** The shortest turn that takes direction a onto direction b (Rodrigues). */
    static double[] toward(double[] a, double[] b) {
        double[] u = unit(a);
        double[] v = unit(b);
        double[] axis = cross(u, v);
        double s = norm(axis);
        double c = dot(u, v);
        if (s < 1e-12) {
            return identity();
        }
        axis = scale(axis, 1 / s);
        return axisAngle(axis, Math.atan2(s, c));
    }

    /**
     * The turn that stands the upright direction on the y axis and lays the
     * level one on the x axis. The level direction is first made square to
     * the upright one, since edges in a picture are never quite square.
     */
    static double[] squareOn(double[] up, double[] across) {
        double[] y = unit(up);
        double[] x = sub(across, scale(y, dot(across, y)));
        if (norm(x) < 1e-6) {
            return null;
        }
        x = unit(x);
        double[] z = cross(x, y);
        return new double[] {x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
    }

    static double[] axisAngle(double[] k, double t) {
        double c = Math.cos(t);
        double s = Math.sin(t);
        double v = 1 - c;
        double x = k[0];
        double y = k[1];
        double z = k[2];
        return new double[] {
            c + x * x * v, x * y * v - z * s, x * z * v + y * s,
            y * x * v + z * s, c + y * y * v, y * z * v - x * s,
            z * x * v - y * s, z * y * v + x * s, c + z * z * v,
        };
    }

    static double[] rotZ(double t) {
        return axisAngle(new double[] {0, 0, 1}, t);
    }

    static double[] rotX(double t) {
        return axisAngle(new double[] {1, 0, 0}, t);
    }

    static double[] rotY(double t) {
        return axisAngle(new double[] {0, 1, 0}, t);
    }

    static double[] identity() {
        return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    /** How far a rotation turns, in radians. */
    static double angleOf(double[] r) {
        double c = (r[0] + r[4] + r[8] - 1) / 2;
        return Math.acos(Math.max(-1, Math.min(1, c)));
    }

    static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += a[i * 3 + k] * b[k * 3 + j];
                }
                o[i * 3 + j] = s;
            }
        }
        return o;
    }

    static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h;
        double B = -(d * i - f * g);
        double C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) {
            return null;
        }
        double k = 1 / det;
        return new double[] {
            A * k, -(b * i - c * h) * k, (b * f - c * e) * k,
            B * k, (a * i - c * g) * k, -(a * f - c * d) * k,
            C * k, -(a * h - b * g) * k, (a * e - b * d) * k,
        };
    }

    /** The direction a symmetric 3\u00d73 matrix stretches least, by the Jacobi method. */
    static double[] weakest(double[] m) {
        double[] a = m.clone();
        double[] v = identity();
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5];
            if (off < 1e-24) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    double apq = a[p * 3 + q];
                    if (Math.abs(apq) < 1e-30) {
                        continue;
                    }
                    double app = a[p * 3 + p];
                    double aqq = a[q * 3 + q];
                    double theta = (aqq - app) / (2 * apq);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k * 3 + p];
                        double akq = a[k * 3 + q];
                        a[k * 3 + p] = c * akp - s * akq;
                        a[k * 3 + q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p * 3 + k];
                        double aqk = a[q * 3 + k];
                        a[p * 3 + k] = c * apk - s * aqk;
                        a[q * 3 + k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k * 3 + p];
                        double vkq = v[k * 3 + q];
                        v[k * 3 + p] = c * vkp - s * vkq;
                        v[k * 3 + q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        int least = 0;
        for (int i = 1; i < 3; i++) {
            if (a[i * 3 + i] < a[least * 3 + least]) {
                least = i;
            }
        }
        return new double[] {v[least], v[3 + least], v[6 + least]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] unit(double[] a) {
        double n = norm(a);
        return n < 1e-30 ? a : scale(a, 1 / n);
    }

    /** A new vector, k times the given one; the given one is left as it was. */
    static double[] scale(double[] a, double k) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            o[i] = a[i] * k;
        }
        return o;
    }

    static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    // ------------------------------------------------------------ segments

    /** The level-line tolerance, and the gradient below which a pixel is noise. */
    private static final double TAU = Math.toRadians(22.5);
    private static final double RHO = 2.0 / Math.sin(TAU);
    /**
     * A region must be this dense in its rectangle to be taken for a line. The
     * rectangle of a thin edge is drawn round its jagged pixels, so half full
     * is already a line.
     */
    private static final double DENSITY = 0.5;
    /** The shortest segment worth keeping, as a share of the long side. */
    private static final double SHORTEST = 0.035;

    /**
     * Straight edges grown from pixels whose gradients agree in direction
     * (von Gioi, Jakubowicz, Morel, Randall, LSD: a Fast Line Segment
     * Detector with a False Detection Control, 2010).
     *
     * The gradient is taken on a two by two window, pixels are visited from
     * the strongest gradient down, and a region grows over neighbours whose
     * level-line angle is within a tolerance of the region's own; a region
     * becomes a segment through its principal axis. The paper's validation of
     * each segment against chance, and its refinement of the rectangle, are
     * replaced here by a plain density threshold and a shortest length: the
     * segments only vote, and a wrong one is outvoted by the consensus that
     * follows.
     */
    static List<Segment> segments(float[] g, int w, int h) {
        int n = w * h;
        float[] angle = new float[n];
        float[] mag = new float[n];
        boolean[] used = new boolean[n];
        float most = 0;
        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w - 1; x++) {
                int i = y * w + x;
                float a = g[i];
                float b = g[i + 1];
                float c = g[i + w];
                float d = g[i + w + 1];
                float gx = (b + d - a - c) / 2f;
                float gy = (c + d - a - b) / 2f;
                float m = (float) Math.hypot(gx, gy);
                mag[i] = m;
                angle[i] = (float) Math.atan2(gx, -gy);
                if (m > most) {
                    most = m;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            used[(h - 1) * w + x] = true;
        }
        for (int y = 0; y < h; y++) {
            used[y * w + w - 1] = true;
        }
        if (most <= RHO) {
            return new ArrayList<Segment>();
        }

        // Pixels in order of falling gradient, by buckets: exact order buys nothing.
        int bins = 1024;
        int[] count = new int[bins];
        for (int i = 0; i < n; i++) {
            if (!used[i] && mag[i] > RHO) {
                count[Math.min(bins - 1, (int) (mag[i] / most * (bins - 1)))]++;
            }
        }
        int[] start = new int[bins];
        int total = 0;
        for (int b = bins - 1; b >= 0; b--) {
            start[b] = total;
            total += count[b];
        }
        int[] order = new int[total];
        for (int i = 0; i < n; i++) {
            if (!used[i] && mag[i] > RHO) {
                int b = Math.min(bins - 1, (int) (mag[i] / most * (bins - 1)));
                order[start[b]++] = i;
            }
        }

        List<Segment> out = new ArrayList<Segment>();
        int[] region = new int[n];
        double shortest = SHORTEST * Math.max(w, h);
        for (int o = 0; o < total; o++) {
            int seed = order[o];
            if (used[seed]) {
                continue;
            }
            int size = 0;
            region[size++] = seed;
            used[seed] = true;
            double sx = Math.cos(angle[seed]);
            double sy = Math.sin(angle[seed]);
            double ra = angle[seed];
            for (int k = 0; k < size; k++) {
                int p = region[k];
                int px = p % w;
                int py = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = py + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = px + dx;
                        if (xx < 0 || xx >= w) {
                            continue;
                        }
                        int q = yy * w + xx;
                        if (used[q] || mag[q] <= RHO) {
                            continue;
                        }
                        double diff = Math.abs(angle[q] - ra);
                        if (diff > Math.PI) {
                            diff = 2 * Math.PI - diff;
                        }
                        if (diff <= TAU) {
                            used[q] = true;
                            region[size++] = q;
                            sx += Math.cos(angle[q]);
                            sy += Math.sin(angle[q]);
                            ra = Math.atan2(sy, sx);
                        }
                    }
                }
            }
            if (size < 12) {
                continue;
            }
            Segment s = rectangle(region, size, mag, w, shortest);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** A region as a segment along its principal axis, if it is long and dense enough to be one. */
    private static Segment rectangle(int[] region, int size, float[] mag, int w, double shortest) {
        double sw = 0;
        double mx = 0;
        double my = 0;
        for (int k = 0; k < size; k++) {
            int p = region[k];
            double m = mag[p];
            sw += m;
            mx += m * (p % w);
            my += m * (p / w);
        }
        mx /= sw;
        my /= sw;
        double ixx = 0;
        double iyy = 0;
        double ixy = 0;
        for (int k = 0; k < size; k++) {
            int p = region[k];
            double m = mag[p];
            double dx = p % w - mx;
            double dy = p / w - my;
            ixx += m * dx * dx;
            iyy += m * dy * dy;
            ixy += m * dx * dy;
        }
        double theta = 0.5 * Math.atan2(2 * ixy, ixx - iyy);
        double ux = Math.cos(theta);
        double uy = Math.sin(theta);
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        double wlo = Double.MAX_VALUE;
        double whi = -Double.MAX_VALUE;
        for (int k = 0; k < size; k++) {
            int p = region[k];
            double dx = p % w - mx;
            double dy = p / w - my;
            double along = dx * ux + dy * uy;
            double across = -dx * uy + dy * ux;
            lo = Math.min(lo, along);
            hi = Math.max(hi, along);
            wlo = Math.min(wlo, across);
            whi = Math.max(whi, across);
        }
        double length = hi - lo + 1;
        double width = whi - wlo + 1;
        if (length < shortest || length < 3 * width) {
            return null;
        }
        if (size / (length * width) < DENSITY) {
            return null;
        }
        return new Segment(mx + lo * ux, my + lo * uy, mx + hi * ux, my + hi * uy);
    }
}
