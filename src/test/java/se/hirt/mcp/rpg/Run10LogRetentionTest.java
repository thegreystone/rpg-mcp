/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Run10LogRetentionTest {

	@TempDir
	Path directory;

	@Test
	void oldestClosedRuntimeGroupsArePrunedWhileCurrentAndLiveOwnersRemain() throws Exception {
		String[] closed = new String[36];
		for (int index = 0; index < closed.length; index++)
			closed[index] = log(10, index, false);
		String current = log(10, -2, false), active = log(10, -1, true);
		RuntimeLogRetention.prune(directory, current, 32, 100000);
		for (int index = 0; index < closed.length; index++) {
			assertEquals(index >= 4, Files.exists(directory.resolve(closed[index])));
			assertEquals(index >= 4, Files.exists(directory.resolve(closed[index] + ".owner")));
		}
		assertTrue(Files.exists(directory.resolve(current)));
		assertTrue(Files.exists(directory.resolve(active)));
	}

	@Test
	void byteBudgetCountsAllBackupsAndDeletesAWholeOldRuntime() throws Exception {
		String old = log(10, 1, false), recent = log(20, 2, false);
		Files.write(directory.resolve(old + ".1"), new byte[30]);
		Files.setLastModifiedTime(directory.resolve(old + ".1"), FileTime.fromMillis(1000));
		RuntimeLogRetention.prune(directory, "current-unused", 32, 30);
		assertTrue(Files.exists(directory.resolve(recent)));
		assertFalse(Files.exists(directory.resolve(old)));
		assertFalse(Files.exists(directory.resolve(old + ".1")));
		assertFalse(Files.exists(directory.resolve(old + ".owner")));
	}

	@Test
	void heldLeaseProtectsEvenAStalePidUntilTheOwnerReleasesIt() throws Exception {
		String guarded = log(10, 1, false);
		Files.writeString(directory.resolve(guarded + ".1"), "saved archive");
		try (var channel = FileChannel.open(directory.resolve(guarded + ".owner"), StandardOpenOption.WRITE);
				var lock = channel.lock()) {
			RuntimeLogRetention.prune(directory, "other", 0, 0);
			assertTrue(Files.exists(directory.resolve(guarded)));
			assertTrue(Files.exists(directory.resolve(guarded + ".1")));
		}
		RuntimeLogRetention.prune(directory, "other", 0, 0);
		assertFalse(Files.exists(directory.resolve(guarded)));
		assertFalse(Files.exists(directory.resolve(guarded + ".owner")));
	}

	@Test
	void unrelatedFilesDirectoriesAndUncertainOwnersAreNeverPruned() throws Exception {
		String corrupt = log(10, 1, false);
		Files.writeString(directory.resolve(corrupt + ".owner"), "incomplete");
		Files.writeString(directory.resolve("rpg.db"), "canonical data");
		Files.writeString(directory.resolve("rpg-mcp-server-custom.log"), "explicit custom log");
		Files.writeString(directory.resolve("notes.log"), "unrelated file");
		String directoryName = "rpg-mcp-server-" + UUID.randomUUID() + ".log";
		Files.createDirectory(directory.resolve(directoryName));
		RuntimeLogRetention.prune(directory, "other", 0, 0);
		assertEquals("canonical data", Files.readString(directory.resolve("rpg.db")));
		assertEquals("explicit custom log", Files.readString(directory.resolve("rpg-mcp-server-custom.log")));
		assertEquals("unrelated file", Files.readString(directory.resolve("notes.log")));
		assertTrue(Files.exists(directory.resolve(corrupt)));
		assertTrue(Files.isDirectory(directory.resolve(directoryName)));
	}

	@Test
	void anotherMaintenanceProcessDefersCleanupWithoutBlockingOrDeletingAnything() throws Exception {
		String old = log(10, 1, false);
		try (var channel = FileChannel.open(directory.resolve(".retention.lock"), StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); var lock = channel.lock()) {
			RuntimeLogRetention.prune(directory, "other", 0, 0);
			assertTrue(Files.exists(directory.resolve(old)));
		}
		RuntimeLogRetention.prune(directory, "other", 0, 0);
		assertFalse(Files.exists(directory.resolve(old)));
	}

	@Test
	void windowsLegacyHandlesAreProtectedAndClosedLegacyLogsCanBeRetired() throws Exception {
		String legacy = log(10, 1, false);
		Files.delete(directory.resolve(legacy + ".owner"));
		Files.writeString(directory.resolve(legacy + ".1"), "legacy archive");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			try (var channel = FileChannel.open(directory.resolve(legacy), StandardOpenOption.WRITE,
					ExtendedOpenOption.NOSHARE_DELETE)) {
				RuntimeLogRetention.prune(directory, "other", 0, 0);
				assertTrue(Files.exists(directory.resolve(legacy)));
				assertTrue(Files.exists(directory.resolve(legacy + ".1")));
			}
			RuntimeLogRetention.prune(directory, "other", 0, 0);
			assertFalse(Files.exists(directory.resolve(legacy)));
			assertFalse(Files.exists(directory.resolve(legacy + ".1")));
		} else {
			RuntimeLogRetention.prune(directory, "other", 0, 0);
			assertTrue(Files.exists(directory.resolve(legacy)), "Unmarked legacy owners are unknown on Unix.");
		}
	}

	private String log(int size, int ageOrder, boolean alive) throws Exception {
		String name = "rpg-mcp-server-" + UUID.randomUUID() + ".log";
		Path log = directory.resolve(name);
		Files.write(log, new byte[size]);
		Files.setLastModifiedTime(log, FileTime.fromMillis((ageOrder + 10L) * 1000));
		long pid = alive ? ProcessHandle.current().pid() : unusedPid();
		Files.writeString(directory.resolve(name + ".owner"), Long.toString(pid));
		return name;
	}

	private static long unusedPid() {
		long candidate = 1_000_000_000L;
		while (ProcessHandle.of(candidate).map(ProcessHandle::isAlive).orElse(false))
			candidate++;
		return candidate;
	}
}
