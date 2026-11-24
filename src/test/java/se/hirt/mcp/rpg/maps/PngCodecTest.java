/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PngCodecTest {

	@Test
	void rgbaRoundTripPreservesTransparencyExactly() {
		int[] pixels = {0xffaabbcc, 0x00112233, 0x80334455, 0xffffffff};
		PngCodec.Raster decoded = PngCodec.decode(PngCodec.encode(new PngCodec.Raster(2, 2, pixels)));
		assertEquals(2, decoded.width());
		assertEquals(2, decoded.height());
		assertArrayEquals(pixels, decoded.pixels());
	}

	@Test
	void importsAllFivePngScanlineFilters() throws Exception {
		for (int filter = 0; filter <= 4; filter++) {
			byte[] raw = {11, 22, 33, 44, 55, 66, 77, 88, 99, (byte) 110, (byte) 121, (byte) 132};
			var scanlines = new ByteArrayOutputStream();
			for (int row = 0; row < 2; row++) {
				scanlines.write(filter);
				for (int column = 0; column < 6; column++) {
					int original = raw[row * 6 + column] & 255;
					int left = column >= 3 ? raw[row * 6 + column - 3] & 255 : 0;
					int up = row > 0 ? raw[column] & 255 : 0;
					int upperLeft = row > 0 && column >= 3 ? raw[column - 3] & 255 : 0;
					int p = left + up - upperLeft;
					int a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - upperLeft);
					int prediction = switch (filter) {
					case 0 -> 0;
					case 1 -> left;
					case 2 -> up;
					case 3 -> (left + up) / 2;
					case 4 -> a <= b && a <= c ? left : b <= c ? up : upperLeft;
					default -> throw new AssertionError();
					};
					scanlines.write((original - prediction) & 255);
				}
			}
			PngCodec.Raster image = PngCodec.decode(png(2, 2, 2, scanlines.toByteArray()));
			assertArrayEquals(new int[] {0xff0b1621, 0xff2c3742, 0xff4d5863, 0xff6e7984}, image.pixels(),
					"PNG filter " + filter);
		}
	}

	@Test
	void rejectsCorruptAndOversizedImages() throws Exception {
		byte[] corrupt = PngCodec.encode(new PngCodec.Raster(1, 1, new int[] {0xffaabbcc}));
		corrupt[20] ^= 1;
		assertThrows(IllegalArgumentException.class, () -> PngCodec.decode(corrupt));
		assertThrows(IllegalArgumentException.class, () -> PngCodec.decode(png(5000, 1, 2, new byte[] {0})));
		assertThrows(IllegalArgumentException.class, () -> PngCodec.decode(new byte[] {(byte) 0xff, (byte) 0xd8}));
		assertThrows(IllegalArgumentException.class, () -> PngCodec.decode(png(1, 1, 2, new byte[] {0, 1, 2, 3, 4})));
	}

	private static byte[] png(int width, int height, int type, byte[] scanlines) throws Exception {
		var bytes = new ByteArrayOutputStream();
		var out = new DataOutputStream(bytes);
		out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
		var header = new ByteArrayOutputStream();
		var h = new DataOutputStream(header);
		h.writeInt(width);
		h.writeInt(height);
		h.write(new byte[] {8, (byte) type, 0, 0, 0});
		chunk(out, "IHDR", header.toByteArray());
		var compressed = new ByteArrayOutputStream();
		try (var z = new DeflaterOutputStream(compressed)) {
			z.write(scanlines);
		}
		chunk(out, "IDAT", compressed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return bytes.toByteArray();
	}

	private static void chunk(DataOutputStream out, String kind, byte[] body) throws Exception {
		byte[] name = kind.getBytes(StandardCharsets.US_ASCII);
		out.writeInt(body.length);
		out.write(name);
		out.write(body);
		var crc = new CRC32();
		crc.update(name);
		crc.update(body);
		out.writeInt((int) crc.getValue());
	}
}
