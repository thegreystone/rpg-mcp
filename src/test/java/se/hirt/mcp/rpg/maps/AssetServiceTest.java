/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import org.junit.jupiter.api.Test;
import se.hirt.mcp.rpg.protocol.RpgException;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class AssetServiceTest {

	@Test
	void importedArtworkSurvivesSourceDeletionAndDeduplicatesByContent() throws Exception {
		var root = Files.createTempDirectory("rpg-map-assets");
		var source = root.resolve("original.png");
		var raster = new PngCodec.Raster(2, 1, new int[] {0xffaa0011, 0x80336699});
		byte[] png = PngCodec.encode(raster);
		Files.write(source, png);
		var service = new AssetService(root.resolve("data"));
		AssetService.Asset first = service.importPng(7, source, 5);
		AssetService.Asset second = service.importPng(7, source, 10);
		assertEquals(first.id(), second.id());
		assertEquals(first.path(), second.path());
		assertEquals(5d, first.repeatFeet());
		assertEquals(10d, second.repeatFeet(), "physical binding is separate from immutable image content");
		Files.delete(source);
		var reopened = new AssetService(root.resolve("data"));
		assertArrayEquals(raster.pixels(), reopened.load(7, first.id(), 5).raster().pixels());
		assertThrows(RpgException.class, () -> reopened.load(8, first.id(), 5));
		assertThrows(RpgException.class, () -> reopened.load(7, "../../outside", 5));
	}

	@Test
	void invalidOrChangedArtworkIsRejected() throws Exception {
		var service = new AssetService(Files.createTempDirectory("rpg-map-invalid-assets"));
		assertThrows(RpgException.class, () -> service.importPng(1, new byte[] {1, 2, 3}, 5));
		byte[] png = PngCodec.encode(new PngCodec.Raster(1, 1, new int[] {0xff112233}));
		assertThrows(RpgException.class, () -> service.importPng(1, png, 0));
		assertThrows(RpgException.class, () -> service.importPng(-1, png, 5));
		AssetService.Asset saved = service.importPng(1, png, 5);
		Files.write(saved.path(), PngCodec.encode(new PngCodec.Raster(1, 1, new int[] {0xffaabbcc})));
		assertThrows(RpgException.class, () -> service.load(1, saved.id(), 5));
	}
}
