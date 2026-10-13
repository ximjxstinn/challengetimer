package de.ximjxstinn.challengetimer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class ChallengeTimer implements ModInitializer {
	public static final String MOD_ID = "challengetimer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String FILE_NAME = "challengetimer.properties";
	private static final int DISPLAY_INTERVAL_TICKS = 10;   // Actionbar alle 0,5 s auffrischen
	private static final int SAVE_INTERVAL_TICKS = 20 * 30; // alle 30 s speichern

	private static TimerState state = new TimerState();
	private static Path saveFile;
	private static int tickCounter = 0;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			saveFile = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
			state = TimerState.load(saveFile);
			tickCounter = 0;
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			state.pause();
			save();
		});

		ServerTickEvents.END_SERVER_TICK.register(ChallengeTimer::onTick);

		// Nach einem Tod (alive == false) beim Respawn in den Zuschauermodus setzen.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive && state.spectatorOnDeath()) {
				newPlayer.setGameMode(GameType.SPECTATOR);
				newPlayer.sendSystemMessage(prefix().append(Component.literal(
						"Du bist gestorben und jetzt im Zuschauermodus.").withStyle(ChatFormatting.YELLOW)));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));

		LOGGER.info("Challenge Timer geladen");
	}

	// ------------------------------------------------------------------ Tick

	private static void onTick(MinecraftServer server) {
		if (state.tick()) {
			onCountdownFinished(server);
		}
		tickCounter++;
		if (tickCounter % DISPLAY_INTERVAL_TICKS == 0) {
			updateDisplay(server);
		}
		if (tickCounter % SAVE_INTERVAL_TICKS == 0) {
			save();
		}
	}

	private static void updateDisplay(MinecraftServer server) {
		if (!state.visible()) return;
		Component text = displayText();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSetActionBarTextPacket(text));
		}
	}

	private static Component displayText() {
		String time = TimerState.format(state.timeMs());
		if (state.running()) {
			return Component.literal(time).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		}
		if (!state.countdown() && state.timeMs() == 0) {
			return Component.literal("Timer pausiert").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC);
		}
		return Component.literal(time + " ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("(pausiert)").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
	}

	private static void onCountdownFinished(MinecraftServer server) {
		Component title = Component.literal("Zeit abgelaufen!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		Component subtitle = Component.literal("Der Countdown ist vorbei").withStyle(ChatFormatting.GRAY);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			if (state.spectatorOnEnd()) {
				player.setGameMode(GameType.SPECTATOR);
			}
		}
		broadcast(server, state.spectatorOnEnd()
				? "Der Countdown ist abgelaufen – alle Spieler sind jetzt im Zuschauermodus."
				: "Der Countdown ist abgelaufen.");
		save();
	}

	// -------------------------------------------------------------- Befehle

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("timer")
				.executes(ChallengeTimer::status)
				.then(Commands.literal("start").executes(ChallengeTimer::start))
				.then(Commands.literal("resume").executes(ChallengeTimer::start))
				.then(Commands.literal("pause").executes(ChallengeTimer::pause))
				.then(Commands.literal("reset").executes(ChallengeTimer::reset))
				.then(Commands.literal("set")
						.then(Commands.argument("zeit", StringArgumentType.greedyString())
								.executes(ChallengeTimer::set)))
				.then(Commands.literal("modus")
						.then(Commands.literal("stoppuhr").executes(ctx -> mode(ctx, false)))
						.then(Commands.literal("countdown").executes(ctx -> mode(ctx, true))))
				.then(Commands.literal("anzeige")
						.then(Commands.literal("an").executes(ctx -> visibility(ctx, true)))
						.then(Commands.literal("aus").executes(ctx -> visibility(ctx, false))))
				.then(Commands.literal("spectator")
						.executes(ChallengeTimer::spectatorStatus)
						.then(Commands.literal("ende")
								.then(Commands.literal("an").executes(ctx -> spectatorEnd(ctx, true)))
								.then(Commands.literal("aus").executes(ctx -> spectatorEnd(ctx, false))))
						.then(Commands.literal("tod")
								.then(Commands.literal("an").executes(ctx -> spectatorDeath(ctx, true)))
								.then(Commands.literal("aus").executes(ctx -> spectatorDeath(ctx, false)))))
				.then(Commands.literal("hilfe").executes(ChallengeTimer::help)));
	}

	private static String onOff(boolean value) {
		return value ? "an" : "aus";
	}

	private static int spectatorStatus(CommandContext<CommandSourceStack> ctx) {
		reply(ctx, "Zuschauermodus bei Countdown-Ende: " + onOff(state.spectatorOnEnd())
				+ " | bei Tod: " + onOff(state.spectatorOnDeath()));
		return 1;
	}

	private static int spectatorEnd(CommandContext<CommandSourceStack> ctx, boolean value) {
		state.setSpectatorOnEnd(value);
		save();
		broadcast(ctx.getSource().getServer(), "Zuschauermodus bei Countdown-Ende: " + onOff(value));
		return 1;
	}

	private static int spectatorDeath(CommandContext<CommandSourceStack> ctx, boolean value) {
		state.setSpectatorOnDeath(value);
		save();
		broadcast(ctx.getSource().getServer(), "Zuschauermodus bei Tod: " + onOff(value));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		String mode = state.countdown() ? "Countdown" : "Stoppuhr";
		String run = state.running() ? "läuft" : "pausiert";
		reply(ctx, "Timer (" + mode + ", " + run + "): " + TimerState.format(state.timeMs())
				+ "  –  /timer hilfe für alle Befehle");
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		if (state.running()) {
			fail(ctx, "Der Timer läuft bereits.");
			return 0;
		}
		if (state.countdown() && state.timeMs() <= 0) {
			fail(ctx, "Stell zuerst eine Zeit ein, z. B. /timer set 30m");
			return 0;
		}
		state.start();
		changed(ctx, "Der Timer wurde gestartet.");
		return 1;
	}

	private static int pause(CommandContext<CommandSourceStack> ctx) {
		if (!state.running()) {
			fail(ctx, "Der Timer ist bereits pausiert.");
			return 0;
		}
		state.pause();
		changed(ctx, "Der Timer wurde pausiert.");
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) {
		state.reset();
		changed(ctx, "Der Timer wurde zurückgesetzt.");
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> ctx) {
		String input = StringArgumentType.getString(ctx, "zeit");
		long ms = TimerState.parse(input);
		if (ms < 0) {
			fail(ctx, "Ungültige Zeit. Beispiele: 1h30m, 90m, 45s, 2d, 1:30:00");
			return 0;
		}
		state.set(ms);
		changed(ctx, "Der Timer wurde auf " + TimerState.format(ms) + " gesetzt.");
		return 1;
	}

	private static int mode(CommandContext<CommandSourceStack> ctx, boolean countdown) {
		state.setCountdown(countdown);
		changed(ctx, countdown
				? "Modus: Countdown (zählt von " + TimerState.format(state.timeMs()) + " runter). Zeit ändern mit /timer set"
				: "Modus: Stoppuhr (zählt hoch).");
		return 1;
	}

	private static int visibility(CommandContext<CommandSourceStack> ctx, boolean visible) {
		state.setVisible(visible);
		save();
		reply(ctx, visible ? "Die Anzeige ist jetzt sichtbar." : "Die Anzeige ist jetzt ausgeblendet (der Timer läuft weiter).");
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		reply(ctx, String.join("\n",
				"/timer – aktuellen Stand anzeigen",
				"/timer start (oder resume) – starten / fortsetzen",
				"/timer pause – anhalten",
				"/timer reset – zurücksetzen",
				"/timer set <zeit> – Zeit setzen, z. B. 1h30m, 45s, 1:30:00",
				"/timer modus stoppuhr|countdown – hoch- oder runterzählen",
				"/timer anzeige an|aus – Actionbar ein-/ausblenden",
				"/timer spectator – aktuelle Zuschauer-Einstellungen",
				"/timer spectator ende an|aus – Zuschauermodus, wenn der Countdown abläuft",
				"/timer spectator tod an|aus – Zuschauermodus nach dem Tod"));
		return 1;
	}

	// -------------------------------------------------------------- Helfer

	private static void changed(CommandContext<CommandSourceStack> ctx, String message) {
		save();
		broadcast(ctx.getSource().getServer(), message);
		updateDisplay(ctx.getSource().getServer());
	}

	private static MutableComponent prefix() {
		return Component.literal("[Timer] ").withStyle(ChatFormatting.GOLD);
	}

	private static void broadcast(MinecraftServer server, String message) {
		server.getPlayerList().broadcastSystemMessage(
				prefix().append(Component.literal(message).withStyle(ChatFormatting.YELLOW)), false);
	}

	private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(message).withStyle(ChatFormatting.YELLOW)), false);
	}

	private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
	}

	private static void save() {
		if (saveFile == null) return;
		try {
			state.save(saveFile);
		} catch (IOException e) {
			LOGGER.error("Timer konnte nicht gespeichert werden", e);
		}
	}
}
