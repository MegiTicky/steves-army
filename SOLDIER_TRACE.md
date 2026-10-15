# Soldier flight recorder

The flight recorder captures a bounded timeline for loaded soldiers across all
dimensions, subject to a 128 MiB estimated memory budget. Each exported bundle
contains the selected soldier's latest dimension segment. Recording remains
active while history is exported. Stopping freezes retained history for export;
starting again after stopping begins a fresh history window.

## Commands

- `/stevesarmy_debug record start [seconds] [sampleTicks]` starts recording.
  Defaults are 60 wall-clock seconds and 5 game ticks per sample. History is
  clamped to 10-60 seconds and the sample interval to 1-20 ticks. Starting
  while already recording preserves the existing history.
- `/stevesarmy_debug record status` reports recording state, time coverage,
  sample interval, and retained actors/events.
- `/stevesarmy_debug record export` selects the soldier under the crosshair within
  64 blocks, respecting intervening blocks.
- `/stevesarmy_debug record export entity <entity>` exports a specified soldier entity.
- `/stevesarmy_debug record export uuid <uuid>` exports a specified soldier by UUID.
  The bundle includes the selected actor and relevant nearby actors.
- `/stevesarmy_debug record stop` stops sampling and retains the frozen
  history for export.

Each export creates a unique directory under
`debug/soldier_traces/<unique>/` containing `trace.json`, `viewer.html`, and
`summary.txt`. The HTML viewer runs without external libraries or a web server.
It supports timestamp-based scrubbing and playback, actor selection, map pan
and zoom, Y-layer inspection, terrain visibility, event browsing, and complete
frame snapshot inspection. The map uses a top-down X/Z view. Terrain is rendered
at the selected Y layer to reduce occlusion from roofs and upper floors.

## Reading the output

The JSON bundle is the source of truth. `timeMs` values are relative to the
recorder's monotonic session clock, not calendar time or game ticks. Frames contain full combat
state snapshots, movement decisions, navigation state, position and velocity,
health, and equipment-related context. Path geometry is attached to
`navigation-path` events under `data.path`; match `data.pathId` to the frame's
`navigation.pathId`. Node indices in frames locate the soldier along that route.
Event `repeat` values represent coalesced duplicate events. Recorder findings
are heuristic signals; inspect the underlying frames and events before drawing
a conclusion. At the rolling history boundary, the route geometry event can be
evicted before its referencing frames. Geometry is refreshed at most every five
seconds, so a path overlay may be absent there even while the frame still has
its navigation target, path ID, and next-node index.

The text summary flags long state holds, same-target intervals, and repeated
movement or navigation failure signals. These are heuristics for finding an
interval to inspect; they do not identify a root cause. In particular, a stable
target does not prove the soldier was stationary, and a repeated failure may be
expected under combat pressure or constrained terrain.

## Terrain and limits

At export, terrain capture covers loaded chunks intersecting the selected
actor's plus/minus 64-block X/Z bounds, across the full build height. Chunk
edges can extend slightly beyond those bounds. It records palette-compressed
block states, capture start and finish times, and unloaded chunks. Capture is
incremental and stops at a 32 MiB raw terrain-index limit. Export does not force
chunks to load. The terrain snapshot is taken at export time, not
historically at every frame. Block entities and dynamic changes between frame
capture and export may therefore be absent or differ from the original scene.

The recorder only observes loaded soldiers and available server-side state at
its configured sample interval. It cannot recover sub-sample transitions,
unloaded actors, client-only presentation, or a complete world save. Use the
manual reconstruction checklist in `summary.txt` to reproduce suspicious
intervals in a simulator and vary one condition at a time.
