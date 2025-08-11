// Constants and default values
private static final double HW = 0.3;
private static final double[][] CORNERS = {{ -HW, -HW }, { HW, -HW }, { -HW, HW }, { HW, HW }};
private boolean sneakingFromScript = false;
private boolean placed = false;
private boolean forceRelease = false;
private int sneakJumpDelayTicks = -1;
private int sneakJumpStartTick = -1;
private int unsneakDelayTicks = -1;
private int unsneakStartTick = -1;

void onLoad() {
    modules.registerSlider("Edge offset", " blocks", 0, 0, HW, 0.01);
    modules.registerSlider("Unsneak delay", "ms", 50, 50, 300, 5);
    modules.registerSlider("Sneak on jump", "ms", 0, 0, 500, 5);
    modules.registerButton("Sneak key pressed", false);
    modules.registerButton("Holding blocks", false);
    modules.registerButton("Looking down", false);
    modules.registerButton("Not moving forward", false);
}

void onDisable() {
    sneakingFromScript = false;
    resetUnsneak();
}

void onPrePlayerInput(MovementInput m) {
    boolean manualSneak = isManualSneak();
    boolean requireSneak = modules.getButton(scriptName, "Sneak key pressed");

    // Handle manual and required sneak state
    if (manualSneak && !requireSneak) {
        resetUnsneak();
        return;
    }

    if (requireSneak && (!manualSneak || (m.forward == 0 && m.strafe == 0))) {
        if (!manualSneak) resetUnsneak();
        repressSneak(m);
        return;
    }

    // Check conditions to possibly stop sneaking
    Entity player = client.getPlayer();
    if (shouldStopSneaking(m, player)) {
        sneakingFromScript = false;
        resetUnsneak();
        if (requireSneak) {
            repressSneak(m);
        }
        return;
    }

    // Handle sneaking on jump
    if (m.jump && player.onGround() && (m.forward != 0 || m.strafe != 0) && modules.getSlider(scriptName, "Sneak on jump") > 0) {
        handleSneakOnJump(m);
        return;
    }

    // Simulate movement for edge offset calculations
    handleMovementSimulation(m, player);
}

boolean shouldStopSneaking(MovementInput m, Entity player) {
    return (modules.getButton(scriptName, "Not moving forward") && client.getForward() > 0) ||
           (modules.getButton(scriptName, "Looking down") && player.getPitch() < 70) ||
           (modules.getButton(scriptName, "Holding blocks") && !player.isHoldingBlock());
}

void handleSneakOnJump(MovementInput m) {
    if (!modules.getButton(scriptName, "Sneak key pressed") || forceRelease) {
        sneakJumpStartTick = client.getPlayer().getTicksExisted();
        double raw = modules.getSlider(scriptName, "Sneak on jump") / 50;
        int base = (int) raw;
        sneakJumpDelayTicks = base + (util.randomDouble(0, 1) < (raw - base) ? 1 : 0);
        pressSneak(m, true);
    }
}

void handleMovementSimulation(MovementInput m, Entity player) {
    Vec3 position = player.getPosition();
    Simulation sim = Simulation.create();
    if (client.isSneak()) {
        sim.setForward(m.forward / 0.3f);
        sim.setStrafe(m.strafe / 0.3f);
        sim.setSneak(false);
    }
    sim.tick();
    Vec3 simPosition = sim.getPosition();

    double edgeOffset = computeEdgeOffset(simPosition, position);
    if (Double.isNaN(edgeOffset)) {
        if (sneakingFromScript) tryReleaseSneak(m, true);
        return;
    }

    boolean shouldSneak = edgeOffset > modules.getSlider(scriptName, "Edge offset");
    boolean shouldRelease = sneakingFromScript;

    if (shouldSneak) {
        pressSneak(m, true);
    } else if (shouldRelease) {
        tryReleaseSneak(m, true);
    }
}

boolean onPacketSent(CPacket packet) {
    if (packet instanceof C08) {
        C08 c08 = (C08) packet;
        if (c08.direction != 255 && sneakingFromScript && modules.getButton(scriptName, "Sneak key pressed")) {
            placed = true;
        }
    }
    return true;
}

// Sneak control functions
void repressSneak(MovementInput m) {
    if (forceRelease && isManualSneak()) {
        keybinds.setPressed("sneak", true);
        m.sneak = true;
    }
    forceRelease = false;
}

void pressSneak(MovementInput m, boolean resetDelay) {
    m.sneak = true;
    sneakingFromScript = true;
    if (resetDelay) {
        unsneakStartTick = -1;
    }
    repressSneak(m);
}

void tryReleaseSneak(MovementInput m, boolean resetDelay) {
    int existed = client.getPlayer().getTicksExisted();
    if (unsneakStartTick == -1 && sneakJumpStartTick == -1) {
        unsneakStartTick = existed;
        double raw = (modules.getSlider(scriptName, "Unsneak delay") - 50) / 50;
        int base = (int) raw;
        unsneakDelayTicks = base + (util.randomDouble(0, 1) < (raw - base) ? 1 : 0);
    }

    if (existed - sneakJumpStartTick < sneakJumpDelayTicks || existed - unsneakStartTick < unsneakDelayTicks) {
        pressSneak(m, false);
        return;
    }

    releaseSneak(m, resetDelay);
}

void releaseSneak(MovementInput m, boolean resetDelay) {
    if (!modules.getButton(scriptName, "Sneak key pressed")) {
        m.sneak = false;
    } else if (sneakingFromScript && isManualSneak() && (placed || !client.getPlayer().onGround())) {
        keybinds.setPressed("sneak", false);
        m.sneak = false;
        forceRelease = true;
    } else if (forceRelease) {
        m.sneak = false;
    }

    sneakingFromScript = placed = false;
    if (resetDelay) {
        resetUnsneak();
    }
}

void resetUnsneak() {
    unsneakStartTick = sneakJumpStartTick = sneakJumpDelayTicks = unsneakDelayTicks = -1;
}

// Helper functions
boolean isManualSneak() {
    return keybinds.isKeyDown(keybinds.getKeyCode("sneak"));
}

double computeEdgeOffset(Vec3 pos1, Vec3 pos2) {
    int floorY = (int)(pos1.y - 0.01);
    double best = Double.NaN;

    for (double[] c : CORNERS) {
        int bx = (int)Math.floor(pos2.x + c[0]);
        int bz = (int)Math.floor(pos2.z + c[1]);
        if (world.getBlockAt(bx, floorY, bz).name.equals("air")) continue;

        double offX = Math.abs(pos1.x - (bx + (pos1.x < bx + 0.5 ? 0 : 1)));
        double offZ = Math.abs(pos1.z - (bz + (pos1.z < bz + 0.5 ? 0 : 1)));
        boolean xDiff = (int)Math.floor(pos1.x) != bx;
        boolean zDiff = (int)Math.floor(pos1.z) != bz;

        double cornerDist = (xDiff ? Math.max(offX, offZ) : offZ);
        best = Double.isNaN(best) ? cornerDist : Math.min(best, cornerDist);
    }

    return best;
}
