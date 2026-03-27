#!/bin/bash
# Generate a z0-z5 world vector MBTiles from OpenMapTiles for bundling in the app.
#
# Prerequisites:
#   pip install openmaptiles-tools  OR  docker pull openmaptiles/openmaptiles-tools
#   pip install mbutil
#
# The simplest approach is to download a pre-built planet MBTiles and extract z0-z5:
#
#   1. Download planet.mbtiles from https://data.maptiler.com/downloads/tileset/osm/
#      (free account required, ~80GB for full planet)
#
#   2. Extract z0-z5 tiles only:
#      python3 -c "
#      import sqlite3, sys
#      src = sqlite3.connect('planet.mbtiles')
#      dst = sqlite3.connect('world.mbtiles')
#      dst.execute('CREATE TABLE metadata (name TEXT, value TEXT)')
#      dst.execute('CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)')
#      dst.execute('CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)')
#      for row in src.execute('SELECT * FROM metadata'):
#          if row[0] in ('minzoom',): dst.execute('INSERT INTO metadata VALUES (?,?)', ('minzoom', '0'))
#          elif row[0] in ('maxzoom',): dst.execute('INSERT INTO metadata VALUES (?,?)', ('maxzoom', '5'))
#          else: dst.execute('INSERT INTO metadata VALUES (?,?)', row)
#      count = 0
#      for row in src.execute('SELECT * FROM tiles WHERE zoom_level <= 5'):
#          dst.execute('INSERT INTO tiles VALUES (?,?,?,?)', row)
#          count += 1
#      dst.commit()
#      print(f'Extracted {count} tiles')
#      "
#
#   3. Copy to assets:
#      cp world.mbtiles app/src/main/assets/world.mbtiles
#
# Expected size: ~12-15 MB for z0-z5 vector tiles of the whole planet.
#
# Alternative: use tilelive or tippecanoe to generate from .osm.pbf files.

echo "See comments in this script for generation instructions."
echo "The placeholder world.mbtiles in assets/ has the correct schema but no tile data."
echo "Replace it with a real z0-z5 OpenMapTiles vector MBTiles file."
