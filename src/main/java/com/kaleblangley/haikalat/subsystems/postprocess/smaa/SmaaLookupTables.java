package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

/**
 * Procedural SMAA 1x area/search lookup tables.
 *
 * <p>This class is a port of the reference generators {@code Scripts/AreaTex.py}
 * and {@code Scripts/SearchTex.py} from the SMAA repository
 * (https://github.com/iryoku/smaa, commit
 * {@code 71c806a838bdd7d517df19192a20f0c61b3ca29d}), Copyright (C) 2013 Jorge
 * Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro and Diego
 * Gutierrez, MIT License.  The generation math, offsets, pattern tables and
 * truncation to 8-bit bytes are unchanged; only the surrounding image I/O was
 * removed.  {@code docs/licenses/smaa.md} carries the licence text and the
 * fingerprints of the generated payloads.</p>
 *
 * <p>Area payload layout: 160x560 RG8, orthogonal patterns in the left half
 * and diagonal patterns in the right half, with seven orthogonal sub-pixel
 * offset slots and five diagonal slots.  Search payload layout: 64x16 R8,
 * vertically flipped, left/right halves.  Both are sampled with linear
 * filtering and clamp-to-edge by the SMAA passes, matching the reference.</p>
 */
final class SmaaLookupTables {
    static final int AREA_WIDTH = 160;
    static final int AREA_HEIGHT = 560;
    static final int SEARCH_WIDTH = 64;
    static final int SEARCH_HEIGHT = 16;

    /** SHA-256 of the author-reference AreaTexDX10 RG payload. */
    static final String AREA_SHA256 =
            "35065cef2a02cabcad711d6bf430239ae64e27d71c4e4fa06f29cce2c992f0d2";
    /** SHA-256 of the author-reference SearchTex R payload. */
    static final String SEARCH_SHA256 =
            "3694eae5e9d44b8ebb4415a13f8c7b94dc08a2fc86658434d771c4610fe5744d";

    private static final int SIZE_ORTHO = 16;
    private static final int SIZE_DIAG = 20;
    private static final int SAMPLES_DIAG = 30;
    private static final int SMOOTH_MAX_DISTANCE = 32;

    private static final double[] SUBSAMPLE_OFFSETS_ORTHO = {
            0.0, -0.25, 0.25, -0.125, 0.125, -0.375, 0.375
    };
    private static final double[][] SUBSAMPLE_OFFSETS_DIAG = {
            {0.0, 0.0}, {0.25, -0.25}, {-0.25, 0.25}, {0.125, -0.125}, {-0.125, 0.125}
    };

    private static final int[][] EDGES_ORTHO = {
            {0, 0}, {3, 0}, {0, 3}, {3, 3}, {1, 0}, {4, 0}, {1, 3}, {4, 3},
            {0, 1}, {3, 1}, {0, 4}, {3, 4}, {1, 1}, {4, 1}, {1, 4}, {4, 4}
    };
    private static final int[][] EDGES_DIAG = {
            {0, 0}, {1, 0}, {0, 2}, {1, 2}, {2, 0}, {3, 0}, {2, 2}, {3, 2},
            {0, 1}, {1, 1}, {0, 3}, {1, 3}, {2, 1}, {3, 1}, {2, 3}, {3, 3}
    };

    private SmaaLookupTables() {
    }

    /** Returns a fresh copy of the generated 160x560 RG8 area payload. */
    static byte[] areaRg() {
        return Holder.AREA.clone();
    }

    /** Returns a fresh copy of the generated 64x16 R8 search payload. */
    static byte[] searchR() {
        return Holder.SEARCH.clone();
    }

    private static final class Holder {
        private static final byte[] AREA = generateAreaRg();
        private static final byte[] SEARCH = generateSearchR();
    }

