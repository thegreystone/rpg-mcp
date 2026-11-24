package se.hirt.mcp.rpg.maps;

import se.hirt.mcp.rpg.protocol.RpgException;

import java.util.List;
import java.util.Map;

/** Stable local atlas coordinates. Sheet origins do not move when neighbors are added. */
public final class AtlasCoordinates {

	private AtlasCoordinates() {
	}

	public record Sheet(long x, long y) {
		public String address(long atlasId) {
			return "atlas:" + atlasId + "[" + x + "," + y + "]";
		}
	}

	public static Sheet containing(double eastFt, double northFt, double widthFt, double heightFt) {
		positive(widthFt, "sheet_width_ft");
		positive(heightFt, "sheet_height_ft");
		if (!Double.isFinite(eastFt) || !Double.isFinite(northFt)) {
			throw RpgException.invalidArgument("Atlas coordinates must be finite.");
		}
		double x = Math.floor(eastFt / widthFt), y = Math.floor(northFt / heightFt);
		if (Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000) {
			throw RpgException
					.invalidArgument("The requested atlas coordinate is outside the supported regional frame.");
		}
		return new Sheet((long) x, (long) y);
	}

	public static List<Double> bounds(long x, long y, double widthFt, double heightFt) {
		positive(widthFt, "sheet_width_ft");
		positive(heightFt, "sheet_height_ft");
		return List.of(x * widthFt, y * heightFt, widthFt, heightFt);
	}

	/** Regional equirectangular frame; poles require another explicitly linked frame. */
	public static Map<String, Object> geographic(
		double eastFt, double northFt, double originLatitude, double originLongitude, double radiusM) {
		positive(radiusM, "world_radius_m");
		if (!Double.isFinite(originLatitude) || Math.abs(originLatitude) >= 85 || !Double.isFinite(originLongitude)
				|| Math.abs(originLongitude) > 180) {
			throw RpgException
					.invalidArgument("Regional origin must be between 85 degrees south/north and valid longitude.");
		}
		double latitude = originLatitude + Math.toDegrees(northFt * 0.3048 / radiusM);
		double longitude = originLongitude
				+ Math.toDegrees(eastFt * 0.3048 / (radiusM * Math.cos(Math.toRadians(originLatitude))));
		if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) >= 85
				|| Math.abs(longitude - originLongitude) > 30) {
			throw RpgException.invalidArgument(
					"This position needs another geographic frame; existing sheet addresses remain stable.");
		}
		longitude = ((longitude + 540) % 360) - 180;
		double absolute = Math.abs(latitude);
		String band = absolute < 23.5 ? "TROPICAL" : absolute < 35 ? "SUBTROPICAL"
				: absolute < 60 ? "TEMPERATE" : absolute < 70 ? "SUBARCTIC" : absolute < 78 ? "TUNDRA" : "POLAR";
		return Map.of("latitude", latitude, "longitude", longitude, "latitude_band", band, "note",
				"Latitude tendency; elevation, moisture, coast, season and saved climate can modify it.");
	}

	private static void positive(double n, String field) {
		if (!Double.isFinite(n) || n <= 0) {
			throw RpgException.invalidArgument(field + " must be finite and positive.");
		}
	}
}
