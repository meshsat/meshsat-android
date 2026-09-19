package net.meshsat.android.ui.theme

import androidx.compose.ui.graphics.Color

// The MeshSat brand (meshsat-website/brand/, MeshSat_Brand_Guide.pdf page 6) and the Bridge web UI
// (meshsat/web/tailwind.config.js), in one palette (MESHSAT-1249). Dark only: the brand uses the dark
// version by default and the Bridge has no light mode.
//
// The old token names are kept and repainted, as the Bridge re-skin did with its Tailwind scales, so
// every screen takes the brand without an edit. Text tokens are chosen for WCAG AA: muted text is at
// least 4.5:1 on every surface, and nothing is ever drawn in white on Signal Orange (2.9:1).

// Brand guide colours
val SpaceBlack = Color(0xFF040406)
val SignalOrange = Color(0xFFF96118)
val OffWhite = Color(0xFFF7F7F4)

// Surfaces and text (the Bridge's warm near-black scale)
val MeshSatBg = SpaceBlack                     // page background
val MeshSatSurface = Color(0xFF15151B)         // cards, bars, sheets
val MeshSatSurfaceLight = Color(0xFF24242C)    // raised: pressed rows, inputs
val MeshSatBorder = Color(0xFF24242C)          // hairlines and card borders
val MeshSatTextPrimary = Color(0xFFEBEBEE)     // body text, 15.3:1 on surface
val MeshSatTextSecondary = Color(0xFFB4B4BD)   // secondary text, 8.8:1
val MeshSatTextMuted = Color(0xFF8A8A96)       // captions and hints, 5.3:1 (4.5:1 on raised)

// The accent is Signal Orange: the main action and anything live. Text on it is Space Black (6.6:1).
val MeshSatTeal = SignalOrange
val MeshSatTealLight = Color(0xFFFF7C3B)       // orange text on raised surfaces
val MeshSatInk = SpaceBlack                    // text and icons drawn on Signal Orange

// State colours are functional only: working, trying, failed.
val MeshSatGreen = Color(0xFF34D399)
val MeshSatAmber = Color(0xFFFBBF24)
val MeshSatRed = Color(0xFFF87171)
val MeshSatBlue = Color(0xFF8FB8DE)            // the Hub's colour; kept for existing uses

// Signal quality, from the state colours
val SignalExcellent = MeshSatGreen
val SignalGood = MeshSatGreen
val SignalFair = MeshSatAmber
val SignalPoor = MeshSatRed

// One colour per way a message travels, from the Bridge booth screen's route lanes (TtcView.vue):
// designed to read apart from each other and from the state colours.
val ColorIridium = Color(0xFFB9A7E6)           // satellite, lavender
val ColorMesh = Color(0xFFC8B89A)              // mesh (LoRa), sand
val ColorCellular = Color(0xFFE0B458)          // SMS and cellular, gold
val ColorSMS = ColorCellular
val ColorHub = Color(0xFF8FB8DE)               // the Hub, blue
val ColorRadio = OffWhite                      // ham radio (APRS)
