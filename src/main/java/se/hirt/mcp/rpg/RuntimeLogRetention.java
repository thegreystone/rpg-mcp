/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Best-effort retention for this application's generated logs, independent of campaign state. */
@ApplicationScoped
public class RuntimeLogRetention {

	static final int MAX_HISTORY = 32;
	static final long MAX_HISTORY_BYTES = 32L * 1024 * 1024;
	static final String DEFAULT_PATH = "${rpg.data-dir}/logs/rpg-mcp-server-${quarkus.uuid:build}.log";
	private static final Pattern GENERATED = Pattern.compile(
			"^(rpg-mcp-server-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.log)(?:\\.[0-9]+)?$",
			Pattern.CASE_INSENSITIVE);
	private FileChannel leaseChannel;
	private FileLock lease;

	void start(@Observes
	StartupEvent event, RpgConfig settings) {
		try {
			var config = ConfigProvider.getConfig();
			String explicitProperty = System.getProperty("quarkus.log.file.path");
			String explicitEnvironment = System.getenv("QUARKUS_LOG_FILE_PATH");
			if (!config.getValue("quarkus.log.file.enabled", Boolean.class)
					|| explicitProperty != null && !explicitProperty.isBlank()
					|| explicitEnvironment != null && !explicitEnvironment.isBlank()
					|| !DEFAULT_PATH.equals(config.getConfigValue("quarkus.log.file.path").getRawValue()))
				return; // An explicit path, including a shared log, remains entirely user-managed.
			Path directory = settings.dataPath().toAbsolutePath().normalize().resolve("logs");
			Path log = Path.of(config.getValue("quarkus.log.file.path", String.class)).toAbsolutePath().normalize();
			if (!directory.equals(log.getParent()) || !GENERATED.matcher(log.getFileName().toString()).matches()
					|| Files.isSymbolicLink(directory))
				return;
			Files.createDirectories(directory);
			Path marker = directory.resolve(log.getFileName() + ".owner");
			leaseChannel = FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS);
			lease = leaseChannel.tryLock();
			if (lease == null) {
				close();
				return;
			}
			leaseChannel.truncate(0);
			leaseChannel.write(StandardCharsets.US_ASCII.encode(Long.toString(ProcessHandle.current().pid())));
			leaseChannel.force(false);
			prune(directory, log.getFileName().toString(), MAX_HISTORY, MAX_HISTORY_BYTES);
		} catch (IOException | RuntimeException ignored) {
			// Logging maintenance must not prevent a STDIO session or emit recursive logging errors.
			close();
		}
	}

	void stop(@Observes
	ShutdownEvent event) {
		close();
	}

	private void close() {
		try {
			if (lease != null)
				lease.close();
		} catch (IOException ignored) {
		}
		try {
			if (leaseChannel != null)
				leaseChannel.close();
		} catch (IOException ignored) {
		}
		lease = null;
		leaseChannel = null;
	}

	/** Retain the newest inactive runtime groups within both limits; active owners are excluded. */
	static void prune(Path directory, String currentLog, int maxHistory, long maxBytes) throws IOException {
		if (Files.isSymbolicLink(directory))
			return;
		Path maintenance = directory.resolve(".retention.lock");
		try (var channel = FileChannel.open(maintenance, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS)) {
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException busy) {
				return;
			}
			if (lock == null)
				return;
			try (lock) {
				Map<String, List<Path>> groups = new LinkedHashMap<>();
				try (var entries = Files.list(directory)) {
					for (Path file : entries.toList()) {
						var match = GENERATED.matcher(file.getFileName().toString());
						if (match.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
								&& !match.group(1).equals(currentLog))
							groups.computeIfAbsent(match.group(1), key -> new ArrayList<>()).add(file);
					}
				}
				var history = new ArrayList<LogGroup>();
				for (var entry : groups.entrySet()) {
					entry.getValue().sort(Comparator.comparing(file -> file.getFileName().toString()));
					Path marker = directory.resolve(entry.getKey() + ".owner");
					boolean managed = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
					if (Files.isSymbolicLink(marker) || managed && ownerAlive(marker))
						continue;
					// Older Windows runtimes have no lease. Their open log handles prevent deletion.
					// On other platforms an unmarked legacy log might still be open: leave it alone.
					if (!managed && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
						continue;
					long bytes = 0, latest = 0;
					for (Path file : entry.getValue()) {
						bytes += Files.size(file);
						latest = Math.max(latest,
								Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
					}
					history.add(new LogGroup(entry.getValue(), marker, managed, bytes, latest));
				}
				history.sort(Comparator.comparingLong(LogGroup::latest).reversed());
				long retainedBytes = 0;
				int retained = 0;
				for (LogGroup group : history) {
					if (retained < maxHistory && group.bytes() <= maxBytes - retainedBytes) {
						retained++;
						retainedBytes += group.bytes();
					} else
						remove(group);
				}
			}
		}
	}

	private static boolean ownerAlive(Path marker) {
		try {
			String pid = Files.readString(marker, StandardCharsets.US_ASCII).trim();
			return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
		} catch (IOException | RuntimeException unreadable) {
			return true; // An incomplete/concurrently written or unknown marker is never proof of death.
		}
	}

	/**
	 * Windows locks forbid re-opening a locked region, including through another handle in this
	 * JVM.
	 */
	private static boolean ownerAlive(FileChannel channel) {
		try {
			ByteBuffer bytes = ByteBuffer.allocate(64);
			int read = channel.read(bytes, 0);
			if (read <= 0 || read == bytes.capacity())
				return true;
			bytes.flip();
			long pid = Long.parseLong(StandardCharsets.US_ASCII.decode(bytes).toString().trim());
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		} catch (IOException | RuntimeException unreadable) {
			return true;
		}
	}

	private static void remove(LogGroup group) {
		try {
			if (!group.managed()) {
				deleteLogs(group);
				return;
			}
			try (var channel = FileChannel.open(group.marker(), StandardOpenOption.READ, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS)) {
				FileLock lock;
				try {
					lock = channel.tryLock();
				} catch (OverlappingFileLockException busy) {
					return;
				}
				if (lock == null)
					return;
				try (lock) {
					if (ownerAlive(channel))
						return;
					deleteLogs(group);
				}
			}
			if (group.files().stream().noneMatch(Files::exists))
				Files.deleteIfExists(group.marker());
		} catch (IOException | RuntimeException ignored) {
			// In-use legacy files and unavailable filesystem locks are left for a later launch.
		}
	}

	private static void deleteLogs(LogGroup group) throws IOException {
		for (Path file : group.files())
			if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				Files.deleteIfExists(file);
	}

	private record LogGroup(List<Path> files, Path marker, boolean managed, long bytes, long latest) {
	}
}
