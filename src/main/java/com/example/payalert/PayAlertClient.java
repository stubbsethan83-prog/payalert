package com.example.payalert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PayAlertClient implements ClientModInitializer {

    // Loose match for "Steve paid you $2.5M". Group 1 = name, group 2 = amount.
    private static final Pattern PAY_PATTERN = Pattern.compile(
            "([\\w.]{3,17}) paid you \\$?\\s?([\\d,\\.]+\\s?[kKmMbBtT]?)");

    // While true, any message containing "paid you" that fails to parse is echoed
    // into your chat so you can see the real format. Set to false once it works.
    private static final boolean DEBUG = true;

    private static final long SLIDE_IN_MS = 450;
    private static final long HOLD_MS = 4000;
    private static final long SLIDE_OUT_MS = 400;
    private static final int WIDTH = 170;
    private static final int HEIGHT = 36;
    private static final int MARGIN = 8;

    private record Alert(String player, String amount) {}

    private final Queue<Alert> queue = new ArrayDeque<>();
    private Alert current;
    private long startTime;

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handle(message.getString()));

        // /payalerttest shows a fake popup so you can check the animation works
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("payalerttest").executes(ctx -> {
                    queue.add(new Alert("TestPlayer", "2.5M"));
                    return 1;
                })));

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.of("payalert", "popup"),
                this::render);
    }

    private String lastKey = "";
    private long lastTime = 0;

    private void handle(String raw) {
        String msg = raw.trim();
        if (msg.startsWith("[PayAlert]")) return;

        Matcher m = PAY_PATTERN.matcher(msg);
        if (!m.find()) {
            if (DEBUG && msg.toLowerCase().contains("paid you")) echo("unparsed: " + msg);
            return;
        }

        // Ignore player chat like "Name: Steve paid you $1M"
        String prefix = msg.substring(0, m.start());
        if (prefix.contains(":") || prefix.contains("<")) return;

        // Ignore duplicates (e.g. same payment in chat and action bar)
        String key = m.group(1) + m.group(2);
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastTime < 1500) return;
        lastKey = key;
        lastTime = now;

        queue.add(new Alert(m.group(1), m.group(2).trim()));
    }

    private void echo(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[PayAlert] " + text), false);
    }

    private void render(DrawContext ctx, RenderTickCounter tick) {
        long now = System.currentTimeMillis();

        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            startTime = now;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.getSoundManager().play(
                    net.minecraft.client.sound.PositionedSoundInstance.master(
                            SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f));
        }

        long t = now - startTime;
        long total = SLIDE_IN_MS + HOLD_MS + SLIDE_OUT_MS;
        if (t >= total) {
            current = null;
            return;
        }

        // progress 0 = fully off-screen, 1 = fully visible
        float progress;
        if (t < SLIDE_IN_MS) {
            float p = t / (float) SLIDE_IN_MS;
            progress = easeOutBack(p);
        } else if (t < SLIDE_IN_MS + HOLD_MS) {
            progress = 1f;
        } else {
            float p = (t - SLIDE_IN_MS - HOLD_MS) / (float) SLIDE_OUT_MS;
            progress = 1f - easeInCubic(p);
        }

        int screenW = ctx.getScaledWindowWidth();
        int visibleX = screenW - WIDTH - MARGIN;
        int hiddenX = screenW + 4;
        int x = Math.round(hiddenX + (visibleX - hiddenX) * progress);
        int y = MARGIN;

        float fade = Math.min(1f, Math.max(0f, progress));
        int a = (int) (230 * fade);

        // background + border
        ctx.fill(x, y, x + WIDTH, y + HEIGHT, argb(a, 20, 20, 28));
        ctx.fill(x, y, x + 3, y + HEIGHT, argb((int) (255 * fade), 255, 200, 40)); // gold accent bar
        ctx.fill(x, y, x + WIDTH, y + 1, argb(a, 255, 200, 40));
        ctx.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, argb(a, 255, 200, 40));

        // shimmer sweep across the box while it's on screen
        float sweep = ((t % 1400) / 1400f);
        int sx = x + (int) (sweep * (WIDTH + 30)) - 20;
        for (int i = 0; i < 14; i++) {
            int cx = sx + i;
            if (cx < x + 3 || cx >= x + WIDTH) continue;
            int sa = (int) (60 * fade * (1f - Math.abs(i - 7) / 7f));
            ctx.fill(cx, y + 1, cx + 1, y + HEIGHT - 1, argb(sa, 255, 255, 255));
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        int textA = Math.max(5, (int) (255 * fade)); // alpha 0 is treated as opaque in some paths
        ctx.drawTextWithShadow(mc.textRenderer, "\u2605 PAYMENT RECEIVED",
                x + 10, y + 6, argb(textA, 255, 200, 40));
        ctx.drawTextWithShadow(mc.textRenderer, "+$" + current.amount(),
                x + 10, y + 18, argb(textA, 90, 255, 120));
        String from = "from " + current.player();
        int fw = mc.textRenderer.getWidth(from);
        ctx.drawTextWithShadow(mc.textRenderer, from,
                x + WIDTH - fw - 8, y + 18, argb(textA, 200, 200, 210));
    }

    private static int argb(int a, int r, int g, int b) {
        return (Math.min(255, Math.max(0, a)) << 24) | (r << 16) | (g << 8) | b;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static float easeInCubic(float x) {
        return x * x * x;
    }
}
