/*
 * Perpendicular Distance Tool - ImageJ / Fiji plugin
 * Copyright (C) 2026 Justinas Jorudas
 * Developed with Claude (Anthropic)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.gui.Toolbar;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.tool.PlugInTool;
import ij.process.FloatPolygon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Perpendicular Distance Tool for ImageJ 1.54p / Fiji.
 *
 * Usage
 *   1. Click twice on the image to define a reference line of arbitrary orientation.
 *   2. Every further click measures the perpendicular (normal) distance to that line.
 *      In "Point pair" mode, two clicks measure the separation of the two points
 *      along the direction perpendicular to the reference line.
 *
 *   Alt + click         : discard the reference line and start a new one at this point
 *   Shift + click       : undo the last measurement
 *   Double-click icon   : options (mode, colours, labels, clear, use selection, ...)
 *
 * Distances are computed in calibrated units (Image > Properties / Analyze > Set Scale),
 * correctly handling non-square pixels. Results go to the "Perpendicular Distances" table;
 * graphics are drawn as a non-destructive overlay.
 */
public class Perpendicular_Distance_Tool extends PlugInTool {

    static final String TOOL_NAME = "Perpendicular Distance Tool";
    static final String TABLE = "Perpendicular Distances";
    static final String TAG = "PDT:";
    static final String PREF = "pdt.";
    static final String[] MODES = {"Point to line", "Point pair (perpendicular separation)"};
    static final String[] COLORS = {"yellow", "cyan", "magenta", "red", "green", "blue",
            "orange", "white", "black"};

    // ---- options (persisted in IJ_Prefs.txt) ----
    static int mode = (int) Prefs.get(PREF + "mode", 0);
    static String refColor = Prefs.get(PREF + "refColor", "cyan");
    static String measColor = Prefs.get(PREF + "measColor", "yellow");
    static double lineWidth = Prefs.get(PREF + "lineWidth", 1.0);
    static int fontSize = (int) Prefs.get(PREF + "fontSize", 14);
    static int decimals = (int) Prefs.get(PREF + "decimals", 3);
    static boolean showLabels = Prefs.get(PREF + "showLabels", true);
    static boolean showIndex = Prefs.get(PREF + "showIndex", true);
    static boolean extendRef = Prefs.get(PREF + "extendRef", true);
    static boolean livePreview = Prefs.get(PREF + "livePreview", true);
    static boolean rightAngle = Prefs.get(PREF + "rightAngle", true);
    static boolean signedDist = Prefs.get(PREF + "signed", false);
    static boolean showParallel = Prefs.get(PREF + "showParallel", true);
    static boolean showPoints = Prefs.get(PREF + "showPoints", false);
    static boolean detailedTable = Prefs.get(PREF + "detailedTable", false);

    /** Per-image state. */
    static class State {
        double[] ref;          // x1,y1,x2,y2 (pixel coords) or null
        double[] refStart;     // first click of the reference line, or null
        double[] pairStart;    // first point of a pair measurement, or null
        int pairSlice;
        final List<Meas> meas = new ArrayList<Meas>();
        int counter;
        double[] preview;      // cursor position for live preview, or null
    }

    static class Meas {
        int id, slice, row = -1;
        double[] ref;          // reference line used (copy)
        double x1, y1;         // first (or only) point
        double x2, y2;         // second point (pair mode)
        boolean pair;
    }

    static final Map<ImagePlus, State> states = new WeakHashMap<ImagePlus, State>();

    static State state(ImagePlus imp) {
        State s = states.get(imp);
        if (s == null) {
            s = new State();
            states.put(imp, s);
        }
        return s;
    }

    // ------------------------------------------------------------------ PlugInTool

    public void run(String arg) {
        Toolbar.addPlugInTool(this);
        IJ.showStatus(TOOL_NAME + ": click twice to set the reference line (double-click icon for options)");
    }

    public String getToolName() {
        return TOOL_NAME;
    }

    public String getToolIcon() {
        // reference line (blue diagonal), perpendicular (red) with point, right-angle mark
        return "C00fL1ee1Cf00L4477O2233C888L9573L7355";
    }

