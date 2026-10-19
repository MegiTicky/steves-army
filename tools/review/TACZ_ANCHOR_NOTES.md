# TaCZ fit investigation

Inspected local TaCZ 1.1.8-hotfix source, not online research.
`GunModelConstant` defines `thirdperson_hand`, `iron_view`, `lefthand_pos`
and `righthand_pos`. The latter two are first-person arm nodes, not guaranteed
third-person wrist contact points. `FirstPersonRenderGunEvent` composes the
gun scope-position path and attachment scope-view path for the selected view,
or uses the iron-sight path without a scope. These transforms need conversion
into the soldier renderer's coordinate system and third-person scale.

Proposed integration: resolve the equipped gun and active attachment transforms,
place the gun using the sight/eye frame, solve two-segment arms to grip targets,
and adjust head/shoulder pose within bounds. Cache profiles by gun/attachments
and invalidate on resource reload. A standard stock-butt contact anchor was not
found in the inspected constants; use resource-pack overrides for stock/grips
and fallback poses when anchors are absent. Arbitrary gun packs cannot be
promised perfect anatomical fit from the hand anchor alone.

The dropped-stock workaround was rejected by the user and removed. The review
now uses an inline stock and shifts/cants the whole rifle 8 degrees, with the
hands following it. Head has 8-degree roll and slight forward pitch. Scope axis
is approximately 3.19 pixels from the eye in the lateral/vertical plane; exact ADS
alignment is deliberately relaxed. Receiver clearance is 1.89 pixels. Grid
sampling of both stock pieces found no points inside the oriented head box;
this sampled check is not a formal intersection proof. Legs remain six pixels
per segment. Core body bounds fit the sneak envelope (with authored ground tuck).

Simple Enemy reference inspected locally:
`entity/client/util/UnitModelDefinitions.java` uses fixed 4x12x4 arms, an
asymmetric rifle hold, and a `rightHandAnchor` at (-1,9,0) in arm coordinates.
`animation/procedural/AdvancedWeaponPoseLayer.java` follows head yaw/pitch
at 0.65/0.85 for rifles, with separate pistol/heavy poses. Its rig has rigid
arms, not elbow IK. Our preview takes the fixed-proportion/asymmetric approach
without copying their geometry or animation: both arms now have six-pixel upper
and lower segments, solved to reachable grip targets, with support grip closer
to the receiver. Inspection asserts all four arm lengths and joint continuity.
Viewed the uncovered front three-quarter preview. This is a pose revision for
human review, not an assertion of finished anatomy or runtime compatibility.
No runtime integration or deployment was performed.
