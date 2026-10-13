package de.ximjxstinn.challengetimer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hält den Zustand des Timers. Gezählt wird in Server-Ticks (1 Tick = 50 ms),
 * damit der Timer im Einzelspieler-Pausemenü automatisch stehen bleibt.
 */
public final class TimerState {
	public static final long MS_PER_TICK = 50L;

	private long timeMs = 0L;
	private long countdownStartMs = 0L;
	private boolean running = false;
	private boolean countdown = false;
	private boolean visible = true;

	/** @return true, wenn ein Countdown in diesem Tick abgelaufen ist. */
	public boolean tick() {
		if (!running) return false;
		if (countdown) {
			timeMs -= MS_PER_TICK;
			if (timeMs <= 0) {
				timeMs = 0;
				running = false;
				return true;
			}
		} else {
			timeMs += MS_PER_TICK;
		}
		return false;
	}

	public void start() { running = true; }
	public void pause() { running = false; }

	public void reset() {
		running = false;
		timeMs = countdown ? countdownStartMs : 0L;
	}

	public void set(long ms) {
		timeMs = Math.max(0, ms);
		if (countdown) countdownStartMs = timeMs;
	}

	public void setCountdown(boolean countdown) {
		this.countdown = countdown;
		running = false;
		if (countdown) {
			countdownStartMs = timeMs;
		}
	}

	public long timeMs() { return timeMs; }
	public boolean running() { return running; }
	public boolean countdown() { return countdown; }
	public boolean visible() { return visible; }
	public void setVisible(boolean visible) { this.visible = visible; }

	/** Formatiert z. B. als "05:09" oder "1:02:05:09" (Tage:Std:Min:Sek, nur so viel wie nötig). */
	public static String format(long ms) {
		long total = ms / 1000L;
		long days = total / 86400L;
		long hours = (total / 3600L) % 24L;
		long minutes = (total / 60L) % 60L;
		long seconds = total % 60L;
		if (days > 0) return String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds);
		if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
		return String.format("%02d:%02d", minutes, seconds);
	}

	private static final Pattern UNIT = Pattern.compile("(\\d+)\\s*([dhms])");

	/**
	 * Liest Zeitangaben wie "1h30m", "90m", "45s", "2d", "1:30:00" oder "90" (Minuten).
	 * @return Millisekunden oder -1, wenn die Eingabe ungültig ist.
	 */
	public static long parse(String input) {
		String s = input.trim().toLowerCase().replace(" ", "");
		if (s.isEmpty()) return -1;
		try {
			if (s.contains(":")) {
				String[] parts = s.split(":");
				if (parts.length > 3) return -1;
				long total = 0;
				for (String p : parts) {
					if (p.isEmpty()) return -1;
					total = total * 60 + Long.parseLong(p);
				}
				// "1:30" = 1 Min 30 Sek, "1:30:00" = 1 Std 30 Min
				return total * 1000L;
			}
			if (s.matches("\\d+")) {
				return Long.parseLong(s) * 60_000L;
			}
			Matcher m = UNIT.matcher(s);
			long total = 0;
			int consumed = 0;
			while (m.find()) {
				if (m.start() != consumed) return -1;
				long v = Long.parseLong(m.group(1));
				total += switch (m.group(2)) {
					case "d" -> v * 86_400_000L;
					case "h" -> v * 3_600_000L;
					case "m" -> v * 60_000L;
					default -> v * 1000L;
				};
				consumed = m.end();
			}
			return consumed == s.length() && consumed > 0 ? total : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public void save(Path file) throws IOException {
		Properties p = new Properties();
		p.setProperty("time", Long.toString(timeMs));
		p.setProperty("countdownStart", Long.toString(countdownStartMs));
		p.setProperty("countdown", Boolean.toString(countdown));
		p.setProperty("visible", Boolean.toString(visible));
		Files.createDirectories(file.getParent());
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			p.store(w, "Challenge Timer");
		}
	}

	public static TimerState load(Path file) {
		TimerState state = new TimerState();
		if (!Files.exists(file)) return state;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
			state.timeMs = Long.parseLong(p.getProperty("time", "0"));
			state.countdownStartMs = Long.parseLong(p.getProperty("countdownStart", "0"));
			state.countdown = Boolean.parseBoolean(p.getProperty("countdown", "false"));
			state.visible = Boolean.parseBoolean(p.getProperty("visible", "true"));
		} catch (IOException | NumberFormatException e) {
			ChallengeTimer.LOGGER.warn("Timer-Datei konnte nicht gelesen werden, starte bei 0", e);
		}
		// Nach einem Neustart ist der Timer immer pausiert.
		state.running = false;
		return state;
	}
}