    public void mousePressed(ImagePlus imp, MouseEvent e) {
        e.consume();
        ImageCanvas ic = imp.getCanvas();
        if (ic == null) return;
        double x = ic.offScreenXD(e.getX());
        double y = ic.offScreenYD(e.getY());
        State s = state(imp);

        if (e.isShiftDown() && !e.isAltDown()) {          // undo last
            undoLast(imp, s);
            return;
        }
        if (e.isAltDown()) {                               // new reference line
            s.ref = null;
            s.pairStart = null;
            s.refStart = new double[]{x, y};
            redraw(imp);
            IJ.showStatus("Reference line: click the second point");
            return;
        }
        if (s.ref == null) {
            if (s.refStart == null) {
                s.refStart = new double[]{x, y};
                IJ.showStatus("Reference line: click the second point");
            } else {
                if (Math.hypot(x - s.refStart[0], y - s.refStart[1]) < 1e-6) {
                    IJ.showStatus("Reference line points must differ");
                    return;
                }
                s.ref = new double[]{s.refStart[0], s.refStart[1], x, y};
                s.refStart = null;
                IJ.showStatus(String.format("Reference line set (angle %.2f°). Click to measure.",
                        refAngle(imp, s.ref)));
            }
            redraw(imp);
            return;
        }

        if (mode == 0) {
            addMeas(imp, s, x, y, 0, 0, false);
        } else {
            if (s.pairStart == null) {
                s.pairStart = new double[]{x, y};
                s.pairSlice = imp.getCurrentSlice();
                IJ.showStatus("Point pair: click the second point");
                redraw(imp);
            } else {
                double[] p1 = s.pairStart;
                s.pairStart = null;
                addMeas(imp, s, p1[0], p1[1], x, y, true);
            }
        }
    }

    public void mouseMoved(ImagePlus imp, MouseEvent e) {
        if (!livePreview) return;
        State s = states.get(imp);
        if (s == null || (s.ref == null && s.refStart == null)) return;
        ImageCanvas ic = imp.getCanvas();
        if (ic == null) return;
        double x = ic.offScreenXD(e.getX());
        double y = ic.offScreenYD(e.getY());
        s.preview = new double[]{x, y};
        redraw(imp);
        if (s.ref != null) {
            double d;
            if (mode == 1 && s.pairStart != null)
                d = pairDist(imp, s.ref, s.pairStart[0], s.pairStart[1], x, y);
            else
                d = signedDist(imp, s.ref, x, y);
            IJ.showStatus("Perpendicular distance: " + fmt(signedDist ? d : Math.abs(d)) + " " + units(imp));
        } else if (s.refStart != null) {
            IJ.showStatus(String.format("Reference line angle: %.2f°",
                    refAngle(imp, new double[]{s.refStart[0], s.refStart[1], x, y})));
        }
    }

    public void mouseExited(ImagePlus imp, MouseEvent e) {
        State s = states.get(imp);
        if (s != null && s.preview != null) {
            s.preview = null;
            redraw(imp);
        }
    }