    /**
     * Generates the area payload exactly like {@code AreaTex.py}'s
     * {@code tex4dortho}/{@code tex4ddiag} + {@code assemble} path.
     */
    static byte[] generateAreaRg() {
        byte[] payload = new byte[AREA_WIDTH * AREA_HEIGHT * 2];
        for (int slot = 0; slot < SUBSAMPLE_OFFSETS_ORTHO.length; slot++) {
            double offset = SUBSAMPLE_OFFSETS_ORTHO[slot];
            for (int pattern = 0; pattern < 16; pattern++) {
                int baseX = 16 * EDGES_ORTHO[pattern][0];
                int baseY = 5 * SIZE_ORTHO * slot + 16 * EDGES_ORTHO[pattern][1];
                for (int left = 0; left < SIZE_ORTHO; left++) {
                    for (int right = 0; right < SIZE_ORTHO; right++) {
                        // The reference assembles the ortho subtextures through a
                        // quadratic compress(): destination (left,right) holds the
                        // area for distances (left^2, right^2).
                        double[] area = areaOrtho(pattern, left * left, right * right, offset);
                        put(payload, baseX + left, baseY + right, area);
                    }
                }
            }
        }
        for (int slot = 0; slot < SUBSAMPLE_OFFSETS_DIAG.length; slot++) {
            double[] offset = SUBSAMPLE_OFFSETS_DIAG[slot];
            for (int pattern = 0; pattern < 16; pattern++) {
                int baseX = 5 * SIZE_ORTHO + SIZE_DIAG * EDGES_DIAG[pattern][0];
                int baseY = 4 * SIZE_DIAG * slot + SIZE_DIAG * EDGES_DIAG[pattern][1];
                for (int left = 0; left < SIZE_DIAG; left++) {
                    for (int right = 0; right < SIZE_DIAG; right++) {
                        double[] area = areaDiag(pattern, left, right, offset);
                        put(payload, baseX + left, baseY + right, area);
                    }
                }
            }
        }
        return payload;
    }

    private static void put(byte[] payload, int x, int y, double[] area) {
        int index = (y * AREA_WIDTH + x) * 2;
        payload[index] = toByte(area[0]);
        payload[index + 1] = toByte(area[1]);
    }

    /** {@code bytes()} in the reference: truncation after scaling to 0..255. */
    private static byte toByte(double value) {
        int scaled = (int) (255.0 * value);
        if (scaled < 0 || scaled > 255) {
            throw new IllegalStateException("SMAA area value outside byte range: " + value);
        }
        return (byte) scaled;
    }

    // ------------------------------------------------------------------
    // Orthogonal areas (AreaTex.py: areaortho)

    private static double[] areaOrtho(int pattern, double left, double right, double offset) {
        double d = left + right + 1.0;
        double o1 = 0.5 + offset;
        double o2 = 0.5 + offset - 1.0;
        return switch (pattern) {
            case 0, 5, 10, 15 -> new double[]{0.0, 0.0};
            case 1 -> left <= right
                    ? area(0.0, o2, d / 2.0, 0.0, left) : new double[]{0.0, 0.0};
            case 2 -> left >= right
                    ? area(d / 2.0, 0.0, d, o2, left) : new double[]{0.0, 0.0};
            case 3 -> smoothSum(d, area(0.0, o2, d / 2.0, 0.0, left),
                    area(d / 2.0, 0.0, d, o2, left));
            case 4 -> left <= right
                    ? area(0.0, o1, d / 2.0, 0.0, left) : new double[]{0.0, 0.0};
            case 6 -> Math.abs(offset) > 0.0
                    ? divide(sum(area(0.0, o1, d, o2, left),
                            sum(area(0.0, o1, d / 2.0, 0.0, left),
                                    area(d / 2.0, 0.0, d, o2, left))), 2.0)
                    : area(0.0, o1, d, o2, left);
            case 7 -> area(0.0, o1, d, o2, left);
            case 8 -> left >= right
                    ? area(d / 2.0, 0.0, d, o1, left) : new double[]{0.0, 0.0};
            case 9 -> Math.abs(offset) > 0.0
                    ? divide(sum(area(0.0, o2, d, o1, left),
                            sum(area(0.0, o2, d / 2.0, 0.0, left),
                                    area(d / 2.0, 0.0, d, o1, left))), 2.0)
                    : area(0.0, o2, d, o1, left);
            case 11 -> area(0.0, o2, d, o1, left);
            case 12 -> smoothSum(d, area(0.0, o1, d / 2.0, 0.0, left),
                    area(d / 2.0, 0.0, d, o1, left));
            case 13 -> area(0.0, o2, d, o1, left);
            case 14 -> area(0.0, o1, d, o2, left);
            default -> throw new IllegalArgumentException("invalid orthogonal SMAA pattern " + pattern);
        };
    }

