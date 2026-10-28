#!/usr/bin/env sh
# Builds dist/Perpendicular_Distance_Tool.jar
# Usage: ./build.sh /path/to/Fiji.app/jars/ij-1.54p.jar   (or set IJ_JAR)
set -e
IJ="${1:-$IJ_JAR}"
if [ -z "$IJ" ] || [ ! -f "$IJ" ]; then
  echo "Give the path to ij.jar, e.g. ./build.sh ~/Fiji.app/jars/ij-1.54p.jar"
  exit 1
fi
mkdir -p build dist
javac -encoding UTF-8 --release 8 -cp "$IJ" -d build src/Perpendicular_Distance_Tool.java
cp src/plugins.config build/
(cd build && jar cf ../dist/Perpendicular_Distance_Tool.jar .)
echo "Built dist/Perpendicular_Distance_Tool.jar - copy it into Fiji.app/plugins/ and restart Fiji."