    public void showOptionsDialog() {
        ImagePlus imp = ij.WindowManager.getCurrentImage();
        GenericDialog gd = new GenericDialog(TOOL_NAME + " Options");
        gd.addChoice("Mode:", MODES, MODES[mode]);
        gd.addChoice("Reference line colour:", COLORS, refColor);
        gd.addChoice("Measurement colour:", COLORS, measColor);
        gd.addNumericField("Line width:", lineWidth, 1);
        gd.addNumericField("Label font size:", fontSize, 0);
        gd.addNumericField("Decimal places:", decimals, 0);
        gd.addCheckbox("Show distance labels", showLabels);
        gd.addCheckbox("Show measurement number in label", showIndex);
        gd.addCheckbox("Extend reference/parallel lines to image edges", extendRef);
        gd.addCheckbox("Draw right-angle marker", rightAngle);
        gd.addCheckbox("Live preview while moving the mouse", livePreview);
        gd.addCheckbox("Report signed distance (side of the line)", signedDist);
        gd.addCheckbox("Draw parallel line through measured points", showParallel);
        gd.addCheckbox("Draw dots at clicked points", showPoints);
        gd.addCheckbox("Add coordinate columns to results table", detailedTable);
        if (imp != null) {
            gd.setInsets(15, 20, 0);
            gd.addMessage("Actions for \"" + imp.getTitle() + "\":");
            gd.addCheckbox("Use current straight-line selection as reference", false);
            gd.addCheckbox("Delete reference line", false);
            gd.addCheckbox("Delete all measurements", false);
        }
        gd.addHelp(helpHtml());
        gd.showDialog();
        if (gd.wasCanceled()) return;

        mode = gd.getNextChoiceIndex();
        refColor = gd.getNextChoice();
        measColor = gd.getNextChoice();
        lineWidth = Math.max(0.1, gd.getNextNumber());
        fontSize = Math.max(6, (int) gd.getNextNumber());
        decimals = Math.max(0, Math.min(9, (int) gd.getNextNumber()));
        showLabels = gd.getNextBoolean();
        showIndex = gd.getNextBoolean();
        extendRef = gd.getNextBoolean();
        rightAngle = gd.getNextBoolean();
        livePreview = gd.getNextBoolean();
        signedDist = gd.getNextBoolean();
        showParallel = gd.getNextBoolean();
        showPoints = gd.getNextBoolean();
        detailedTable = gd.getNextBoolean();
        savePrefs();

        if (imp != null) {
            boolean useSel = gd.getNextBoolean();
            boolean delRef = gd.getNextBoolean();
            boolean delMeas = gd.getNextBoolean();
            State s = state(imp);
            if (useSel) {
                Roi roi = imp.getRoi();
                if (roi != null && roi.getType() == Roi.LINE) {
                    Line l = (Line) roi;
                    s.ref = new double[]{l.x1d, l.y1d, l.x2d, l.y2d};
                    s.refStart = null;
                    s.pairStart = null;
                    imp.deleteRoi();
                    IJ.setTool(TOOL_NAME);
                } else {
                    IJ.error(TOOL_NAME, "No straight-line selection on \"" + imp.getTitle() + "\".");
                }
            }
            if (delRef) {
                s.ref = null;
                s.refStart = null;
                s.pairStart = null;
            }
            if (delMeas) {
                s.meas.clear();
                s.counter = 0;
                s.pairStart = null;
            }
        }
        for (ImagePlus im : new ArrayList<ImagePlus>(states.keySet()))
            if (im != null && im.getWindow() != null) redraw(im);
    }

    // ------------------------------------------------------------------ measurement

    void addMeas(ImagePlus imp, State s, double x1, double y1, double x2, double y2, boolean pair) {
        Meas m = new Meas();
        m.id = ++s.counter;
        m.slice = pair ? s.pairSlice : imp.getCurrentSlice();
        m.ref = s.ref;   // shared (s.ref is replaced, never mutated)
        m.x1 = x1; m.y1 = y1; m.x2 = x2; m.y2 = y2;
        m.pair = pair;
        s.meas.add(m);

        double d = pair ? pairDist(imp, m.ref, x1, y1, x2, y2) : signedDist(imp, m.ref, x1, y1);
        double[] foot = pair ? pairFoot(imp, m.ref, x1, y1, x2, y2) : foot(imp, m.ref, x1, y1);
        Calibration cal = imp.getCalibration();

        ResultsTable rt = ResultsTable.getResultsTable(TABLE);
        if (rt == null) rt = new ResultsTable();
        rt.incrementCounter();
        rt.addValue("Image", imp.getTitle());
        rt.addValue("No.", m.id);
        rt.addValue("Mode", pair ? "pair" : "point");
        rt.addValue("Distance", signedDist ? d : Math.abs(d));
        rt.addValue("Units", units(imp));
        rt.addValue("Ref angle (deg)", refAngle(imp, m.ref));
        if (detailedTable) {
            if (imp.getStackSize() > 1) rt.addValue("Slice", m.slice);
            rt.addValue("X1 (px)", x1);
            rt.addValue("Y1 (px)", y1);
            if (pair) {
                rt.addValue("X2 (px)", x2);
                rt.addValue("Y2 (px)", y2);
            }
            rt.addValue("Foot X (px)", foot[0]);
            rt.addValue("Foot Y (px)", foot[1]);
        }
        rt.setDecimalPlaces(rt.getColumnIndex("Distance"), decimals);
        rt.setDecimalPlaces(rt.getColumnIndex("Ref angle (deg)"), 2);
        rt.show(TABLE);
        m.row = rt.size() - 1;

        IJ.showStatus("#" + m.id + ": perpendicular distance = " + fmt(signedDist ? d : Math.abs(d))
                + " " + units(imp));
        redraw(imp);
    }