    /**
     * Calculates the area under the line p1-&gt;p2 for the pixel x..x+1,
     * biased by {@code offset} (AreaTex.py: areaortho.area).
     */
    private static double[] area(double p1x, double p1y, double p2x, double p2y, double x) {
        double dx = p2x - p1x;
        double dy = p2y - p1y;
        double x1 = x;
        double x2 = x + 1.0;
        double y1 = p1y + dy * (x1 - p1x) / dx;
        double y2 = p1y + dy * (x2 - p1x) / dx;

        boolean inside = (x1 >= p1x && x1 < p2x) || (x2 > p1x && x2 <= p2x);
        if (!inside) {
            return new double[]{0.0, 0.0};
        }
        boolean isTrapezoid = Math.copySign(1.0, y1) == Math.copySign(1.0, y2)
                || Math.abs(y1) < 1e-4 || Math.abs(y2) < 1e-4;
        if (isTrapezoid) {
            double a = (y1 + y2) / 2.0;
            return a < 0.0 ? new double[]{Math.abs(a), 0.0} : new double[]{0.0, Math.abs(a)};
        }
        double crossing = -p1y * dx / dy + p1x;
        double fraction = modf(crossing);
        double a1 = crossing > p1x ? y1 * fraction / 2.0 : 0.0;
        double a2 = crossing < p2x ? y2 * (1.0 - fraction) / 2.0 : 0.0;
        double a = Math.abs(a1) > Math.abs(a2) ? a1 : -a2;
        if (a < 0.0) {
            return new double[]{Math.abs(a1), Math.abs(a2)};
        }
        return new double[]{Math.abs(a2), Math.abs(a1)};
    }

    /** Python {@code math.modf(x)[0]}: fractional part with the sign of {@code x}. */
    private static double modf(double value) {
        double truncated = value < 0.0 ? Math.ceil(value) : Math.floor(value);
        return value - truncated;
    }

    /**
     * AreaTex.py {@code smootharea}: blends each of the two vec2 areas towards
     * its square-root form and returns {@code a1[0]+a2[0], a1[1]+a2[1]}.
     */
    private static double[] smoothSum(double d, double[] a1, double[] a2) {
        double[] b1 = {(Math.sqrt(a1[0] * 2.0)) * 0.5, (Math.sqrt(a1[1] * 2.0)) * 0.5};
        double[] b2 = {(Math.sqrt(a2[0] * 2.0)) * 0.5, (Math.sqrt(a2[1] * 2.0)) * 0.5};
        double p = saturate(d / (double) SMOOTH_MAX_DISTANCE);
        double s1x = lerp(b1[0], a1[0], p);
        double s1y = lerp(b1[1], a1[1], p);
        double s2x = lerp(b2[0], a2[0], p);
        double s2y = lerp(b2[1], a2[1], p);
        return new double[]{s1x + s2x, s1y + s2y};
    }

