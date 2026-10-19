# Half-cover kneeling ADS study

Generate with `node tools/gen_gecko_kneel_review.mjs`. Load
`gecko_half_cover_kneel.bbmodel` in Blockbench, select Animate, then select one
clip at a time. The three clips lower, hold, and rise; the hold is static.
The reference block, wire box, eye line, and floor are excluded from runtime
geometry. The palette PNG accompanies the runtime geometry.

The preview uses world units: 16 pixels = 1 block. Source dimensions are
`SoldierEntity.getDimensions(CROUCHING)` (0.6 wide, 1.5 high) and
`getStandingEyeHeight(CROUCHING)` (1.27). The kneeling head, torso, and two-part
legs fit a box spanning X/Z +/-4.8 pixels and Y 0..24. Arms and the weapon
extend forward while aiming, as held items do in Minecraft. There are no feet.
The torso leans forward 10 degrees and the head counter-rotates to stay level.
Both leg segments are 4.45 pixels, shortened to fit the fixed sneak box.

The dominant eye and optic axis share X=-0.8 and Y=20.32 pixels at the kneeling
endpoint. The optic sits in front of that eye; the receiver bottom is 2 pixels
above the 16-pixel cover. The weapon is a geometric stand-in, including an
offset stock beside the cheek. Real TaCZ optics/grip transforms still need
per-weapon integration. A weapon without an optic needs iron-sight alignment.

Load the project through `tools/load_blockbench_review.mjs`, then run
`node tools/inspect_gecko_kneel.mjs` to inspect the live Blockbench scene and
capture side, front, and three-quarter views. Checks compare exported bone
pivots/cube transforms and animation keys, sample limb contact through the
lowering transition, and measure the final sneak bounds and eye/optic axis.
Linear interpolation allows about 0.024 pixel of ground penetration between
sampled keys; this is a known preview tolerance, not exact collision solving.

These files are review assets, not game resources. No Java renderer, hitbox,
or server eye height is changed. Runtime integration must use this world-space
calibration or compensate for it: the legacy vanilla soldier renderer applies
0.9375 scaling plus a crouch offset. Applying those on top of this rig would
invalidate its cover and eye measurements. Lower/rise clips include intermediate
heights above the sneak box; synchronize the entity pose/dimensions when wiring
them into gameplay. No in-game validation or deployment has occurred.