    void undoLast(ImagePlus imp, State s) {
        if (s.pairStart != null) {
            s.pairStart = null;
        } else if (!s.meas.isEmpty()) {
            Meas m = s.meas.remove(s.meas.size() - 1);
            s.counter = m.id - 1;
            ResultsTable rt = ResultsTable.getResultsTable(TABLE);
            if (rt != null && m.row >= 0 && m.row == rt.size() - 1
                    && imp.getTitle().equals(rt.getStringValue("Image", m.row))) {
                rt.deleteRow(m.row);
                rt.show(TABLE);
            }
            IJ.showStatus("Removed measurement #" + m.id);
        } else if (s.ref != null || s.refStart != null) {
            s.ref = null;
            s.refStart = null;
            IJ.showStatus("Reference line removed");
        }
        redraw(imp);
    }

    // ------------------------------------------------------------------ geometry
    // All geometry is done in calibrated space so non-square pixels are handled correctly.

    static double pw(ImagePlus imp) {
        double v = imp.getCalibration().pixelWidth;
        return v > 0 ? v : 1;
    }

    static double ph(ImagePlus imp) {
        double v = imp.getCalibration().pixelHeight;
        return v > 0 ? v : 1;
    }

    /** unit direction u and normal n of the reference line in calibrated space: {ux,uy,nx,ny}. */
    static double[] frame(ImagePlus imp, double[] r) {
        double dx = (r[2] - r[0]) * pw(imp), dy = (r[3] - r[1]) * ph(imp);
        double len = Math.hypot(dx, dy);
        double ux = dx / len, uy = dy / len;
        return new double[]{ux, uy, -uy, ux};
    }

    static double signedDist(ImagePlus imp, double[] r, double x, double y) {
        double[] f = frame(imp, r);
        double px = (x - r[0]) * pw(imp), py = (y - r[1]) * ph(imp);
        return px * f[2] + py * f[3];
    }

    static double[] foot(ImagePlus imp, double[] r, double x, double y) {
        double[] f = frame(imp, r);
        double d = signedDist(imp, r, x, y);
        return new double[]{x - d * f[2] / pw(imp), y - d * f[3] / ph(imp)};
    }

    static double pairDist(ImagePlus imp, double[] r, double x1, double y1, double x2, double y2) {
        double[] f = frame(imp, r);
        return (x2 - x1) * pw(imp) * f[2] + (y2 - y1) * ph(imp) * f[3];
    }

    /** End point of the perpendicular from point 1 to the line through point 2 parallel to ref. */
    static double[] pairFoot(ImagePlus imp, double[] r, double x1, double y1, double x2, double y2) {
        double[] f = frame(imp, r);
        double d = pairDist(imp, r, x1, y1, x2, y2);
        return new double[]{x1 + d * f[2] / pw(imp), y1 + d * f[3] / ph(imp)};
    }

    /** Angle of the reference line in degrees, counter-clockwise from the x axis (ImageJ convention). */
    static double refAngle(ImagePlus imp, double[] r) {
        double dx = (r[2] - r[0]) * pw(imp), dy = (r[3] - r[1]) * ph(imp);
        return Math.toDegrees(Math.atan2(-dy, dx)) + 0.0;
    }

    // ------------------------------------------------------------------ drawing