    private static double[] sum(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1]};
    }

    private static double[] divide(double[] value, double divisor) {
        return new double[]{value[0] / divisor, value[1] / divisor};
    }

    private static double saturate(double value) {
        return Math.min(Math.max(value, 0.0), 1.0);
    }

    private static double lerp(double a, double b, double p) {
        return a + (b - a) * p;
    }

    // ------------------------------------------------------------------
    // Diagonal areas (AreaTex.py: areadiag)

    private static double[] areaDiag(int pattern, int left, int right, double[] offset) {
        int d = left + right + 1;
        return switch (pattern) {
            case 0 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 1 -> divide(sum(areaDiagLine(pattern, 1.0, 0.0, 0.0 + d, 0.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 2 -> divide(sum(areaDiagLine(pattern, 0.0, 0.0, 1.0 + d, 0.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 3 -> areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset);
            case 4 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 0.0 + d, 0.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 5 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 0.0 + d, 0.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 6 -> areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 0.0 + d, left, offset);
            case 7 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 0.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 8 -> divide(sum(areaDiagLine(pattern, 0.0, 0.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 1.0 + d, left, offset)), 2.0);
            case 9 -> areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 1.0 + d, left, offset);
            case 10 -> divide(sum(areaDiagLine(pattern, 0.0, 0.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 11 -> divide(sum(areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 12 -> areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 1.0 + d, left, offset);
            case 13 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 1.0 + d, left, offset)), 2.0);
            case 14 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            case 15 -> divide(sum(areaDiagLine(pattern, 1.0, 1.0, 1.0 + d, 1.0 + d, left, offset),
                    areaDiagLine(pattern, 1.0, 0.0, 1.0 + d, 0.0 + d, left, offset)), 2.0);
            default -> throw new IllegalArgumentException("invalid diagonal SMAA pattern " + pattern);
        };
    }

    /**
     * AreaTex.py {@code areadiag.area}: includes the pixel and its opposite and
     * offsets the ends whose edge index is non-zero.
     */
    private static double[] areaDiagLine(int pattern, double p1x, double p1y,
                                         double p2x, double p2y, int left, double[] offset) {
        int[] edges = EDGES_DIAG[pattern];
        if (edges[0] > 0) {
            p1x += offset[0];
            p1y += offset[1];
        }
        if (edges[1] > 0) {
            p2x += offset[0];
            p2y += offset[1];
        }
        double a1 = area1(p1x, p1y, p2x, p2y, 1.0 + left, 0.0 + left);
        double a2 = area1(p1x, p1y, p2x, p2y, 1.0 + left, 1.0 + left);
        return new double[]{1.0 - a1, a2};
    }

    private static double area1(double p1x, double p1y, double p2x, double p2y,
                                double px, double py) {
        boolean degenerate = p1x == p2x && p1y == p2y;
        double accumulator = 0.0;
        for (int x = 0; x < SAMPLES_DIAG; x++) {
            for (int y = 0; y < SAMPLES_DIAG; y++) {
                double ox = x / (double) (SAMPLES_DIAG - 1);
                double oy = y / (double) (SAMPLES_DIAG - 1);
                boolean inside;
                if (!degenerate) {
                    double sampleX = px + ox;
                    double sampleY = py + oy;
                    double xm = (p1x + p2x) / 2.0;
                    double ym = (p1y + p2y) / 2.0;
                    double a = p2y - p1y;
                    double b = p1x - p2x;
                    double c = a * (sampleX - xm) + b * (sampleY - ym);
                    inside = c > 0.0;
                } else {
                    inside = true;
                }
                if (inside) {
                    accumulator += 1.0;
                }
            }
        }
        return accumulator / (SAMPLES_DIAG * SAMPLES_DIAG);
    }

    // ------------------------------------------------------------------
    // Search texture (SearchTex.py)

    static byte[] generateSearchR() {
        int imageWidth = 66;
        int imageHeight = 33;
        int[] image = new int[imageWidth * imageHeight];
        double[] edgeValues = new double[16];
        int[][] edgePatterns = new int[16][];
        int index = 0;
        for (int e0 = 0; e0 < 2; e0++) {
            for (int e1 = 0; e1 < 2; e1++) {
                for (int e2 = 0; e2 < 2; e2++) {
                    for (int e3 = 0; e3 < 2; e3++) {
                        edgePatterns[index] = new int[]{e0, e1, e2, e3};
                        edgeValues[index] = bilinear(e0, e1, e2, e3);
                        index++;
                    }
                }
            }
        }
        for (int x = 0; x < 33; x++) {
            for (int y = 0; y < 33; y++) {
                int[] leftEdges = edgesAt(0.03125 * x, edgeValues, edgePatterns);
                int[] topEdges = edgesAt(0.03125 * y, edgeValues, edgePatterns);
                if (leftEdges == null || topEdges == null) {
                    continue;
                }
                image[y * imageWidth + x] = 127 * deltaLeft(leftEdges, topEdges);
                image[y * imageWidth + 33 + x] = 127 * deltaRight(leftEdges, topEdges);
            }
        }
        byte[] payload = new byte[SEARCH_WIDTH * SEARCH_HEIGHT];
        for (int y = 0; y < SEARCH_HEIGHT; y++) {
            // Crop to rows 17..32, then flip top-bottom.
            int sourceY = 32 - y;
            for (int x = 0; x < SEARCH_WIDTH; x++) {
                payload[y * SEARCH_WIDTH + x] = (byte) image[sourceY * imageWidth + x];
            }
        }
        return payload;
    }

    private static double bilinear(int e0, int e1, int e2, int e3) {
        double a = lerp(e0, e1, 1.0 - 0.25);
        double b = lerp(e2, e3, 1.0 - 0.25);
        return lerp(a, b, 1.0 - 0.125);
    }

    private static int[] edgesAt(double coordinate, double[] values, int[][] patterns) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == coordinate) {
                return patterns[index];
            }
        }
        return null;
    }

    private static int deltaLeft(int[] left, int[] top) {
        int delta = 0;
        if (top[3] == 1) {
            delta += 1;
        }
        if (delta == 1 && top[2] == 1 && left[1] != 1 && left[3] != 1) {
            delta += 1;
        }
        return delta;
    }

    private static int deltaRight(int[] left, int[] top) {
        int delta = 0;
        if (top[3] == 1 && left[1] != 1 && left[3] != 1) {
            delta += 1;
        }
        if (delta == 1 && top[2] == 1 && left[0] != 1 && left[2] != 1) {
            delta += 1;
        }
        return delta;
    }
}
