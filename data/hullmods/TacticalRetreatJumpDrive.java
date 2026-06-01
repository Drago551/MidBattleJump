package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

public class TacticalRetreatJumpDrive extends BaseHullMod {

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "0%";
        if (index == 1) return "2/3/4/5 seconds";
        if (index == 2) return "shields are disabled";
        if (index == 3) return "tactical retreat jump";

        return null;
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        if (!engine.hasPluginOfClass(GlobalRetreatJumpSystem.class)) {
            engine.addPlugin(new GlobalRetreatJumpSystem());
        }

        GlobalRetreatJumpSystem.register(ship, id);
    }

    public static class GlobalRetreatJumpSystem extends BaseEveryFrameCombatPlugin {

        private static final Set ships = new HashSet();

        private static final Map retreatStarted = new HashMap();
        private static final Map jumpTimer = new HashMap();
        private static final Map jumpDone = new HashMap();
        private static final Map originalCollisionClass = new HashMap();

        private static final float FLUX_EPSILON = 1f;

        public GlobalRetreatJumpSystem() {
            ships.clear();
            retreatStarted.clear();
            jumpTimer.clear();
            jumpDone.clear();
            originalCollisionClass.clear();
        }

        public static void register(ShipAPI ship, String id) {
            if (ship == null) return;

            ship = getRootShip(ship);
            if (ship == null) return;

            if (ships.contains(ship)) return;

            ships.add(ship);

            retreatStarted.put(ship, Boolean.FALSE);
            jumpTimer.put(ship, new Float(0f));
            jumpDone.put(ship, Boolean.FALSE);

            rememberOriginalCollisionClasses(ship);
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
                    cleanupShip(ship, it, true);
                    continue;
                }

                if (!engine.isEntityInPlay(ship)) {
                    cleanupShip(ship, it, true);
                    continue;
                }

                Boolean doneObj = (Boolean) jumpDone.get(ship);
                if (doneObj != null && doneObj.booleanValue()) {
                    continue;
                }

                /*
                 * Najważniejszy warunek:
                 * skrypt odpala dopiero wtedy, kiedy gracz kliknie Retreat.
                 */
                if (!ship.isRetreating()) {
                    continue;
                }

                Boolean startedObj = (Boolean) retreatStarted.get(ship);
                boolean started = startedObj != null && startedObj.booleanValue();

                if (!started) {
                    retreatStarted.put(ship, Boolean.TRUE);
                    rememberOriginalCollisionClasses(ship);
                    spawnRetreatStartFX(engine, ship);
                }

                /*
                 * Od momentu kliknięcia Retreat tarcze są wyłączone
                 * i nie da się ich ponownie włączyć.
                 * Dla statków modułowych robimy to na całej grupie.
                 */
                forceShieldOffGroup(ship);

                /*
                 * Czekamy aż cały statek-modułowiec ma 0 fluxu.
                 * Jeśli którykolwiek moduł ma flux, timer zostaje wyzerowany.
                 */
                if (!hasZeroFluxGroup(ship)) {
                    jumpTimer.put(ship, new Float(0f));
                    showFluxWaitFX(engine, ship, amount);
                    continue;
                }

                /*
                 * Statek ma 0 fluxu, więc zaczynamy ładowanie skoku.
                 */
                Float timerObj = (Float) jumpTimer.get(ship);
                float timer = timerObj != null ? timerObj.floatValue() : 0f;

                timer += amount;
                jumpTimer.put(ship, new Float(timer));

                float chargeTime = getRetreatChargeTime(ship);

                applyChargingEffects(engine, ship, timer, chargeTime);

                if (timer >= chargeTime) {
                    performRetreatJump(engine, ship);
                    jumpDone.put(ship, Boolean.TRUE);
                    cleanupShip(ship, it, false);
                }
            }
        }

        // =========================
        // MODULED SHIP HELPERS
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

        private static void rememberOriginalCollisionClasses(ShipAPI ship) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                if (!originalCollisionClass.containsKey(part)) {
                    originalCollisionClass.put(part, part.getCollisionClass());
                }
            }
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

        private static void restoreGroupCollision(ShipAPI ship) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                CollisionClass original = (CollisionClass) originalCollisionClass.get(part);
                if (original != null) {
                    part.setCollisionClass(original);
                } else {
                    part.setCollisionClass(CollisionClass.SHIP);
                }
            }
        }

        private static void forceShieldOffGroup(ShipAPI ship) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                forceShieldOff(part);
            }
        }

        private static boolean hasZeroFluxGroup(ShipAPI ship) {
            List group = getShipGroup(ship);

            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (!hasZeroFlux(part)) {
                    return false;
                }
            }

            return true;
        }

        private static void removeGroupFromCombat(CombatEngineAPI engine, ShipAPI ship) {
            if (engine == null || ship == null) return;

            ShipAPI root = getRootShip(ship);
            if (root == null) return;

            List group = getShipGroup(root);

            /*
             * Najpierw ukrywamy i wyłączamy kolizję całej grupy.
             */
            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.setAlphaMult(0f);
                part.setCollisionClass(CollisionClass.NONE);
                part.getVelocity().set(0f, 0f);
            }

            /*
             * WAŻNE:
             * engine.removeEntity() usuwa obiekt z mapy, ale nie oznacza statku jako poprawnie
             * wycofanego w CombatFleetManager. Dlatego główny kadłub zdejmujemy przez
             * removeDeployed(..., true), co działa tak jak normalny retreat poza mapę.
             */
            CombatFleetManagerAPI manager = engine.getFleetManager(root.getOwner());

            if (manager != null) {
                try {
                    manager.removeDeployed(root, true);
                } catch (Exception ex) {
                    if (engine.isEntityInPlay(root)) {
                        engine.removeEntity(root);
                    }
                }
            } else {
                if (engine.isEntityInPlay(root)) {
                    engine.removeEntity(root);
                }
            }

            /*
             * Moduły nie zawsze są traktowane przez fleet manager jak zwykłe deployed ships,
             * więc próbujemy oznaczyć je jako retreated, a jeśli API tego nie przyjmie,
             * usuwamy samą encję jako fallback. Główny kadłub powyżej jest tym, który
             * powinien liczyć się dla wyniku bitwy i statusu floty.
             */
            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;
                if (part == root) continue;

                CombatFleetManagerAPI partManager = engine.getFleetManager(part.getOwner());

                if (partManager != null) {
                    try {
                        partManager.removeDeployed(part, true);
                        continue;
                    } catch (Exception ex) {
                        // fallback poniżej
                    }
                }

                if (engine.isEntityInPlay(part)) {
                    engine.removeEntity(part);
                }
            }

            /*
             * Dodatkowy fallback: jeśli root z jakiegoś powodu nadal istnieje jako encja,
             * zdejmujemy go wizualnie/fizycznie, ale po wcześniejszym removeDeployed(true)
             * powinien już być policzony jako wycofany.
             */
            if (engine.isEntityInPlay(root)) {
                engine.removeEntity(root);
            }
        }

        private static boolean hasZeroFlux(ShipAPI ship) {
            if (ship == null) return false;
            if (ship.getFluxTracker() == null) return false;

            return ship.getFluxTracker().getCurrFlux() <= FLUX_EPSILON;
        }

        private static float getRetreatChargeTime(ShipAPI ship) {
            if (ship.isFrigate()) return 2f;
            if (ship.isDestroyer()) return 3f;
            if (ship.isCruiser()) return 4f;
            if (ship.isCapital()) return 5f;

            return 3f;
        }

        private static void forceShieldOff(ShipAPI ship) {
            if (ship == null) return;

            /*
             * Blokuje komendę włączania tarczy / phase cloak.
             * Działa co klatkę, więc gracz ani AI nie powinny móc jej aktywować.
             */
            ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);

            ShieldAPI shield = ship.getShield();

            if (shield != null && shield.isOn()) {
                shield.toggleOff();
            }
        }

        private static void applyChargingEffects(
                CombatEngineAPI engine,
                ShipAPI ship,
                float timer,
                float chargeTime
        ) {
            if (engine == null || ship == null) return;

            float progress = timer / chargeTime;
            if (progress < 0f) progress = 0f;
            if (progress > 1f) progress = 1f;

            /*
             * Statek nadal jest na mapie, ale lekko pulsuje.
             * Dla statków modułowych zmieniamy alpha całej grupy.
             */
            float alpha = 1f - 0.35f * progress;
            if (alpha < 0.35f) alpha = 0.35f;

            setGroupAlpha(ship, alpha);

            /*
             * Trzymamy całą grupę w miejscu podczas ładowania skoku.
             * Jeśli chcesz, żeby mogła dalej lecieć podczas ładowania,
             * usuń blok poniżej.
             */
            List group = getShipGroup(ship);
            for (int i = 0; i < group.size(); i++) {
                ShipAPI part = (ShipAPI) group.get(i);
                if (part == null) continue;

                part.getVelocity().scale(0.96f);
                part.giveCommand(ShipCommand.DECELERATE, null, 0);
            }

            Vector2f loc = ship.getLocation();

            if (Math.random() < 0.25f + 0.45f * progress) {
                float angle = (float) (Math.random() * Math.PI * 2f);
                float radius = ship.getCollisionRadius() * (0.6f + (float) Math.random() * 0.8f);

                Vector2f particleLoc = new Vector2f(
                        loc.x + (float) Math.cos(angle) * radius,
                        loc.y + (float) Math.sin(angle) * radius
                );

                Vector2f vel = new Vector2f(
                        (float) Math.cos(angle) * -80f * progress,
                        (float) Math.sin(angle) * -80f * progress
                );

                engine.addSmoothParticle(
                        particleLoc,
                        vel,
                        35f + 45f * progress,
                        0.8f + progress,
                        0.25f,
                        new Color(80, 180, 255, 100 + (int) (120f * progress))
                );
            }

            if (Math.random() < 0.08f + 0.25f * progress) {
                engine.addHitParticle(
                        loc,
                        new Vector2f(),
                        ship.getCollisionRadius() * (0.8f + progress),
                        0.6f + progress,
                        0.12f,
                        new Color(180, 230, 255, 100 + (int) (120f * progress))
                );
            }
        }

        private static void performRetreatJump(CombatEngineAPI engine, ShipAPI ship) {
            if (engine == null || ship == null) return;

            Vector2f loc = new Vector2f(ship.getLocation());

            spawnRetreatJumpFX(engine, ship, loc);

            stopGroupMovement(ship);
            setGroupAlpha(ship, 0f);
            setGroupCollision(ship, CollisionClass.NONE);

            /*
             * Statek był już oznaczony jako retreating przez kliknięcie Retreat.
             * Usuwamy z pola walki cały statek modułowy, nie tylko główny kadłub.
             */
            removeGroupFromCombat(engine, ship);
        }

        private static void spawnRetreatStartFX(CombatEngineAPI engine, ShipAPI ship) {
            if (engine == null || ship == null) return;

            Vector2f loc = ship.getLocation();

            engine.addHitParticle(
                    loc,
                    new Vector2f(),
                    ship.getCollisionRadius() * 1.2f,
                    0.8f,
                    0.35f,
                    new Color(80, 180, 255, 180)
            );

            engine.addSmoothParticle(
                    loc,
                    new Vector2f(),
                    ship.getCollisionRadius() * 1.8f,
                    0.9f,
                    0.45f,
                    new Color(120, 220, 255, 120)
            );
        }

        private static void showFluxWaitFX(CombatEngineAPI engine, ShipAPI ship, float amount) {
            if (engine == null || ship == null) return;

            /*
             * Bardzo subtelny efekt informujący, że napęd czeka na wyzerowanie fluxu.
             */
            if (Math.random() > 0.08f) return;

            Vector2f loc = ship.getLocation();

            float angle = (float) (Math.random() * Math.PI * 2f);
            float radius = ship.getCollisionRadius() * (0.8f + (float) Math.random() * 0.5f);

            Vector2f particleLoc = new Vector2f(
                    loc.x + (float) Math.cos(angle) * radius,
                    loc.y + (float) Math.sin(angle) * radius
            );

            engine.addSmoothParticle(
                    particleLoc,
                    new Vector2f(),
                    30f,
                    0.7f,
                    0.25f,
                    new Color(80, 180, 255, 90)
            );
        }

        private static void spawnRetreatJumpFX(CombatEngineAPI engine, ShipAPI ship, Vector2f center) {
            float scale = getFxScale(ship);

            engine.addHitParticle(
                    center,
                    new Vector2f(),
                    500f * scale,
                    1.8f,
                    0.35f,
                    new Color(255, 255, 255, 255)
            );

            engine.addSmoothParticle(
                    center,
                    new Vector2f(),
                    700f * scale,
                    1.5f,
                    0.55f,
                    new Color(120, 220, 255, 220)
            );

            int points = 80;

            for (int i = 0; i < points; i++) {
                float angle = (float) (Math.PI * 2f * i / points);

                Vector2f vel = new Vector2f(
                        (float) Math.cos(angle) * 500f * scale,
                        (float) Math.sin(angle) * 500f * scale
                );

                engine.addSmoothParticle(
                        center,
                        vel,
                        140f * scale,
                        1.5f,
                        0.45f,
                        new Color(80, 180, 255, 160)
                );
            }

            for (int i = 0; i < 28; i++) {
                float angle = (float) (Math.random() * Math.PI * 2f);
                float speed = 250f + (float) Math.random() * 450f;

                Vector2f vel = new Vector2f(
                        (float) Math.cos(angle) * speed * scale,
                        (float) Math.sin(angle) * speed * scale
                );

                engine.addHitParticle(
                        center,
                        vel,
                        100f * scale,
                        1.1f,
                        0.35f,
                        new Color(180, 230, 255, 200)
                );
            }
        }

        private static float getFxScale(ShipAPI ship) {
            if (ship.isFrigate()) return 0.75f;
            if (ship.isDestroyer()) return 0.9f;
            if (ship.isCruiser()) return 1.15f;
            if (ship.isCapital()) return 1.45f;

            return 1f;
        }

        private static void cleanupShip(ShipAPI ship, Iterator it, boolean restoreVisuals) {
            if (ship != null) {
                if (restoreVisuals) {
                    setGroupAlpha(ship, 1f);
                    restoreGroupCollision(ship);
                }

                retreatStarted.remove(ship);
                jumpTimer.remove(ship);
                jumpDone.remove(ship);

                List group = getShipGroup(ship);
                for (int i = 0; i < group.size(); i++) {
                    ShipAPI part = (ShipAPI) group.get(i);
                    originalCollisionClass.remove(part);
                }
            }

            it.remove();

            if (ships.isEmpty()) {
                retreatStarted.clear();
                jumpTimer.clear();
                jumpDone.clear();
                originalCollisionClass.clear();
            }
        }
    }
}