    static void redraw(ImagePlus imp) {
        State s = states.get(imp);
        Overlay ov = imp.getOverlay();
        if (ov == null) ov = new Overlay();
        for (int i = ov.size() - 1; i >= 0; i--) {
            String n = ov.get(i).getName();
            if (n != null && n.startsWith(TAG)) ov.remove(i);
        }
        if (s != null) {
            Color rc = ij.plugin.Colors.decode(refColor, Color.cyan);
            Color mc = ij.plugin.Colors.decode(measColor, Color.yellow);
            boolean stack = imp.getStackSize() > 1;

            if (s.ref != null) drawRef(imp, ov, s.ref, rc);
            // keep earlier reference lines visible for measurements made with them
            List<double[]> drawn = new ArrayList<double[]>();
            if (s.ref != null) drawn.add(s.ref);
            for (Meas m : s.meas) {
                boolean seen = false;
                for (double[] r : drawn) if (r == m.ref) { seen = true; break; }
                if (!seen) { drawRef(imp, ov, m.ref, rc); drawn.add(m.ref); }
            }
            if (s.refStart != null) {
                if (showPoints || s.preview == null)
                    ov.add(point(s.refStart[0], s.refStart[1], rc, TAG + "ref"));
                if (s.preview != null)
                    ov.add(line(s.refStart[0], s.refStart[1], s.preview[0], s.preview[1], rc,
                            TAG + "preview", true));
            }
            for (Meas m : s.meas) {
                List<Roi> rois = measRois(imp, m, mc, rc);
                for (Roi roi : rois) {
                    if (stack) roi.setPosition(m.slice);
                    ov.add(roi);
                }
            }
            if (s.pairStart != null && s.ref != null) {
                Line pl = parallelLine(imp, s.ref, s.pairStart[0], s.pairStart[1], mc, TAG + "pending", false);
                if (pl != null) ov.add(pl);
                if (showPoints) ov.add(point(s.pairStart[0], s.pairStart[1], mc, TAG + "pending"));
            }
            if (livePreview && s.preview != null && s.ref != null) {
                double px = s.preview[0], py = s.preview[1];
                double[] ft;
                if (mode == 1 && s.pairStart != null)
                    ft = pairFoot(imp, s.ref, s.pairStart[0], s.pairStart[1], px, py);
                else
                    ft = foot(imp, s.ref, px, py);
                double sx = (mode == 1 && s.pairStart != null) ? s.pairStart[0] : px;
                double sy = (mode == 1 && s.pairStart != null) ? s.pairStart[1] : py;
                ov.add(line(sx, sy, ft[0], ft[1], mc, TAG + "preview", true));
                // dashed parallel line following the cursor, to align with edges/features
                Line pl = parallelLine(imp, s.ref, px, py, mc, TAG + "preview", true);
                if (pl != null) ov.add(pl);
            }
        }
        imp.setOverlay(ov.size() > 0 ? ov : null);
    }

    static void drawRef(ImagePlus imp, Overlay ov, double[] r, Color c) {
        if (extendRef) {
            double[] e = clipToImage(r, imp.getWidth(), imp.getHeight());
            if (e != null) {
                Line l = line(e[0], e[1], e[2], e[3], c, TAG + "ref", false);
                ov.add(l);
            }
        } else {
            ov.add(line(r[0], r[1], r[2], r[3], c, TAG + "ref", false));
        }
        if (showPoints) {
            ov.add(point(r[0], r[1], c, TAG + "ref"));
            ov.add(point(r[2], r[3], c, TAG + "ref"));
        }
    }

