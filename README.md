# Microscope Measurement Tool – Perpendicular Distance Tool

An ImageJ / Fiji plugin (tested with ImageJ 1.54p, Fiji) for measuring distances **perpendicular to a reference line of any orientation**. You set the line with two clicks, then measure how far features are from it, or how far apart two features are in the direction perpendicular to it (for example, layer thickness or stripe spacing).

It was inspired by the [Microscope Measurement Tools](https://imagej.net/plugins/microscope-measurement-tools) plugin.

## Features

- **Reference line:** two clicks define it, at any angle. It can be drawn across the whole image.
- **Point to line:** each click measures the perpendicular distance from that point to the reference line.
- **Point pair:** two clicks measure their separation perpendicular to the reference line.
- **Drawing on the image:** each measurement draws a line parallel to the reference through the point, the perpendicular line, a right-angle mark and a distance label. Clicked points get no dots, so nothing hides the feature you are aiming at.
- **Live preview:** a dashed parallel line follows the mouse, so you can line it up with edges before clicking.
- **Units:** results use the image calibration (Analyze › Set Scale) and are correct for non-square pixels.
- **Results table:** "Perpendicular Distances" with the columns Image, No., Mode, Distance, Units and Ref angle. Coordinate columns can be added in the options.
- **Non-destructive:** everything is drawn on top of the image. Use Image › Overlay › Flatten to burn it into a copy.

## Installation

1. Download `Perpendicular_Distance_Tool.jar` from the [Releases](../../releases) page.
2. Copy it into `Fiji.app/plugins/`.
3. Restart Fiji.
4. Choose **Plugins › Measurement › Perpendicular Distance Tool**. The tool appears on the toolbar.

Plugin tools are removed from the toolbar when Fiji restarts. To load it every time, add `run("Perpendicular Distance Tool");` to Edit › Options › Startup…

## Usage

| Action | What it does |
|---|---|
| Click, click | Set the reference line |
| Click | Measure a point (or the first or second point of a pair) |
| Alt + click | Start a new reference line |
| Shift + click | Undo the last measurement |
| Double-click the tool icon | Options: mode, colours, line width, labels, table columns, use the current straight-line selection as the reference, clear |

## Building from source

You need a JDK (8 or newer). Fiji includes one in `Fiji.app/java/`.

```sh
./build.sh /path/to/Fiji.app/jars/ij-1.54p.jar          # Linux / macOS
build.bat "C:\path\to\Fiji.app\jars\ij-1.54p.jar"       # Windows
```

The plugin is written to `dist/Perpendicular_Distance_Tool.jar`.

## Authors

- **Justinas Jorudas**
- **Claude** (Anthropic), AI assistant who co-developed the code

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
