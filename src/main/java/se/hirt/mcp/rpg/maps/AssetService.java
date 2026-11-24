/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.protocol.RpgException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Campaign-owned immutable PNG artwork. Database registration and journaled bindings belong to the
 * caller.
 */
public final class AssetService {

	private final Path dataDirectory;

	public record Asset(String id, Path path, int width, int height, double repeatFeet) {
		public Map<String, Object> metadata() {
			return Map.of("content_hash", id, "mime_type", "image/png", "width", width, "height", height, "repeat_ft",
					repeatFeet);
		}
	}

	/** repeatFeet is the physical width of one image repetition; its aspect ratio is preserved. */
	public record Texture(PngCodec.Raster raster, double repeatFeet) {
		public Texture {
			if (raster == null || !Double.isFinite(repeatFeet) || repeatFeet <= 0 || repeatFeet > 100_000) {
				throw new IllegalArgumentException("A texture needs pixels and a positive physical repeat size.");
			}
		}
	}

	public AssetService(Path dataDirectory) {
		this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
	}

	/** Validate and copy artwork now; future reads never depend on the supplied source path. */
	public Asset importPng(long campaignId, Path source, double repeatFeet) {
		checkRepeat(repeatFeet);
		try {
			if (source == null || !Files.isRegularFile(source) || Files.size(source) > PngCodec.MAX_FILE_BYTES) {
				throw RpgException.invalidArgument("Supply an existing PNG file no larger than 24 MiB.");
			}
			return importPng(campaignId, Files.readAllBytes(source), repeatFeet);
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot read texture source: " + e.getMessage());
		}
	}

	/**
	 * The byte-backed path also supports uploads when client and server do not share a filesystem.
	 */
	public Asset importPng(long campaignId, byte[] bytes, double repeatFeet) {
		checkRepeat(repeatFeet);
		PngCodec.Raster raster = decode(bytes);
		String id = hash(bytes);
		Path target = assetPath(campaignId, id);
		Path temporary = null;
		try {
			Files.createDirectories(target.getParent());
			if (!Files.exists(target)) {
				temporary = Files.createTempFile(target.getParent(), "import-", ".tmp");
				Files.write(temporary, bytes);
				try {
					Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(temporary, target);
				} catch (FileAlreadyExistsException e) {
					// An identical concurrent import has already installed these immutable bytes.
				}
			}
			if (!hash(Files.readAllBytes(target)).equals(id)) {
				throw RpgException
						.invalidArgument("Stored texture failed its content checksum; import was not applied.");
			}
			return new Asset(id, target, raster.width(), raster.height(), repeatFeet);
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot save campaign texture: " + e.getMessage());
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// A leftover, unreferenced temporary file cannot change a registered asset.
				}
			}
		}
	}

	/**
	 * Load a registered hash within this campaign. No arbitrary filesystem path is accepted here.
	 */
	public Texture load(long campaignId, String id, double repeatFeet) {
		checkRepeat(repeatFeet);
		Path path = assetPath(campaignId, id);
		try {
			if (!Files.isRegularFile(path) || Files.size(path) > PngCodec.MAX_FILE_BYTES) {
				throw RpgException.invalidArgument("The campaign texture is missing or exceeds the image limit.");
			}
			byte[] bytes = Files.readAllBytes(path);
			if (!hash(bytes).equals(id)) {
				throw RpgException.invalidArgument("The campaign texture content checksum does not match.");
			}
			return new Texture(decode(bytes), repeatFeet);
		} catch (IOException e) {
			throw RpgException.invalidArgument("Cannot load campaign texture: " + e.getMessage());
		}
	}

	public Path assetPath(long campaignId, String id) {
		if (campaignId < 1 || id == null || !id.matches("[a-f0-9]{64}")) {
			throw RpgException.invalidArgument("A valid campaign and SHA-256 asset hash are required.");
		}
		Path target = dataDirectory.resolve("campaigns").resolve(Long.toString(campaignId)).resolve("assets")
				.resolve(id + ".png").normalize();
		if (!target.startsWith(dataDirectory)) {
			throw RpgException.invalidArgument("Asset path is outside the configured data directory.");
		}
		return target;
	}

	private static PngCodec.Raster decode(byte[] bytes) {
		try {
			return PngCodec.decode(bytes);
		} catch (IllegalArgumentException e) {
			throw RpgException.invalidArgument(e.getMessage());
		}
	}

	private static void checkRepeat(double repeatFeet) {
		if (!Double.isFinite(repeatFeet) || repeatFeet <= 0 || repeatFeet > 100_000) {
			throw RpgException.invalidArgument("repeat_ft must be positive and no larger than 100000 feet.");
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}
}
