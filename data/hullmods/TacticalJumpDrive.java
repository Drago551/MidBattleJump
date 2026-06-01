package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TacticalJumpDrive extends BaseHullMod {

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "15%";
        if (index == 1) return "25%";
        if (index == 2) return "35%";
        if (index == 3) return "50%";

        return null;
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        if (!engine.hasPluginOfClass(GlobalJumpSystem.class)) {
            engine.addPlugin(new GlobalJumpSystem());
        }

        GlobalJumpSystem.register(ship);
    }

    public static class GlobalJumpSystem extends BaseEveryFrameCombatPlugin {

        private static final Set ships = new HashSet();
        private static final Map lock = new HashMap();
        private static final Map stage = new HashMap();
        private static final Map ringDone = new HashMap();
        private static final Map slowTime = new HashMap();

        private static final Map spawnDelay = new HashMap();
        private static final Map reservedSpawns = new HashMap();
        private static final Map plannedSpawns = new HashMap();
        private static final Map warningDone = new HashMap();
        private static final Map afterimageTime = new HashMap();

        private static final String SLOW_ID = "tactical_jump_slow";

        private static final float WARNING_TIME = 0.15f;
        private static final float TELEPORT_TIME = 0.65f;
        private static final float SPAWN_END_TIME = 0.70f;

        private static final float FADE_TIME = 0.35f;
        private static final float LOCK_TIME = 0.3f;

        private static final float SPAWN_INTERVAL = 0.5f;

        private static float nextSpawnDelay = 0f;

        public GlobalJumpSystem() {
            ships.clear();
            lock.clear();
            stage.clear();
            ringDone.clear();
            slowTime.clear();
            spawnDelay.clear();
            reservedSpawns.clear();
            plannedSpawns.clear();
            warningDone.clear();
            afterimageTime.clear();
            nextSpawnDelay = 0f;
        }

        public static void register(ShipAPI ship) {
            if (ship == null) return;

            ship = getRootShip(ship);
            if (ship == null) return;

            if (ships.contains(ship)) return;

            ships.add(ship);
            lock.put(ship, new Float(0f));
            stage.put(ship, new Float(0f));
            ringDone.put(ship, Boolean.FALSE);
            slowTime.put(ship, new Float(0f));
            warningDone.put(ship, Boolean.FALSE);
            afterimageTime.put(ship, new Float(0f));

            spawnDelay.put(ship, new Float(nextSpawnDelay));
            nextSpawnDelay += SPAWN_INTERVAL;
        }

        @Override
        public void advance(float amount, List events) {

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) return;
            if (engine.isPaused()) return;

            Iterator it = ships.iterator();

            while (it.hasNext()) {

                ShipAPI ship = (ShipAPI) it.next();

                if (ship == null || !ship.isAlive()) {
                    cleanupShip(ship, it);
                    continue;
                }

                if (!engine.isEntityInPlay(ship)) {
                    continue;
                }

                Float delayObj = (Float) spawnDelay.get(ship);
                float delay = delayObj != null ? delayObj.floatValue() : 0f;

                if (delay > 0f) {
                    delay -= amount;
                    if (delay < 0f) delay = 0f;

                    spawnDelay.put(ship, new Float(delay));

                    setGroupAlpha(ship, 0f);
                    setGroupCollision(ship, CollisionClass.NONE);
                    stopGroupMovement(ship);

                    continue;
                }

                Float sObj = (Float) stage.get(ship);
                float s = sObj != null ? sObj.floatValue() : 0f;

                s += amount;
                stage.put(ship, new Float(s));

                if (s < WARNING_TIME) {
                    setGroupAlpha(ship, 0f);
                    setGroupCollision(ship, CollisionClass.NONE);
                    stopGroupMovement(ship);
                    continue;
                }

                if (s >= WARNING_TIME && s < TELEPORT_TIME) {

                    setGroupAlpha(ship, 0f);
                    setGroupCollision(ship, CollisionClass.NONE);
                    stopGroupMovement(ship);

                    Boolean warned = (Boolean) warningDone.get(ship);

                    if (warned == null || !warned.booleanValue()) {

                        Vector2f spawn = determineSpawnPoint(engine, ship);

                        plannedSpawns.put(ship, spawn);
                        reservedSpawns.put(ship, spawn);

                        spawnJumpWarningFX(engine, spawn);

                        warningDone.put(ship, Boolean.TRUE);
                    }

                    continue;
                }

                if (s >= TELEPORT_TIME && s < SPAWN_END_TIME) {

                    Boolean done = (Boolean) ringDone.get(ship);

                    if (done == null || !done.booleanValue()) {

                        Vector2f spawn = (Vector2f) plannedSpawns.get(ship);

                        if (spawn == null) {
                            spawn = determineSpawnPoint(engine, ship);
                            plannedSpawns.put(ship, spawn);
                            reservedSpawns.put(ship, spawn);
                        }

                        teleportGroupPreserveFormation(ship, spawn);
                        stopGroupMovement(ship);

                        faceNearestEnemy(engine, ship);

                        spawnArrivalFX(engine, ship, spawn);
                        spawnArrivalShockwave(engine, ship, spawn);

                        float maxFlux = ship.getMaxFlux();
                        ship.getFluxTracker().setCurrFlux(maxFlux * getFluxPenalty(ship));

                        setGroupCollision(ship, CollisionClass.SHIP);
                        setGroupAlpha(ship, 0f);

                        afterimageTime.put(ship, new Float(getAfterimageDuration(ship)));

                        ringDone.put(ship, Boolean.TRUE);
                    }

                    continue;
                }

                float slowEndTime = SPAWN_END_TIME + getSlowDuration(ship);

                Float stObj = (Float) slowTime.get(ship);
                float st = stObj != null ? stObj.floatValue() : 0f;

                if (s >= SPAWN_END_TIME && s < slowEndTime) {

                    st += amount;

                    MutableShipStatsAPI stats = ship.getMutableStats();

                    stats.getMaxSpeed().modifyMult(SLOW_ID, getSpeedMult(ship));
                    stats.getAcceleration().modifyMult(SLOW_ID, getAccelMult(ship));
                    stats.getDeceleration().modifyMult(SLOW_ID, getAccelMult(ship));
                    stats.getTurnAcceleration().modifyMult(SLOW_ID, getTurnMult(ship));

                } else if (st > 0f) {

                    st = 0f;
                    removeSlow(ship);
                }

                slowTime.put(ship, new Float(st));

                float alpha = (s - SPAWN_END_TIME) / FADE_TIME;
                if (alpha > 1f) alpha = 1f;
                if (alpha < 0f) alpha = 0f;

                setGroupAlpha(ship, alpha);

                Float aiObj = (Float) afterimageTime.get(ship);
                float ai = aiObj != null ? aiObj.floatValue() : 0f;

                if (ai > 0f) {
                    ai -= amount;
                    if (ai < 0f) ai = 0f;

                    afterimageTime.put(ship, new Float(ai));
                    spawnJumpSignatureTrail(engine, ship, ai);
                }

                Float tObj = (Float) lock.get(ship);
                float t = tObj != null ? tObj.floatValue() : 0f;

                if (t < LOCK_TIME) {
                    t += amount;
                    lock.put(ship, new Float(t));

                    stopGroupMovement(ship);
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                }

                if (s < slowEndTime) {
                    continue;
                }

                removeSlow(ship);
                setGroupAlpha(ship, 1f);
                setGroupCollision(ship, CollisionClass.SHIP);

                cleanupShip(ship, it);
            }
        }

        // =========================
        // MODULAR SHIP HELPERS
        // =========================

        private static ShipAPI getRootShip(ShipAPI ship) {
            if (ship == null) return null;

            ShipAPI root = ship;

            while (root.getParentStation() != null) {
                root = root.getParentStation();
            }

            return root;
        }

        private static List getShipGroup(ShipAPI ship) {
            List result = new ArrayList();

            ShipAPI root = getRootShip(ship);
            if (root == null) return result;

            result.add(root);

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) return result;

            List shipsList = engine.getShips();

            for (int i = 0; i < shipsList.size(); i++) {
                ShipAPI other = (ShipAPI) shipsList.get(i);

                if (other == null) continue;
                if (other == root) continue;

                ShipAPI otherRoot = getRootShip(other);

                if (otherRoot == root) {
                    result.add(other);
                }
            }

            return result;
        }

        private static void setGroupAlpha(ShipAPI ship, float alpha) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.setAlphaMult(alpha);
            }
        }

        private static void setGroupCollision(ShipAPI ship, CollisionClass collisionClass) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.setCollisionClass(collisionClass);
            }
        }

        private static void stopGroupMovement(ShipAPI ship) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.getVelocity().set(0f, 0f);
            }
        }

        private static void teleportGroupPreserveFormation(ShipAPI ship, Vector2f target) {
            ShipAPI root = getRootShip(ship);
            if (root == null || target == null) return;

            float dx = target.x - root.getLocation().x;
            float dy = target.y - root.getLocation().y;

            List group = getShipGroup(root);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.getLocation().x += dx;
                part.getLocation().y += dy;
                part.getVelocity().set(0f, 0f);
            }
        }

        // =========================
        // SIZE-BASED BALANCE
        // =========================

        private static float getFluxPenalty(ShipAPI ship) {
            if (ship.isFrigate()) return 0.15f;
            if (ship.isDestroyer()) return 0.25f;
            if (ship.isCruiser()) return 0.35f;
            if (ship.isCapital()) return 0.50f;
            return 0.30f;
        }

        private static float getSlowDuration(ShipAPI ship) {
            if (ship.isFrigate()) return 1.4f;
            if (ship.isDestroyer()) return 1.8f;
            if (ship.isCruiser()) return 2.3f;
            if (ship.isCapital()) return 3.0f;
            return 2.0f;
        }

        private static float getSpeedMult(ShipAPI ship) {
            if (ship.isFrigate()) return 0.70f;
            if (ship.isDestroyer()) return 0.60f;
            if (ship.isCruiser()) return 0.50f;
            if (ship.isCapital()) return 0.40f;
            return 0.50f;
        }

        private static float getAccelMult(ShipAPI ship) {
            if (ship.isFrigate()) return 0.65f;
            if (ship.isDestroyer()) return 0.55f;
            if (ship.isCruiser()) return 0.45f;
            if (ship.isCapital()) return 0.35f;
            return 0.40f;
        }

        private static float getTurnMult(ShipAPI ship) {
            if (ship.isFrigate()) return 0.75f;
            if (ship.isDestroyer()) return 0.65f;
            if (ship.isCruiser()) return 0.50f;
            if (ship.isCapital()) return 0.35f;
            return 0.50f;
        }

        private static float getShockwaveRadius(ShipAPI ship) {
            if (ship.isFrigate()) return 650f;
            if (ship.isDestroyer()) return 800f;
            if (ship.isCruiser()) return 1000f;
            if (ship.isCapital()) return 1300f;
            return 900f;
        }

        private static float getShockwaveEmpMult(ShipAPI ship) {
            if (ship.isFrigate()) return 0.75f;
            if (ship.isDestroyer()) return 1.00f;
            if (ship.isCruiser()) return 1.25f;
            if (ship.isCapital()) return 1.60f;
            return 1.00f;
        }

        private static float getArrivalFxScale(ShipAPI ship) {
            if (ship.isFrigate()) return 0.75f;
            if (ship.isDestroyer()) return 0.90f;
            if (ship.isCruiser()) return 1.15f;
            if (ship.isCapital()) return 1.45f;
            return 1.00f;
        }

        private static float getAfterimageDuration(ShipAPI ship) {
            if (ship.isFrigate()) return 0.8f;
            if (ship.isDestroyer()) return 1.0f;
            if (ship.isCruiser()) return 1.25f;
            if (ship.isCapital()) return 1.55f;
            return 1.15f;
        }

        private Vector2f determineSpawnPoint(CombatEngineAPI engine, ShipAPI ship) {

            Vector2f cluster = findAllyCluster(engine, ship);

            if (cluster != null) {
                return findSafeSpawn(engine, cluster, ship);
            }

            Vector2f enemy = findEnemyAnchor(engine, ship);

            if (enemy != null) {
                return findSafeEnemySpawn(engine, enemy, ship);
            }

            return new Vector2f(ship.getLocation().x, ship.getLocation().y);
        }

        private static void removeSlow(ShipAPI ship) {
            if (ship == null) return;

            MutableShipStatsAPI stats = ship.getMutableStats();

            stats.getMaxSpeed().unmodify(SLOW_ID);
            stats.getAcceleration().unmodify(SLOW_ID);
            stats.getDeceleration().unmodify(SLOW_ID);
            stats.getTurnAcceleration().unmodify(SLOW_ID);
        }

        private static void cleanupShip(ShipAPI ship, Iterator it) {
            if (ship != null) {
                removeSlow(ship);
                setGroupAlpha(ship, 1f);
                setGroupCollision(ship, CollisionClass.SHIP);

                lock.remove(ship);
                stage.remove(ship);
                ringDone.remove(ship);
                slowTime.remove(ship);
                spawnDelay.remove(ship);
                reservedSpawns.remove(ship);
                plannedSpawns.remove(ship);
                warningDone.remove(ship);
                afterimageTime.remove(ship);
            }

            it.remove();

            if (ships.isEmpty()) {
                nextSpawnDelay = 0f;
                reservedSpawns.clear();
                plannedSpawns.clear();
                spawnDelay.clear();
                warningDone.clear();
                afterimageTime.clear();
            }
        }

        private void spawnJumpWarningFX(CombatEngineAPI engine, Vector2f center) {

            engine.addHitParticle(
                    center,
                    new Vector2f(),
                    180f,
                    1.2f,
                    0.55f,
                    new Color(80, 180, 255, 180)
            );

            engine.addSmoothParticle(
                    center,
                    new Vector2f(),
                    260f,
                    1.0f,
                    0.6f,
                    new Color(120, 220, 255, 140)
            );

            int points = 48;

            for (int i = 0; i < points; i++) {

                float angle = (float) (Math.PI * 2f * i / points);

                Vector2f loc = new Vector2f(
                        center.x + (float) Math.cos(angle) * 180f,
                        center.y + (float) Math.sin(angle) * 180f
                );

                Vector2f vel = new Vector2f(
                        (float) Math.cos(angle) * 80f,
                        (float) Math.sin(angle) * 80f
                );

                engine.addSmoothParticle(
                        loc,
                        vel,
                        45f,
                        1.3f,
                        0.5f,
                        new Color(80, 180, 255, 130)
                );
            }

            for (int i = 0; i < 18; i++) {

                float a = (float) (Math.random() * Math.PI * 2f);
                float r = 30f + (float) Math.random() * 140f;

                Vector2f loc = new Vector2f(
                        center.x + (float) Math.cos(a) * r,
                        center.y + (float) Math.sin(a) * r
                );

                engine.addSmoothParticle(
                        loc,
                        new Vector2f(),
                        35f,
                        1.0f,
                        0.45f,
                        new Color(160, 230, 255, 160)
                );
            }
        }

        private void spawnArrivalFX(CombatEngineAPI engine, ShipAPI ship, Vector2f center) {

            float scale = getArrivalFxScale(ship);

            engine.addHitParticle(center, new Vector2f(), 500f * scale, 1.6f, 0.5f,
                    new Color(255, 255, 255, 255));

            engine.addSmoothParticle(center, new Vector2f(), 600f * scale, 1.8f, 0.7f,
                    new Color(120, 200, 255, 220));

            int points = 80;

            for (int i = 0; i < points; i++) {

                float angle = (float) (Math.PI * 2f * i / points);

                engine.addSmoothParticle(
                        center,
                        new Vector2f(
                                (float) Math.cos(angle) * 500f * scale,
                                (float) Math.sin(angle) * 500f * scale
                        ),
                        220f * scale,
                        2.0f,
                        0.6f,
                        new Color(100, 180, 255, 180)
                );
            }

            for (int i = 0; i < 25; i++) {

                float a = (float) (Math.random() * Math.PI * 2f);
                float v = (200f + (float) Math.random() * 300f) * scale;

                engine.addHitParticle(
                        center,
                        new Vector2f(
                                (float) Math.cos(a) * v,
                                (float) Math.sin(a) * v
                        ),
                        140f * scale,
                        1.2f,
                        0.4f,
                        new Color(180, 220, 255, 200)
                );
            }

            for (int i = 0; i < 35; i++) {

                float a = (float) (Math.random() * Math.PI * 2f);
                float r = (50f + (float) Math.random() * 120f) * scale;

                engine.addSmoothParticle(
                        new Vector2f(
                                center.x + (float) Math.cos(a) * r,
                                center.y + (float) Math.sin(a) * r
                        ),
                        new Vector2f(),
                        90f * scale,
                        1.1f,
                        0.8f,
                        new Color(200, 240, 255, 255)
                );
            }

            engine.addHitParticle(center, new Vector2f(), 900f * scale, 1.0f, 0.25f,
                    new Color(140, 200, 255, 255));
        }

        private void spawnJumpSignatureTrail(CombatEngineAPI engine, ShipAPI ship, float timeLeft) {

            Vector2f loc = ship.getLocation();
            float facing = (float) Math.toRadians(ship.getFacing());

            float maxTime = getAfterimageDuration(ship);
            if (maxTime < 0.1f) maxTime = 0.1f;

            float intensity = timeLeft / maxTime;
            if (intensity < 0f) intensity = 0f;
            if (intensity > 1f) intensity = 1f;

            float backX = -(float) Math.cos(facing);
            float backY = -(float) Math.sin(facing);

            int count = 3;
            if (ship.isCruiser()) count = 4;
            if (ship.isCapital()) count = 5;

            for (int i = 0; i < count; i++) {

                float side = ((float) Math.random() - 0.5f) * ship.getCollisionRadius() * 1.2f;
                float back = 40f + (float) Math.random() * ship.getCollisionRadius();

                float px = loc.x + backX * back + (float) Math.cos(facing + Math.PI / 2f) * side;
                float py = loc.y + backY * back + (float) Math.sin(facing + Math.PI / 2f) * side;

                Vector2f particleLoc = new Vector2f(px, py);

                Vector2f vel = new Vector2f(
                        backX * (80f + 120f * intensity),
                        backY * (80f + 120f * intensity)
                );

                engine.addSmoothParticle(
                        particleLoc,
                        vel,
                        45f + 55f * intensity,
                        0.8f + 0.7f * intensity,
                        0.25f + 0.25f * intensity,
                        new Color(90, 190, 255, (int) (80 + 100 * intensity))
                );
            }

            if (Math.random() < 0.35f) {
                engine.addHitParticle(
                        new Vector2f(loc.x, loc.y),
                        new Vector2f(),
                        ship.getCollisionRadius() * 1.2f,
                        0.5f,
                        0.15f,
                        new Color(180, 230, 255, (int) (60 + 90 * intensity))
                );
            }
        }

        private void faceNearestEnemy(CombatEngineAPI engine, ShipAPI ship) {

            ShipAPI nearest = findNearestEnemyShip(engine, ship);

            if (nearest == null) {
                return;
            }

            float dx = nearest.getLocation().x - ship.getLocation().x;
            float dy = nearest.getLocation().y - ship.getLocation().y;

            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));

            ship.setFacing(angle);
        }

        private ShipAPI findNearestEnemyShip(CombatEngineAPI engine, ShipAPI ship) {

            List shipsList = engine.getShips();

            ShipAPI nearest = null;
            float bestDistSq = 999999999f;

            for (int i = 0; i < shipsList.size(); i++) {

                ShipAPI other = (ShipAPI) shipsList.get(i);

                if (other == null) continue;
                if (!other.isAlive()) continue;
                if (other.getOwner() == ship.getOwner()) continue;
                if (other.isFighter()) continue;
                if (other.isDrone()) continue;

                float dx = other.getLocation().x - ship.getLocation().x;
                float dy = other.getLocation().y - ship.getLocation().y;

                float distSq = dx * dx + dy * dy;

                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    nearest = other;
                }
            }

            return nearest;
        }

        private void spawnArrivalShockwave(CombatEngineAPI engine, ShipAPI source, Vector2f center) {

            float radius = getShockwaveRadius(source);
            float empMult = getShockwaveEmpMult(source);

            int points = 120;

            for (int i = 0; i < points; i++) {

                float angle = (float) (Math.PI * 2f * i / points);

                Vector2f velocity = new Vector2f(
                        (float) Math.cos(angle) * 750f * getArrivalFxScale(source),
                        (float) Math.sin(angle) * 750f * getArrivalFxScale(source)
                );

                engine.addSmoothParticle(
                        center,
                        velocity,
                        160f * getArrivalFxScale(source),
                        1.5f,
                        0.45f,
                        new Color(80, 180, 255, 160)
                );
            }

            engine.addHitParticle(
                    center,
                    new Vector2f(),
                    700f * getArrivalFxScale(source),
                    1.3f,
                    0.25f,
                    new Color(180, 230, 255, 220)
            );

            List shipsList = engine.getShips();

            for (int i = 0; i < shipsList.size(); i++) {

                ShipAPI target = (ShipAPI) shipsList.get(i);

                if (target == null) continue;
                if (!target.isAlive()) continue;
                if (target == source) continue;
                if (target.getOwner() == source.getOwner()) continue;

                float dx = target.getLocation().x - center.x;
                float dy = target.getLocation().y - center.y;

                float distSq = dx * dx + dy * dy;

                if (distSq > radius * radius) {
                    continue;
                }

                float dist = (float) Math.sqrt(distSq);
                if (dist < 1f) dist = 1f;

                float power = 1f - dist / radius;
                if (power < 0f) power = 0f;
                if (power > 1f) power = 1f;

                float emp = (250f + 450f * power) * empMult;
                float damage = (20f + 40f * power) * empMult;

                if (target.isFighter() || target.isDrone()) {
                    emp *= 1.5f;
                    damage *= 1.5f;
                }

                engine.spawnEmpArc(
                        source,
                        center,
                        source,
                        target,
                        DamageType.ENERGY,
                        damage,
                        emp,
                        100000f,
                        null,
                        14f,
                        new Color(80, 180, 255, 180),
                        new Color(220, 250, 255, 255)
                );

                if (target.isFighter() || target.isDrone()) {

                    float push = 250f + 350f * power;

                    target.getVelocity().x += (dx / dist) * push;
                    target.getVelocity().y += (dy / dist) * push;
                }
            }

            disruptNearbyMissiles(engine, source, center);
        }

        private void disruptNearbyMissiles(CombatEngineAPI engine, ShipAPI source, Vector2f center) {

            float radius = 500f;

            List missiles = engine.getMissiles();

            for (int i = 0; i < missiles.size(); i++) {

                MissileAPI missile = (MissileAPI) missiles.get(i);

                if (missile == null) continue;
                if (missile.isFading()) continue;
                if (missile.isFizzling()) continue;

                if (missile.getOwner() == source.getOwner()) continue;

                float dx = missile.getLocation().x - center.x;
                float dy = missile.getLocation().y - center.y;

                float distSq = dx * dx + dy * dy;

                if (distSq > radius * radius) {
                    continue;
                }

                float dist = (float) Math.sqrt(distSq);
                if (dist < 1f) dist = 1f;

                float power = 1f - dist / radius;
                if (power < 0f) power = 0f;
                if (power > 1f) power = 1f;

                float push = 650f + 850f * power;

                missile.getVelocity().x += (dx / dist) * push;
                missile.getVelocity().y += (dy / dist) * push;

                float randomAngle = (float) (Math.random() * Math.PI * 2f);
                float scatter = 120f + 180f * power;

                missile.getVelocity().x += (float) Math.cos(randomAngle) * scatter;
                missile.getVelocity().y += (float) Math.sin(randomAngle) * scatter;

                float vx = missile.getVelocity().x;
                float vy = missile.getVelocity().y;

                if (vx * vx + vy * vy > 1f) {
                    missile.setFacing((float) Math.toDegrees(Math.atan2(vy, vx)));
                }

                engine.addHitParticle(
                        missile.getLocation(),
                        new Vector2f(),
                        80f + 80f * power,
                        1.0f,
                        0.2f,
                        new Color(120, 220, 255, 180)
                );
            }
        }

        private Vector2f findAllyCluster(CombatEngineAPI engine, ShipAPI ship) {

            List shipsList = engine.getShips();

            float bestX = 0f;
            float bestY = 0f;
            int bestCount = 0;
            boolean foundAnchor = false;

            for (int i = 0; i < shipsList.size(); i++) {

                ShipAPI other = (ShipAPI) shipsList.get(i);

                if (other == null) continue;
                if (!other.isAlive()) continue;
                if (other.getOwner() != ship.getOwner()) continue;
                if (other == ship) continue;

                if (ships.contains(other)) continue;
                if (other.isFighter()) continue;
                if (other.isDrone()) continue;

                foundAnchor = true;

                float ox = other.getLocation().x;
                float oy = other.getLocation().y;

                int count = 0;

                for (int j = 0; j < shipsList.size(); j++) {

                    ShipAPI check = (ShipAPI) shipsList.get(j);

                    if (check == null) continue;
                    if (!check.isAlive()) continue;
                    if (check.getOwner() != ship.getOwner()) continue;
                    if (check == ship) continue;

                    if (ships.contains(check)) continue;
                    if (check.isFighter()) continue;
                    if (check.isDrone()) continue;

                    float dx = check.getLocation().x - ox;
                    float dy = check.getLocation().y - oy;

                    if (dx * dx + dy * dy < 490000f) {
                        count++;
                    }
                }

                if (count > bestCount) {
                    bestCount = count;
                    bestX = ox;
                    bestY = oy;
                }
            }

            if (!foundAnchor) {
                return null;
            }

            return new Vector2f(bestX, bestY);
        }

        private Vector2f findEnemyAnchor(CombatEngineAPI engine, ShipAPI ship) {

            List shipsList = engine.getShips();

            ShipAPI picked = null;
            int enemyCount = 0;

            for (int i = 0; i < shipsList.size(); i++) {

                ShipAPI other = (ShipAPI) shipsList.get(i);

                if (other == null) continue;
                if (!other.isAlive()) continue;
                if (other.getOwner() == ship.getOwner()) continue;
                if (other.isFighter()) continue;
                if (other.isDrone()) continue;

                enemyCount++;

                if (picked == null || Math.random() < 1f / enemyCount) {
                    picked = other;
                }
            }

            if (picked == null) {
                return null;
            }

            return new Vector2f(picked.getLocation().x, picked.getLocation().y);
        }

        private Vector2f findSafeSpawn(CombatEngineAPI engine, Vector2f center, ShipAPI spawningShip) {

            List shipsList = engine.getShips();

            float angleStep = 0.12f;
            float radiusStep = 100f;

            float startAngle = (float) (Math.random() * Math.PI * 2f);

            for (float r = 650f; r <= 2200f; r += radiusStep) {

                for (float a = startAngle; a < startAngle + Math.PI * 2f; a += angleStep) {

                    float jitter = (float) (Math.random() * 0.16f - 0.08f);

                    float x = center.x + (float) Math.cos(a + jitter) * r;
                    float y = center.y + (float) Math.sin(a + jitter) * r;

                    if (isFree(shipsList, x, y, spawningShip)) {
                        return new Vector2f(x, y);
                    }
                }
            }

            for (float r = 2300f; r <= 4000f; r += 150f) {

                for (float a = startAngle; a < startAngle + Math.PI * 2f; a += angleStep) {

                    float x = center.x + (float) Math.cos(a) * r;
                    float y = center.y + (float) Math.sin(a) * r;

                    if (isFree(shipsList, x, y, spawningShip)) {
                        return new Vector2f(x, y);
                    }
                }
            }

            float fallbackAngle = (float) (Math.random() * Math.PI * 2f);
            float fallbackDistance = 4500f;

            return new Vector2f(
                    center.x + (float) Math.cos(fallbackAngle) * fallbackDistance,
                    center.y + (float) Math.sin(fallbackAngle) * fallbackDistance
            );
        }

        private Vector2f findSafeEnemySpawn(CombatEngineAPI engine, Vector2f enemyCenter, ShipAPI spawningShip) {

            List shipsList = engine.getShips();

            float angleStep = 0.15f;
            float radiusStep = 150f;

            float startAngle = (float) (Math.random() * Math.PI * 2f);

            for (float r = 1200f; r <= 2600f; r += radiusStep) {

                for (float a = startAngle; a < startAngle + Math.PI * 2f; a += angleStep) {

                    float jitter = (float) (Math.random() * 0.2f - 0.1f);

                    float x = enemyCenter.x + (float) Math.cos(a + jitter) * r;
                    float y = enemyCenter.y + (float) Math.sin(a + jitter) * r;

                    if (isFree(shipsList, x, y, spawningShip)) {
                        return new Vector2f(x, y);
                    }
                }
            }

            for (float r = 2800f; r <= 5000f; r += 200f) {

                for (float a = startAngle; a < startAngle + Math.PI * 2f; a += angleStep) {

                    float x = enemyCenter.x + (float) Math.cos(a) * r;
                    float y = enemyCenter.y + (float) Math.sin(a) * r;

                    if (isFree(shipsList, x, y, spawningShip)) {
                        return new Vector2f(x, y);
                    }
                }
            }

            float fallbackAngle = (float) (Math.random() * Math.PI * 2f);
            float fallbackDistance = 5200f;

            return new Vector2f(
                    enemyCenter.x + (float) Math.cos(fallbackAngle) * fallbackDistance,
                    enemyCenter.y + (float) Math.sin(fallbackAngle) * fallbackDistance
            );
        }

        private boolean isFree(List shipsList, float x, float y, ShipAPI spawningShip) {

            float spawningRadius = 150f;

            if (spawningShip != null) {
                spawningRadius = spawningShip.getCollisionRadius();
            }

            for (int i = 0; i < shipsList.size(); i++) {

                ShipAPI s = (ShipAPI) shipsList.get(i);

                if (s == null) continue;
                if (!s.isAlive()) continue;
                if (s == spawningShip) continue;

                float otherRadius = s.getCollisionRadius();

                float safe = spawningRadius + otherRadius + 350f;

                if (safe < 750f) {
                    safe = 750f;
                }

                float dx = s.getLocation().x - x;
                float dy = s.getLocation().y - y;

                if (dx * dx + dy * dy < safe * safe) {
                    return false;
                }
            }

            Iterator entries = reservedSpawns.entrySet().iterator();

            while (entries.hasNext()) {

                Map.Entry entry = (Map.Entry) entries.next();

                ShipAPI reservedShip = (ShipAPI) entry.getKey();
                Vector2f reserved = (Vector2f) entry.getValue();

                if (reservedShip == spawningShip) continue;
                if (reserved == null) continue;

                float otherRadius = 150f;

                if (reservedShip != null) {
                    otherRadius = reservedShip.getCollisionRadius();
                }

                float safe = spawningRadius + otherRadius + 500f;

                if (safe < 950f) {
                    safe = 950f;
                }

                float dx = reserved.x - x;
                float dy = reserved.y - y;

                if (dx * dx + dy * dy < safe * safe) {
                    return false;
                }
            }

            return true;
        }
    }
}