    static List<Roi> measRois(ImagePlus imp, Meas m, Color mc, Color rc) {
        List<Roi> out = new ArrayList<Roi>();
        String name = TAG + "m" + m.id;
        double d;
        double[] ft;
        if (m.pair) {
            d = pairDist(imp, m.ref, m.x1, m.y1, m.x2, m.y2);
            ft = pairFoot(imp, m.ref, m.x1, m.y1, m.x2, m.y2);
            if (showParallel) {
                Line pl = parallelLine(imp, m.ref, m.x2, m.y2, mc, name, false);
                if (pl != null) out.add(pl);
            } else {
                out.add(line(ft[0], ft[1], m.x2, m.y2, mc, name, true));
            }
            if (showPoints) out.add(point(m.x2, m.y2, mc, name));
        } else {
            d = signedDist(imp, m.ref, m.x1, m.y1);
            ft = foot(imp, m.ref, m.x1, m.y1);
            if (!extendRef) {
                // if the foot lies outside the drawn segment, draw a dashed extension
                double[] f = frame(imp, m.ref);
                double len = Math.hypot((m.ref[2] - m.ref[0]) * pw(imp), (m.ref[3] - m.ref[1]) * ph(imp));
                double t = (ft[0] - m.ref[0]) * pw(imp) * f[0] + (ft[1] - m.ref[1]) * ph(imp) * f[1];
                if (t < 0) out.add(line(m.ref[0], m.ref[1], ft[0], ft[1], rc, name, true));
                else if (t > len) out.add(line(m.ref[2], m.ref[3], ft[0], ft[1], rc, name, true));
            }
        }
        out.add(line(m.x1, m.y1, ft[0], ft[1], mc, name, false));
        if (showParallel) {
            Line pl = parallelLine(imp, m.ref, m.x1, m.y1, mc, name, false);
            if (pl != null) out.add(pl);
        }
        if (showPoints) out.add(point(m.x1, m.y1, mc, name));

        if (rightAngle && Math.abs(d) > 0) {
            Roi ra = rightAngleMark(imp, m.ref, ft, m.x1, m.y1, Math.abs(d), mc, name);
            if (ra != null) out.add(ra);
        }
        if (showLabels) {
            String txt = (showIndex ? m.id + ": " : "") + fmt(signedDist ? d : Math.abs(d)) + " " + units(imp);
            Font font = new Font("SansSerif", Font.PLAIN, fontSize);
            double mx = (m.x1 + ft[0]) / 2.0, my = (m.y1 + ft[1]) / 2.0;
            // offset the label sideways (parallel to the reference line, towards the right)
            double[] f = frame(imp, m.ref);
            double sx = f[0], sy = f[1];
            if (sx < 0 || (sx == 0 && sy > 0)) { sx = -sx; sy = -sy; }
            double gap = fontSize * 0.5;
            double lx = mx + sx * gap;
            double ly = my + sy * gap - fontSize * (1 - sy) / 2.0;
            TextRoi t = new TextRoi(lx, ly, txt, font);
            t.setStrokeColor(mc);
            t.setAntiAlias(true);
            t.setName(name);
            out.add(t);
        }
        return out;
    }

    static Roi rightAngleMark(ImagePlus imp, double[] r, double[] ft, double px, double py,
                              double dist, Color c, String name) {
        double[] f = frame(imp, r);
        double pwv = pw(imp), phv = ph(imp);
        // marker size in calibrated units: ~1.2% of image diagonal, at most 40% of the distance
        double diag = Math.hypot(imp.getWidth() * pwv, imp.getHeight() * phv);
        double size = Math.min(diag * 0.012, dist * 0.4);
        if (size <= 0) return null;
        // normal pointing from foot towards the point
        double nx = (px - ft[0]) * pwv, ny = (py - ft[1]) * phv;
        double nl = Math.hypot(nx, ny);
        if (nl == 0) return null;
        nx /= nl; ny /= nl;
        double ux = f[0], uy = f[1];
        float[] xs = {
                (float) (ft[0] + size * ux / pwv),
                (float) (ft[0] + (size * ux + size * nx) / pwv),
                (float) (ft[0] + size * nx / pwv)};
        float[] ys = {
                (float) (ft[1] + size * uy / phv),
                (float) (ft[1] + (size * uy + size * ny) / phv),
                (float) (ft[1] + size * ny / phv)};
        PolygonRoi p = new PolygonRoi(new FloatPolygon(xs, ys), Roi.POLYLINE);
        p.setStrokeColor(c);
        p.setStrokeWidth(Math.max(1, lineWidth * 0.75));
        p.setName(name);
        return p;
    }

