/**
 * MeshSat vector tile styles for OpenMapTiles schema.
 * Dark and light themes matching the app's visual language.
 *
 * Used by Leaflet.VectorGrid to render .pbf vector tiles.
 * Layer names follow the OpenMapTiles specification:
 * https://openmaptiles.org/schema/
 */

var MeshSatMapStyle = {
    dark: {
        background: '#111827',
        water:        { fill: true, fillColor: '#1E3A5F', fillOpacity: 1, stroke: false, weight: 0 },
        waterway:     { color: '#1E3A5F', weight: 1, opacity: 0.8 },
        landcover:    { fill: true, fillColor: '#1A2332', fillOpacity: 0.6, stroke: false },
        landuse:      { fill: true, fillColor: '#1A2332', fillOpacity: 0.4, stroke: false },
        park:         { fill: true, fillColor: '#162B1F', fillOpacity: 0.5, stroke: false },
        boundary:     { color: '#374151', weight: 1, opacity: 0.6, dashArray: '4,4' },
        boundary_z5:  { color: '#4B5563', weight: 1.5, opacity: 0.7, dashArray: '6,3' },
        building:     { fill: true, fillColor: '#1F2937', fillOpacity: 0.7, color: '#374151', weight: 0.5 },
        road_major:   { color: '#4B5563', weight: 2, opacity: 0.9 },
        road_minor:   { color: '#374151', weight: 1, opacity: 0.7 },
        road_highway: { color: '#6B7280', weight: 2.5, opacity: 0.9 },
        rail:         { color: '#374151', weight: 1, opacity: 0.5, dashArray: '3,3' },
        aeroway:      { color: '#374151', weight: 1.5, opacity: 0.6 },
        label:        { color: '#9CA3AF', fontSize: 11, fontWeight: 'normal' },
        label_major:  { color: '#D1D5DB', fontSize: 13, fontWeight: 'bold' },
    },
    light: {
        background: '#F9FAFB',
        water:        { fill: true, fillColor: '#BFDBFE', fillOpacity: 1, stroke: false, weight: 0 },
        waterway:     { color: '#93C5FD', weight: 1, opacity: 0.8 },
        landcover:    { fill: true, fillColor: '#F0FDF4', fillOpacity: 0.5, stroke: false },
        landuse:      { fill: true, fillColor: '#F3F4F6', fillOpacity: 0.4, stroke: false },
        park:         { fill: true, fillColor: '#DCFCE7', fillOpacity: 0.5, stroke: false },
        boundary:     { color: '#D1D5DB', weight: 1, opacity: 0.6, dashArray: '4,4' },
        boundary_z5:  { color: '#9CA3AF', weight: 1.5, opacity: 0.7, dashArray: '6,3' },
        building:     { fill: true, fillColor: '#E5E7EB', fillOpacity: 0.7, color: '#D1D5DB', weight: 0.5 },
        road_major:   { color: '#D1D5DB', weight: 2, opacity: 0.9 },
        road_minor:   { color: '#E5E7EB', weight: 1, opacity: 0.7 },
        road_highway: { color: '#9CA3AF', weight: 2.5, opacity: 0.9 },
        rail:         { color: '#D1D5DB', weight: 1, opacity: 0.5, dashArray: '3,3' },
        aeroway:      { color: '#D1D5DB', weight: 1.5, opacity: 0.6 },
        label:        { color: '#6B7280', fontSize: 11, fontWeight: 'normal' },
        label_major:  { color: '#111827', fontSize: 13, fontWeight: 'bold' },
    }
};

/**
 * Build VectorGrid style object for the given theme.
 * Maps OpenMapTiles layer names to Leaflet path styles.
 */
function meshsatVectorStyle(theme) {
    var s = MeshSatMapStyle[theme] || MeshSatMapStyle.dark;

    return {
        water:          function(p, z) { return s.water; },
        waterway:       function(p, z) { return s.waterway; },
        landcover:      function(p, z) { return s.landcover; },
        landuse:        function(p, z) {
            if (p.class === 'park' || p.class === 'grass' || p.class === 'forest' || p.class === 'wood') return s.park;
            return s.landuse;
        },
        park:           function(p, z) { return s.park; },
        boundary:       function(p, z) {
            if (p.admin_level <= 4) return s.boundary_z5;
            return s.boundary;
        },
        building:       function(p, z) { return z >= 14 ? s.building : []; },
        aeroway:        function(p, z) { return s.aeroway; },
        transportation: function(p, z) {
            if (p.class === 'motorway' || p.class === 'trunk' || p.class === 'primary') return s.road_highway;
            if (p.class === 'secondary' || p.class === 'tertiary') return s.road_major;
            if (p.class === 'rail') return s.rail;
            if (p.class === 'minor' || p.class === 'service' || p.class === 'track' || p.class === 'path') {
                return z >= 12 ? s.road_minor : [];
            }
            return s.road_minor;
        },
        transportation_name: function(p, z) { return []; },
        place:          function(p, z) { return []; },
        housenumber:    function(p, z) { return []; },
        poi:            function(p, z) { return []; },
        mountain_peak:  function(p, z) { return []; },
    };
}
