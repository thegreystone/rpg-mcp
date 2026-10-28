/*
 * Copyright (C) 2026 Marcus Hirt
 * SPDX-License-Identifier: BSD-3-Clause
 */
package se.hirt.mcp.rpg.maps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Small, headless PNG codec. No desktop toolkit, fonts or native image libraries are required. */
public final class PngCodec {

	private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
	public static final int MAX_DIMENSION = 4096;
	public static final int MAX_PIXELS = 16_777_216;
	public static final int MAX_FILE_BYTES = 24 * 1024 * 1024;

	/** Pixels use ARGB, including alpha for imported textures. */
	public record Raster(int width, int height, int[] pixels) {
		public Raster {
			checkDimensions(width, height);
			if (pixels == null || pixels.length != (long) width * height) {
				throw new IllegalArgumentException("The pixel count must match the image dimensions.");
			}
		}
	}

	private PngCodec() {
	}

	public static byte[] encode(Raster raster) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = new DataOutputStream(bytes);
			out.write(SIGNATURE);
			var header = new ByteArrayOutputStream();
			var h = new DataOutputStream(header);
			h.writeInt(raster.width());
			h.writeInt(raster.height());
			h.write(new byte[] {8, 6, 0, 0, 0});
			chunk(out, "IHDR", header.toByteArray());
			var compressed = new ByteArrayOutputStream();
			try (var z = new DeflaterOutputStream(compressed)) {
				byte[] row = new byte[raster.width() * 4 + 1];
				for (int y = 0; y < raster.height(); y++) {
					for (int x = 0; x < raster.width(); x++) {
						int color = raster.pixels()[y * raster.width() + x];
						int i = x * 4 + 1;
						row[i] = (byte) (color >>> 16);
						row[i + 1] = (byte) (color >>> 8);
						row[i + 2] = (byte) color;
						row[i + 3] = (byte) (color >>> 24);
					}
					z.write(row);
				}
			}
			chunk(out, "IDAT", compressed.toByteArray());
			chunk(out, "IEND", new byte[0]);
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("PNG encoding failed.", e);
		}
	}

	/**
	 * Accepts noninterlaced, eight-bit RGB, RGBA, grayscale and indexed PNGs; rejects other inputs.
	 */
	public static Raster decode(byte[] encoded) {
		if (encoded == null || encoded.length < 33 || encoded.length > MAX_FILE_BYTES) {
			throw new IllegalArgumentException("PNG input must be between 33 bytes and 24 MiB.");
		}
		try {
			var in = new DataInputStream(new ByteArrayInputStream(encoded));
			if (!Arrays.equals(SIGNATURE, in.readNBytes(8))) {
				throw new IllegalArgumentException("Only PNG artwork is supported.");
			}
			int width = 0, height = 0, type = -1, channels = 0;
			byte[] palette = null, transparency = null;
			boolean header = false, ended = false, data = false;
			var compressed = new ByteArrayOutputStream();
			while (in.available() > 0) {
				int size = in.readInt();
				if (size < 0 || size > MAX_FILE_BYTES || size > in.available() - 8) {
					throw new IllegalArgumentException("Invalid PNG chunk length.");
				}
				byte[] kind = in.readNBytes(4);
				String name = new String(kind, java.nio.charset.StandardCharsets.US_ASCII);
				byte[] body = in.readNBytes(size);
				int expectedCrc = in.readInt();
				var crc = new CRC32();
				crc.update(kind);
				crc.update(body);
				if ((int) crc.getValue() != expectedCrc) {
					throw new IllegalArgumentException("PNG chunk checksum does not match.");
				}
				if (!header && !name.equals("IHDR")) {
					throw new IllegalArgumentException("PNG must start with its image header.");
				}
				switch (name) {
				case "IHDR" -> {
					if (header || size != 13) {
						throw new IllegalArgumentException("Invalid PNG image header.");
					}
					var h = new DataInputStream(new ByteArrayInputStream(body));
					width = h.readInt();
					height = h.readInt();
					checkDimensions(width, height);
					int depth = h.readUnsignedByte();
					type = h.readUnsignedByte();
					channels = switch (type) {
					case 0, 3 -> 1;
					case 2 -> 3;
					case 4 -> 2;
					case 6 -> 4;
					default -> throw new IllegalArgumentException("Unsupported PNG color type.");
					};
					if (depth != 8 || h.readUnsignedByte() != 0 || h.readUnsignedByte() != 0
							|| h.readUnsignedByte() != 0) {
						throw new IllegalArgumentException(
								"Import an eight-bit noninterlaced PNG (RGB, RGBA, grayscale or indexed).");
					}
					header = true;
				}
				case "PLTE" -> {
					if (size == 0 || size > 768 || size % 3 != 0 || data) {
						throw new IllegalArgumentException("Invalid PNG palette.");
					}
					palette = body;
				}
				case "tRNS" -> {
					if (data || size > 256) {
						throw new IllegalArgumentException("Invalid PNG transparency.");
					}
					transparency = body;
				}
				case "IDAT" -> {
					compressed.write(body);
					data = true;
				}
				case "IEND" -> {
					if (size != 0 || !data || in.available() != 0) {
						throw new IllegalArgumentException("Invalid PNG end chunk.");
					}
					ended = true;
				}
				default -> {
					if ((kind[0] & 32) == 0) {
						throw new IllegalArgumentException("Unsupported critical PNG chunk: " + name);
					}
				}
				}
			}
			if (!ended || type == 3 && palette == null) {
				throw new IllegalArgumentException("PNG is incomplete.");
			}
			int stride = Math.multiplyExact(width, channels);
			byte[] previous = new byte[stride], current = new byte[stride];
			int[] pixels = new int[width * height];
			try (var z = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
				for (int y = 0; y < height; y++) {
					int filter = z.read();
					if (filter < 0 || filter > 4 || z.readNBytes(current, 0, stride) != stride) {
						throw new IllegalArgumentException("PNG scanlines are invalid or truncated.");
					}
					for (int i = 0; i < stride; i++) {
						int left = i >= channels ? current[i - channels] & 255 : 0;
						int up = previous[i] & 255;
						int upperLeft = i >= channels ? previous[i - channels] & 255 : 0;
						int prediction = switch (filter) {
						case 0 -> 0;
						case 1 -> left;
						case 2 -> up;
						case 3 -> (left + up) / 2;
						case 4 -> paeth(left, up, upperLeft);
						default -> throw new IllegalArgumentException("Unsupported PNG filter.");
						};
						current[i] = (byte) ((current[i] & 255) + prediction);
					}
					for (int x = 0; x < width; x++) {
						int i = x * channels;
						int a = 255, r = current[i] & 255, g = r, b = r;
						if (type == 3) {
							int index = r;
							if (index * 3 + 2 >= palette.length) {
								throw new IllegalArgumentException("PNG palette index is out of range.");
							}
							r = palette[index * 3] & 255;
							g = palette[index * 3 + 1] & 255;
							b = palette[index * 3 + 2] & 255;
							a = transparency != null && index < transparency.length ? transparency[index] & 255 : 255;
						} else if (type == 2 || type == 6) {
							g = current[i + 1] & 255;
							b = current[i + 2] & 255;
							if (type == 6) {
								a = current[i + 3] & 255;
							} else if (transparency != null && transparency.length == 6 && r == u16(transparency, 0)
									&& g == u16(transparency, 2) && b == u16(transparency, 4)) {
								a = 0;
							}
						} else if (type == 4) {
							a = current[i + 1] & 255;
						} else if (transparency != null && transparency.length == 2 && r == u16(transparency, 0)) {
							a = 0;
						}
						pixels[y * width + x] = a << 24 | r << 16 | g << 8 | b;
					}
					byte[] swap = previous;
					previous = current;
					current = swap;
				}
				if (z.read() != -1) {
					throw new IllegalArgumentException("PNG contains more decoded pixels than its dimensions allow.");
				}
			}
			return new Raster(width, height, pixels);
		} catch (IOException | ArithmeticException e) {
			throw new IllegalArgumentException("PNG cannot be decoded: " + e.getMessage(), e);
		}
	}

	private static int u16(byte[] bytes, int offset) {
		return (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255;
	}

	private static int paeth(int left, int up, int upperLeft) {
		int p = left + up - upperLeft;
		int a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - upperLeft);
		return a <= b && a <= c ? left : b <= c ? up : upperLeft;
	}

	private static void checkDimensions(int width, int height) {
		if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
				|| (long) width * height > MAX_PIXELS) {
			throw new IllegalArgumentException(
					"PNG dimensions must be positive, at most 4096 per side and 16 megapixels.");
		}
	}

	private static void chunk(DataOutputStream out, String name, byte[] body) throws IOException {
		byte[] kind = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		out.writeInt(body.length);
		out.write(kind);
		out.write(body);
		var crc = new CRC32();
		crc.update(kind);
		crc.update(body);
		out.writeInt((int) crc.getValue());
	}
}