    static Line line(double x1, double y1, double x2, double y2, Color c, String name, boolean dashed) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStrokeColor(c);
        if (dashed) {
            l.setStroke(new BasicStroke((float) lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, 0f));
        } else {
            l.setStrokeWidth(lineWidth);
        }
        l.setName(name);
        return l;
    }

    /** Line parallel to the reference r passing through (x,y): full width or same length as r. */
    static Line parallelLine(ImagePlus imp, double[] r, double x, double y, Color c, String name,
                             boolean dashed) {
        double[] ft = foot(imp, r, x, y);
        double ox = x - ft[0], oy = y - ft[1];   // translation from the reference to the point
        double dx = r[2] - r[0], dy = r[3] - r[1];
        double tp = ((ft[0] - r[0]) * dx + (ft[1] - r[1]) * dy) / (dx * dx + dy * dy);
        double t0 = Math.min(0, tp), t1 = Math.max(1, tp);   // make sure the point is covered
        double[] q = {r[0] + t0 * dx + ox, r[1] + t0 * dy + oy, r[0] + t1 * dx + ox, r[1] + t1 * dy + oy};
        if (extendRef) {
            q = clipToImage(q, imp.getWidth(), imp.getHeight());
            if (q == null) return null;
        }
        return line(q[0], q[1], q[2], q[3], c, name, dashed);
    }

    static PointRoi point(double x, double y, Color c, String name) {
        PointRoi p = new PointRoi(x, y);
        p.setPointType(PointRoi.DOT);
        p.setSize(2);
        p.setStrokeColor(c);
        p.setFillColor(c);
        p.setName(name);
        return p;
    }

    /** Clip the infinite line through r to the rectangle [0,w]x[0,h]. */
    static double[] clipToImage(double[] r, int w, int h) {
        double dx = r[2] - r[0], dy = r[3] - r[1];
        double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {r[0], w - r[0], r[1], h - r[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) tmin = Math.max(tmin, t);
                else tmax = Math.min(tmax, t);
            }
        }
        if (tmin > tmax) return null;
        return new double[]{r[0] + tmin * dx, r[1] + tmin * dy, r[0] + tmax * dx, r[1] + tmax * dy};
    }

    // ------------------------------------------------------------------ helpers

    static String units(ImagePlus imp) {
        Calibration cal = imp.getCalibration();
        String u = cal.getUnit();
        if (u != null && (u.equals("micron") || u.equals("um") || u.equals("\u00B5m"))) return "\u00B5m";
        return cal.getUnits();
    }

    static String fmt(double v) {
        return IJ.d2s(v, decimals);
    }

    static void savePrefs() {
        Prefs.set(PREF + "mode", mode);
        Prefs.set(PREF + "refColor", refColor);
        Prefs.set(PREF + "measColor", measColor);
        Prefs.set(PREF + "lineWidth", lineWidth);
        Prefs.set(PREF + "fontSize", fontSize);
        Prefs.set(PREF + "decimals", decimals);
        Prefs.set(PREF + "showLabels", showLabels);
        Prefs.set(PREF + "showIndex", showIndex);
        Prefs.set(PREF + "extendRef", extendRef);
        Prefs.set(PREF + "livePreview", livePreview);
        Prefs.set(PREF + "rightAngle", rightAngle);
        Prefs.set(PREF + "signed", signedDist);
        Prefs.set(PREF + "showParallel", showParallel);
        Prefs.set(PREF + "showPoints", showPoints);
        Prefs.set(PREF + "detailedTable", detailedTable);
    }

    static String helpHtml() {
        return "<html><h3>" + TOOL_NAME + "</h3>"
                + "<ol><li>Click twice to define a reference line (any orientation).</li>"
                + "<li>Click anywhere to measure the perpendicular distance to that line.</li></ol>"
                + "<b>Point pair</b> mode: two clicks give their separation measured "
                + "perpendicular to the reference line (e.g. layer thickness).<br><br>"
                + "<b>Alt+click</b>: start a new reference line<br>"
                + "<b>Shift+click</b>: undo last measurement<br>"
                + "<b>Double-click tool icon</b>: these options<br><br>"
                + "Distances use the image calibration (Analyze &gt; Set Scale) and handle non-square pixels. "
                + "Results go to the '" + TABLE + "' table. Use Image &gt; Overlay &gt; Flatten "
                + "to burn the annotations into a copy of the image.</html>";
    }
}
