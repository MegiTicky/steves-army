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

The review placeholder now has a dropped, diagonally offset stock terminating
at the leaned shoulder instead of a high sideways connector. Blockbench world
bounding boxes for all three stock pieces do not intersect the head box.
Eye/optic alignment, two-pixel receiver clearance, full six-pixel leg segments,
and export parity still pass. Viewed the three-quarter screenshot. This custom
stock shape demonstrates clearance only; it does not prove a normal straight
TaCZ stock fits this unchanged head/shoulder pose. No runtime integration or
deployment was performed.
