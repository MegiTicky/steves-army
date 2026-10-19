# Half-cover kneeling ADS study

Generate with `node tools/gen_gecko_kneel_review.mjs`. Load
`gecko_half_cover_kneel.bbmodel` in Blockbench, select Animate, then select one
clip at a time. The three clips lower, hold, and rise; the hold is static.
The reference block, wire box, eye line, and floor are excluded from runtime
geometry. The palette PNG accompanies the runtime geometry.

The preview uses world units: 16 pixels = 1 block. Source dimensions are
`SoldierEntity.getDimensions(CROUCHING)` (0.6 wide, 1.5 high) and
`getStandingEyeHeight(CROUCHING)` (1.27). The kneeling head, torso, and two-part
legs fit a box spanning X/Z +/-4.8 pixels and Y 0..24, with a deliberate
0.35-pixel rear-shin ground overlap. Arms and the weapon
extend forward while aiming, as held items do in Minecraft. There are no feet.
The torso leans forward 22 degrees and the head counter-rotates to stay level.
Both leg segments are restored to the original 6 pixels: there is no length
compression or animation scaling. The rear thigh folds forward 30 degrees;
its shin folds back horizontally. The raised thigh's hip is tucked 1.2 pixels
rearward into the lower torso. The head pivot is tucked 0.7 pixel lower and
1.1 pixels rearward into the collar, preserving head size. This moves the head
forward relative to the earlier 1.5-pixel tuck to follow the tilted neck more
closely. The weapon and
grip points move forward together by 0.65 pixel to retain eye relief.

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
The check also requires all four leg cubes to remain 6 pixels long.
The rear shin intentionally settles 0.35 pixel (about 0.022 block) into the
ground; the 101-frame transition sample permits at most 0.4 pixel. This is an
authored overlap for the silhouette, not exact collision solving. The front
shin remains planted and vertical. Numeric fit and optic alignment do not
replace human review of the tucked limbs and body proportions.

These files are review assets, not game resources. No Java renderer, hitbox,
or server eye height is changed. Runtime integration must use this world-space
calibration or compensate for it: the legacy vanilla soldier renderer applies
0.9375 scaling plus a crouch offset. Applying those on top of this rig would
invalidate its cover and eye measurements. Lower/rise clips include intermediate
heights above the sneak box; synchronize the entity pose/dimensions when wiring
them into gameplay. No in-game validation or deployment has occurred.
