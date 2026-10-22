# Project behavior memory

## 2026-10-15: MG ATTACK rear-support experiment rolled back

User decision: restore MG attack movement from before f499c78, but KEEP continuous ATTACK suppression. Firing access and keeping up with the squad matter more than rear spacing. Do not reintroduce a mandatory rear slot or longer MG dwell/steps without a new request.

Attempt history: f499c78 added a preferred 9-block rear slot, 8-second minimum dock dwell, longer steps, and sustained suppression together. User repeatedly observed MGs 30+ blocks behind with no useful firing lane. Trace 20261015_134502_931_0e8cb36e showed a stationary MG for 57 seconds. The signed squad-to-objective projection reversed after rifles crossed the objective; distant search anchors/corridor restrictions also blocked useful movement. bbf379e added recovery and passed focused synthetic tests (crossed fixture gap 50.33 -> 29.14 and accepted shots), but the user still found the resulting behavior much less useful. Passing those tests did not establish satisfactory live gameplay.

Rollback: remove the rear-support movement policy and its compensating recovery rules; restore prior cover selection, anchors, movement permissions, dwell and step behavior. Preserve pre-existing MG support safeguards, sustained suppression, later aiming improvements, and unrelated work. Keep physical movement/shot regression fixtures independent of the removed policy.

Known separate issue: mg_persistent_attack_suppression failed on both the pre-recovery and recovery snapshots in the earlier investigation; this rollback does not claim to repair that failure. Minimal focused verification only; live evaluation remains necessary.

Validation: isolated staged-source Java17 build SUCCESS; focused scenarios 3 PASS / 0 FAIL. Crossed gap 50.33 -> 29.11 with 91 shots by tick1200; aligned gap35 ->18.39 with95 shots. Both remained stationary at tick400, so this is a rollback, not proof of prompt catch-up. Reports retain FireDeadlock/rotation/danger warnings. Direct-fire cadence passed. Log: Debug/mg-rollback-validation.log. Test deployment follows the committed-source packaging check; see agents-mailbox.md for artifact hash.
