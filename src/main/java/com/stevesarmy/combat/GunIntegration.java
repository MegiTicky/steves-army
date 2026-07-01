package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.lang.reflect.Method;
import java.util.Optional;

public class GunIntegration {
    private static boolean taczLoaded = false;
    private static GunHandler gunHandler = new FallbackGunHandler();

    public static void init() {
        try {
            Class.forName("com.tacz.guns.api.entity.IGunOperator");
            Class.forName("com.tacz.guns.api.item.IGun");
            Class.forName("com.tacz.guns.api.TimelessAPI");
            
            taczLoaded = true;
            gunHandler = new ReflectionGunHandler();
            StevesArmyMod.LOGGER.info("TaCZ detected - enabling gun integration");
        } catch (ClassNotFoundException e) {
            StevesArmyMod.LOGGER.info("TaCZ not detected - soldiers will use melee combat");
        }
    }

    public static boolean isTaczLoaded() { return taczLoaded; }
    public static boolean hasGun(LivingEntity entity) { return gunHandler.hasGun(entity); }
    public static ShootResult shoot(LivingEntity shooter, LivingEntity target) { return gunHandler.shoot(shooter, target); }
    public static ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation) { return gunHandler.shootWithDeviation(shooter, aimPoint, pitchDeviation, yawDeviation); }
    public static ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) { return gunHandler.shootAtPosition(shooter, targetPosition); }
    public static void reload(LivingEntity entity) { gunHandler.reload(entity); }
    public static void bolt(LivingEntity entity) { gunHandler.bolt(entity); }
    public static void aim(LivingEntity entity, boolean isAiming) { gunHandler.aim(entity, isAiming); }
    public static boolean isBolting(LivingEntity entity) { return gunHandler.isBolting(entity); }
    public static boolean isReloading(LivingEntity entity) { return gunHandler.isReloading(entity); }
    public static float getAimProgress(LivingEntity entity) { return gunHandler.getAimProgress(entity); }
    public static long getShootCoolDown(LivingEntity entity) { return gunHandler.getShootCoolDown(entity); }
    public static boolean isDrawing(LivingEntity entity) { return gunHandler.isDrawing(entity); }
    public static double getEffectiveRange(LivingEntity entity) { return gunHandler.getEffectiveRange(entity); }
    public static Optional<ItemStack> getGunStack(LivingEntity entity) { return gunHandler.getGunStack(entity); }
    public static void initialData(LivingEntity entity) { gunHandler.initialData(entity); }
    public static void draw(LivingEntity entity) { gunHandler.draw(entity); }
    public static int getMagazineSize(LivingEntity entity) { return gunHandler.getMagazineSize(entity); }
    public static int getCurrentAmmo(LivingEntity entity) { return gunHandler.getCurrentAmmo(entity); }
    public static boolean hasAmmoInBarrel(LivingEntity entity) { return gunHandler.hasAmmoInBarrel(entity); }
    public static boolean isManualBolt(LivingEntity entity) { return gunHandler.isManualBolt(entity); }
    public static boolean useInventoryAmmo(LivingEntity entity) { return gunHandler.useInventoryAmmo(entity); }
    public static String getGunId(LivingEntity entity) { return gunHandler.getGunId(entity); }
    public static String getAmmoId(LivingEntity entity) { return gunHandler.getAmmoId(entity); }
    public static void crawl(LivingEntity entity, boolean isCrawl) { gunHandler.crawl(entity, isCrawl); }
    public static boolean isCrawling(LivingEntity entity) { return gunHandler.isCrawling(entity); }

    public enum ShootResult {
        SUCCESS, NO_AMMO, COOLDOWN, NOT_GUN, NO_TARGET, OUT_OF_RANGE,
        NEED_BOLT, IS_BOLTING, IS_RELOADING, IS_DRAWING, NOT_DRAWN, 
        PATH_BLOCKED, UNKNOWN
    }

    public interface GunHandler {
        boolean hasGun(LivingEntity entity);
        ShootResult shoot(LivingEntity shooter, LivingEntity target);
        ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation);
        ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition);
        void reload(LivingEntity entity);
        void bolt(LivingEntity entity);
        void aim(LivingEntity entity, boolean isAiming);
        boolean isBolting(LivingEntity entity);
        boolean isReloading(LivingEntity entity);
        float getAimProgress(LivingEntity entity);
        long getShootCoolDown(LivingEntity entity);
        boolean isDrawing(LivingEntity entity);
        double getEffectiveRange(LivingEntity entity);
        Optional<ItemStack> getGunStack(LivingEntity entity);
        void initialData(LivingEntity entity);
        void draw(LivingEntity entity);
        int getMagazineSize(LivingEntity entity);
        int getCurrentAmmo(LivingEntity entity);
        boolean hasAmmoInBarrel(LivingEntity entity);
        boolean isManualBolt(LivingEntity entity);
        boolean useInventoryAmmo(LivingEntity entity);
        String getGunId(LivingEntity entity);
        String getAmmoId(LivingEntity entity);
        void crawl(LivingEntity entity, boolean isCrawl);
        boolean isCrawling(LivingEntity entity);
    }

    private static class FallbackGunHandler implements GunHandler {
        @Override public boolean hasGun(LivingEntity entity) { return false; }
        @Override public ShootResult shoot(LivingEntity shooter, LivingEntity target) { return ShootResult.NOT_GUN; }
        @Override public ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation) { return ShootResult.NOT_GUN; }
        @Override public ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) { return ShootResult.NOT_GUN; }
        @Override public void reload(LivingEntity entity) {}
        @Override public void bolt(LivingEntity entity) {}
        @Override public void aim(LivingEntity entity, boolean isAiming) {}
        @Override public boolean isBolting(LivingEntity entity) { return false; }
        @Override public boolean isReloading(LivingEntity entity) { return false; }
        @Override public float getAimProgress(LivingEntity entity) { return 0; }
        @Override public long getShootCoolDown(LivingEntity entity) { return 0; }
        @Override public boolean isDrawing(LivingEntity entity) { return false; }
        @Override public double getEffectiveRange(LivingEntity entity) { return 3.0; }
        @Override public Optional<ItemStack> getGunStack(LivingEntity entity) { return Optional.empty(); }
        @Override public void initialData(LivingEntity entity) {}
        @Override public void draw(LivingEntity entity) {}
        @Override public int getMagazineSize(LivingEntity entity) { return 0; }
        @Override public int getCurrentAmmo(LivingEntity entity) { return 0; }
        @Override public boolean hasAmmoInBarrel(LivingEntity entity) { return false; }
        @Override public boolean isManualBolt(LivingEntity entity) { return false; }
        @Override public boolean useInventoryAmmo(LivingEntity entity) { return false; }
        @Override public String getGunId(LivingEntity entity) { return ""; }
        @Override public String getAmmoId(LivingEntity entity) { return ""; }
        @Override public void crawl(LivingEntity entity, boolean isCrawl) {}
        @Override public boolean isCrawling(LivingEntity entity) { return false; }
    }

    private static class ReflectionGunHandler implements GunHandler {
        private static final double DEFAULT_GUN_RANGE = 50.0;

        @Override
        public boolean hasGun(LivingEntity entity) {
            try {
                Class<?> gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
                Method mainHandHoldGun = gunInterface.getMethod("mainHandHoldGun", LivingEntity.class);
                return (boolean) mainHandHoldGun.invoke(null, entity);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public ShootResult shoot(LivingEntity shooter, LivingEntity target) {
            if (!hasGun(shooter)) {
                StevesArmyMod.LOGGER.info("[TaCZ] shoot() - No gun");
                return ShootResult.NOT_GUN;
            }
            if (target == null || !target.isAlive()) {
                StevesArmyMod.LOGGER.info("[TaCZ] shoot() - No target");
                return ShootResult.NO_TARGET;
            }

            try {
                ItemStack gunStack = shooter.getMainHandItem();
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, shooter);

                double dx = target.getX() - shooter.getX();
                double dy = target.getEyeY() - shooter.getEyeY();
                double dz = target.getZ() - shooter.getZ();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
                float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

                long cooldown = getShootCoolDown(shooter);
                int ammo = getCurrentAmmo(shooter);
                boolean barrelAmmo = hasAmmoInBarrel(shooter);
                String gunId = getGunId(shooter);
                String ammoId = getAmmoId(shooter);
                boolean useInvAmmo = useInventoryAmmo(shooter);
                
                StevesArmyMod.LOGGER.info("[TaCZ] Pre-shoot: gun={}, ammoId={}, useInvAmmo={}, cooldown={}ms, ammo={}, barrel={}", 
                    gunId, ammoId, useInvAmmo, cooldown, ammo, barrelAmmo);

                Method shootMethod = gunOperatorClass.getMethod("shoot", java.util.function.Supplier.class, java.util.function.Supplier.class);
                Object result = shootMethod.invoke(gunOperator, 
                    (java.util.function.Supplier<Float>) () -> pitch, 
                    (java.util.function.Supplier<Float>) () -> yaw);
                
                String resultName = result.toString();
                
                ammo = getCurrentAmmo(shooter);
                barrelAmmo = hasAmmoInBarrel(shooter);
                cooldown = getShootCoolDown(shooter);
                StevesArmyMod.LOGGER.info("[TaCZ] Post-shoot: result={}, cooldown={}ms, ammo={}, barrel={}", 
                    resultName, cooldown, ammo, barrelAmmo);
                
                return mapShootResult(resultName);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[TaCZ] Shoot failed: " + e.getMessage());
                e.printStackTrace();
                return ShootResult.UNKNOWN;
            }
        }

        @Override
        public ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation) {
            if (!hasGun(shooter)) {
                StevesArmyMod.LOGGER.info("[TaCZ] shootWithDeviation() - No gun");
                return ShootResult.NOT_GUN;
            }
            
            if (!aimPoint.canShoot()) {
                StevesArmyMod.LOGGER.info("[TaCZ] shootWithDeviation() - Path blocked, aimPoint={}, bulletPathClear={}", 
                    aimPoint.type.displayName, aimPoint.bulletPathClear);
                return ShootResult.PATH_BLOCKED;
            }

            try {
                ItemStack gunStack = shooter.getMainHandItem();
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, shooter);

                Vec3 aimPosition = aimPoint.position;
                
                double dx = aimPosition.x - shooter.getX();
                double dy = aimPosition.y - shooter.getEyeY();
                double dz = aimPosition.z - shooter.getZ();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                float basePitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
                float baseYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                
                float pitch = basePitch + pitchDeviation;
                float yaw = baseYaw + yawDeviation;

                String gunId = getGunId(shooter);
                int ammo = getCurrentAmmo(shooter);
                boolean barrelAmmo = hasAmmoInBarrel(shooter);
                
                StevesArmyMod.LOGGER.info("[TaCZ] Pre-shoot: gun={}, aimPoint={}, ammo={}, barrel={}", 
                    gunId, aimPoint.type.displayName, ammo, barrelAmmo);

                Method shootMethod = gunOperatorClass.getMethod("shoot", java.util.function.Supplier.class, java.util.function.Supplier.class);
                Object result = shootMethod.invoke(gunOperator, 
                    (java.util.function.Supplier<Float>) () -> pitch, 
                    (java.util.function.Supplier<Float>) () -> yaw);
                
                String resultName = result.toString();
                
                ammo = getCurrentAmmo(shooter);
                barrelAmmo = hasAmmoInBarrel(shooter);
                StevesArmyMod.LOGGER.info("[TaCZ] Post-shoot: result={}, ammo={}, barrel={}", 
                    resultName, ammo, barrelAmmo);
                
                return mapShootResult(resultName);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[TaCZ] Shoot with deviation failed: " + e.getMessage());
                e.printStackTrace();
                return ShootResult.UNKNOWN;
            }
        }

        @Override
        public ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) {
            if (!hasGun(shooter)) {
                return ShootResult.NOT_GUN;
            }

            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, shooter);

                double dx = targetPosition.x - shooter.getX();
                double dy = targetPosition.y - shooter.getEyeY();
                double dz = targetPosition.z - shooter.getZ();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
                float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

                Method shootMethod = gunOperatorClass.getMethod("shoot", java.util.function.Supplier.class, java.util.function.Supplier.class);
                Object result = shootMethod.invoke(gunOperator, 
                    (java.util.function.Supplier<Float>) () -> pitch, 
                    (java.util.function.Supplier<Float>) () -> yaw);
                
                return mapShootResult(result.toString());
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[TaCZ] shootAtPosition failed: " + e.getMessage());
                return ShootResult.UNKNOWN;
            }
        }

        private ShootResult mapShootResult(String resultName) {
            if (resultName.contains("SUCCESS")) return ShootResult.SUCCESS;
            if (resultName.contains("NO_AMMO")) return ShootResult.NO_AMMO;
            if (resultName.contains("COOL_DOWN")) return ShootResult.COOLDOWN;
            if (resultName.contains("NEED_BOLT")) return ShootResult.NEED_BOLT;
            if (resultName.contains("IS_BOLTING")) return ShootResult.IS_BOLTING;
            if (resultName.contains("IS_RELOADING")) return ShootResult.IS_RELOADING;
            if (resultName.contains("IS_DRAWING")) return ShootResult.IS_DRAWING;
            if (resultName.contains("NOT_DRAW")) return ShootResult.NOT_DRAWN;
            if (resultName.contains("NOT_GUN")) return ShootResult.NOT_GUN;
            return ShootResult.UNKNOWN;
        }

        @Override
        public void reload(LivingEntity entity) {
            if (!hasGun(entity)) return;
            try {
                String gunId = getGunId(entity);
                String ammoId = getAmmoId(entity);
                boolean useInvAmmo = useInventoryAmmo(entity);
                int currentAmmo = getCurrentAmmo(entity);
                int magSize = getMagazineSize(entity);
                
                StevesArmyMod.LOGGER.info("[TaCZ] reload() called for gun={} ammoId={} useInvAmmo={} currentAmmo={}/{}", 
                    gunId, ammoId, useInvAmmo, currentAmmo, magSize);
                
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                
                Method needCheckAmmoMethod = gunOperatorClass.getMethod("needCheckAmmo");
                boolean needCheckAmmo = (boolean) needCheckAmmoMethod.invoke(gunOperator);
                StevesArmyMod.LOGGER.info("[TaCZ] needCheckAmmo={}", needCheckAmmo);
                
                long drawCoolDown = isDrawing(entity) ? 1 : 0;
                long shootCoolDown = getShootCoolDown(entity);
                boolean bolting = isBolting(entity);
                boolean reloading = isReloading(entity);
                StevesArmyMod.LOGGER.info("[TaCZ] State checks: drawCoolDown={} shootCoolDown={} isBolting={} isReloading={}", 
                    drawCoolDown, shootCoolDown, bolting, reloading);
                
                if (needCheckAmmo) {
                    StevesArmyMod.LOGGER.info("[TaCZ] Checking inventory for ammo items...");
                    ItemStack gunStack = entity.getMainHandItem();
                    try {
                        Class<?> iAmmoClass = Class.forName("com.tacz.guns.api.item.IAmmo");
                        Method getIAmmoOrNullMethod = iAmmoClass.getMethod("getIAmmoOrNull", ItemStack.class);
                        Method getAmmoIdMethod = iAmmoClass.getMethod("getAmmoId", ItemStack.class);
                        Method isAmmoOfGunMethod = iAmmoClass.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class);
                        
                        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(cap -> {
                            StevesArmyMod.LOGGER.info("[TaCZ] Inventory capability found with {} slots", cap.getSlots());
                            for (int i = 0; i < cap.getSlots(); i++) {
                                ItemStack slotStack = cap.getStackInSlot(i);
                                if (!slotStack.isEmpty()) {
                                    try {
                                        Object iAmmo = getIAmmoOrNullMethod.invoke(null, slotStack);
                                        if (iAmmo != null) {
                                            Object itemAmmoId = getAmmoIdMethod.invoke(iAmmo, slotStack);
                                            boolean matches = (boolean) isAmmoOfGunMethod.invoke(iAmmo, gunStack, slotStack);
                                            StevesArmyMod.LOGGER.info("[TaCZ] Slot {}: ammoId={} isAmmoOfGun={}", i, itemAmmoId, matches);
                                        }
                                    } catch (Exception ex) {
                                        StevesArmyMod.LOGGER.warn("[TaCZ] Error checking slot {}: {}", i, ex.getMessage());
                                    }
                                }
                            }
                        });
                    } catch (Exception capEx) {
                        StevesArmyMod.LOGGER.warn("[TaCZ] Failed to check inventory capability: {}", capEx.getMessage());
                    }
                }
                
                Method getReloadState = gunOperatorClass.getMethod("getSynReloadState");
                Object reloadStateBefore = getReloadState.invoke(gunOperator);
                StevesArmyMod.LOGGER.info("[TaCZ] Reload state before: {}", reloadStateBefore.toString());
                
                Method reloadMethod = gunOperatorClass.getMethod("reload");
                reloadMethod.invoke(gunOperator);
                
                Object reloadStateAfter = getReloadState.invoke(gunOperator);
                StevesArmyMod.LOGGER.info("[TaCZ] Reload state after: {}", reloadStateAfter.toString());
                
                boolean isReloadingAfter = isReloading(entity);
                StevesArmyMod.LOGGER.info("[TaCZ] isReloading after call: {}", isReloadingAfter);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[TaCZ] Reload failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void bolt(LivingEntity entity) {
            if (!hasGun(entity)) return;
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method boltMethod = gunOperatorClass.getMethod("bolt");
                boltMethod.invoke(gunOperator);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Bolt failed: " + e.getMessage());
            }
        }

        @Override
        public void aim(LivingEntity entity, boolean isAiming) {
            if (!hasGun(entity)) return;
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method aimMethod = gunOperatorClass.getMethod("aim", boolean.class);
                aimMethod.invoke(gunOperator, isAiming);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Aim failed: " + e.getMessage());
            }
        }

        @Override
        public boolean isBolting(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method isBoltingMethod = gunOperatorClass.getMethod("getSynIsBolting");
                return (boolean) isBoltingMethod.invoke(gunOperator);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean isReloading(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method getReloadState = gunOperatorClass.getMethod("getSynReloadState");
                Object reloadState = getReloadState.invoke(gunOperator);
                Class<?> reloadStateClass = Class.forName("com.tacz.guns.api.entity.ReloadState");
                Method getStateType = reloadStateClass.getMethod("getStateType");
                Object stateType = getStateType.invoke(reloadState);
                
                Method isReloadingMethod = stateType.getClass().getMethod("isReloading");
                boolean reloading = (boolean) isReloadingMethod.invoke(stateType);
                
                Method getCountDown = reloadStateClass.getMethod("getCountDown");
                long countDown = (long) getCountDown.invoke(reloadState);
                
                if (reloading) {
                    StevesArmyMod.LOGGER.info("[TaCZ] isReloading: stateType={}, countDown={}ms", stateType.toString(), countDown);
                }
                
                return reloading;
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[TaCZ] isReloading exception: {}", e.getMessage());
                return false;
            }
        }

        @Override
        public float getAimProgress(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method getAimProgress = gunOperatorClass.getMethod("getSynAimingProgress");
                return (float) getAimProgress.invoke(gunOperator);
            } catch (Exception e) {
                return 0f;
            }
        }

        @Override
        public long getShootCoolDown(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method getCooldown = gunOperatorClass.getMethod("getSynShootCoolDown");
                return (long) getCooldown.invoke(gunOperator);
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public boolean isDrawing(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method getDrawCoolDown = gunOperatorClass.getMethod("getSynDrawCoolDown");
                long drawCooldown = (long) getDrawCoolDown.invoke(gunOperator);
                return drawCooldown > 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public double getEffectiveRange(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                
                Method getCacheProperty = gunOperatorClass.getMethod("getCacheProperty");
                Object cacheProperty = getCacheProperty.invoke(gunOperator);
                
                Method getCache = cacheProperty.getClass().getMethod("getCache", String.class);
                Float effectiveRange = (Float) getCache.invoke(cacheProperty, "effective_range");
                
                if (effectiveRange != null && effectiveRange > 0) {
                    return effectiveRange.doubleValue();
                }
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Failed to get effective range: {}", e.getMessage());
            }
            return DEFAULT_GUN_RANGE;
        }

        @Override
        public Optional<ItemStack> getGunStack(LivingEntity entity) {
            ItemStack mainHand = entity.getMainHandItem();
            if (hasGun(entity)) return Optional.of(mainHand);
            return Optional.empty();
        }

        @Override
        public void initialData(LivingEntity entity) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method initialDataMethod = gunOperatorClass.getMethod("initialData");
                initialDataMethod.invoke(gunOperator);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] initialData failed: " + e.getMessage());
            }
        }

        @Override
        public void draw(LivingEntity entity) {
            if (!hasGun(entity)) return;
            if (isReloading(entity)) {
                StevesArmyMod.LOGGER.info("[TaCZ] draw() skipped - entity is reloading");
                return;
            }
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                Method drawMethod = gunOperatorClass.getMethod("draw", java.util.function.Supplier.class);
                drawMethod.invoke(gunOperator, (java.util.function.Supplier<ItemStack>) () -> entity.getMainHandItem());
                StevesArmyMod.LOGGER.info("[TaCZ] draw() completed");
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Draw failed: " + e.getMessage());
            }
        }

        @Override
        public int getMagazineSize(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return 30;
                
                Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
                Object gunId = getGunId.invoke(iGun, gunStack);
                
                Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
                Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
                Object indexOpt = getCommonGunIndex.invoke(null, gunId);
                
                if (indexOpt instanceof Optional<?> opt && opt.isPresent()) {
                    Object gunIndex = opt.get();
                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    Method getMagazineSizeMethod = gunData.getClass().getMethod("getMagazineSize");
                    return (int) getMagazineSizeMethod.invoke(gunData);
                }
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Failed to get magazine size: " + e.getMessage());
            }
            return 30;
        }

        @Override
        public int getCurrentAmmo(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return 0;
                Method getCurrentAmmoCount = iGunClass.getMethod("getCurrentAmmoCount", ItemStack.class);
                return (int) getCurrentAmmoCount.invoke(iGun, gunStack);
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public boolean hasAmmoInBarrel(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return false;
                Method hasAmmoInBarrelMethod = iGunClass.getMethod("hasAmmoInBarrel", ItemStack.class);
                return (boolean) hasAmmoInBarrelMethod.invoke(iGun, gunStack);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean isManualBolt(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return false;
                
                Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
                Object gunId = getGunId.invoke(iGun, gunStack);
                
                Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
                Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
                Object indexOpt = getCommonGunIndex.invoke(null, gunId);
                
                if (indexOpt instanceof Optional<?> opt && opt.isPresent()) {
                    Object gunIndex = opt.get();
                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    Method getBolt = gunData.getClass().getMethod("getBolt");
                    Object bolt = getBolt.invoke(gunData);
                    return bolt.toString().equals("MANUAL_ACTION");
                }
            } catch (Exception e) {
            }
            return false;
        }

        @Override
        public boolean useInventoryAmmo(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return false;
                
                Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
                Object gunId = getGunId.invoke(iGun, gunStack);
                
                Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
                Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
                Object indexOpt = getCommonGunIndex.invoke(null, gunId);
                
                if (indexOpt instanceof Optional<?> opt && opt.isPresent()) {
                    Object gunIndex = opt.get();
                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    Method getUseInventoryAmmoMethod = gunData.getClass().getMethod("getUseInventoryAmmo");
                    return (boolean) getUseInventoryAmmoMethod.invoke(gunData);
                }
            } catch (Exception e) {
            }
            return false;
        }

        @Override
        public String getGunId(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return "";
                Method getGunIdMethod = iGunClass.getMethod("getGunId", ItemStack.class);
                Object gunId = getGunIdMethod.invoke(iGun, gunStack);
                return gunId != null ? gunId.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public String getAmmoId(LivingEntity entity) {
            try {
                ItemStack gunStack = entity.getMainHandItem();
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);
                if (iGun == null) return "";
                
                Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
                Object gunId = getGunId.invoke(iGun, gunStack);
                
                Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
                Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
                Object indexOpt = getCommonGunIndex.invoke(null, gunId);
                
                if (indexOpt instanceof Optional<?> opt && opt.isPresent()) {
                    Object gunIndex = opt.get();
                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    Method getAmmoIdMethod = gunData.getClass().getMethod("getAmmoId");
                    Object ammoId = getAmmoIdMethod.invoke(gunData);
                    return ammoId != null ? ammoId.toString() : "";
                }
            } catch (Exception e) {
            }
            return "";
        }
        
        @Override
        public void crawl(LivingEntity entity, boolean isCrawl) {
            try {
                Class<?> gunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                Method fromLivingEntity = gunOperatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                Object gunOperator = fromLivingEntity.invoke(null, entity);
                
                Method crawlMethod = gunOperatorClass.getMethod("crawl", boolean.class);
                crawlMethod.invoke(gunOperator, isCrawl);
                
                if (isCrawl) {
                    entity.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                } else if (entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
                    entity.setPose(net.minecraft.world.entity.Pose.STANDING);
                }
            } catch (Exception e) {
                StevesArmyMod.LOGGER.debug("[TaCZ] Failed to set crawl state: " + e.getMessage());
            }
        }
        
        @Override
        public boolean isCrawling(LivingEntity entity) {
            return entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING && !entity.isInWater();
        }
    }
}